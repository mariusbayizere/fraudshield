package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import io.github.mariusbayizere.fraudshield.decision.adapter.resilience.DegradedMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis idempotency with the PostgreSQL fallback while Redis is down (C.4), and verification
 * against PostgreSQL whenever Redis may not hold a decision that was made (ADR 0067): after this
 * instance decided without Redis, after Redis lost its data, and after an attempt whose outcome was
 * uncertain. Without it, a transaction decided during an outage would be decided again once Redis
 * answers, and its client could receive a different decision.
 */
public final class ResilientIdempotency implements IdempotencyStore {

  /** How long new claims are verified after decisions Redis does not hold (the records' TTL). */
  static final Duration VERIFY_FOR = RedisIdempotency.TTL;

  private final IdempotencyStore redis;
  private final IdempotencyStore database;
  private final DegradedMode mode;
  private final Clock clock;
  private final Runnable unverified;
  private final Set<UUID> decidedWithoutRedis = ConcurrentHashMap.newKeySet();

  /**
   * Creates the store.
   *
   * @param redis primary
   * @param database fallback, and the durable record verified claims consult
   * @param mode shared degraded-mode flag
   * @param clock clock for the verification window
   * @param unverified called for each claim decided without the verification PostgreSQL could not
   *     give
   */
  public ResilientIdempotency(
      IdempotencyStore redis,
      IdempotencyStore database,
      DegradedMode mode,
      Clock clock,
      Runnable unverified) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.database = Objects.requireNonNull(database, "database");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.unverified = Objects.requireNonNull(unverified, "unverified");
  }

  @Override
  public Claim claim(UUID institutionId, UUID transactionId, byte[] fingerprint) {
    if (!mode.skipPrimary()) {
      try {
        Claim claim = redis.claim(institutionId, transactionId, fingerprint);
        mode.recovered();
        announceRecovery();
        return claim instanceof Claimed claimed && claimed.verify()
            ? verify(institutionId, transactionId, fingerprint, claimed)
            : claim;
      } catch (RuntimeException redisDown) {
        mode.failed();
      }
    } else {
      mode.fellBack();
    }
    decidedWithoutRedis.add(institutionId);
    return database.claim(institutionId, transactionId, fingerprint);
  }

  /**
   * Consults PostgreSQL before deciding a claim Redis cannot vouch for. A decision found there is
   * copied into Redis and replayed. If PostgreSQL cannot answer, the claim is decided unverified
   * and counted: decisions must continue while PostgreSQL is down (C.4), and the PostgreSQL writer
   * keeps the first decision if the submission had been decided before (ADR 0067 point 6).
   */
  private Claim verify(
      UUID institutionId, UUID transactionId, byte[] fingerprint, Claimed claimed) {
    Claim durable;
    try {
      durable = database.claim(institutionId, transactionId, fingerprint);
    } catch (RuntimeException unavailable) {
      unverified.run();
      return new Claimed(claimed.token(), false);
    }
    return switch (durable) {
      case Claimed first -> new Claimed(claimed.token(), false);
      case Replay replay ->
          new Replay(
              redis.complete(
                  institutionId, transactionId, claimed, fingerprint, replay.response()));
      case Conflict conflict -> {
        redis.release(institutionId, transactionId, claimed);
        yield conflict;
      }
      case InFlight inFlight -> {
        redis.release(institutionId, transactionId, claimed);
        yield inFlight;
      }
    };
  }

  /** After deciding without Redis, makes every instance verify that institution's new claims. */
  private void announceRecovery() {
    if (decidedWithoutRedis.isEmpty()) {
      return;
    }
    Instant until = clock.instant().plus(VERIFY_FOR);
    for (UUID institution : List.copyOf(decidedWithoutRedis)) {
      redis.requireVerification(institution, until);
      decidedWithoutRedis.remove(institution);
    }
  }

  @Override
  public java.util.Optional<byte[]> decided(UUID institutionId, UUID transactionId) {
    if (!mode.skipPrimary()) {
      try {
        java.util.Optional<byte[]> cached = redis.decided(institutionId, transactionId);
        if (cached.isPresent()) {
          return cached;
        }
      } catch (RuntimeException redisDown) {
        mode.failed();
      }
    }
    return database.decided(institutionId, transactionId);
  }

  @Override
  public byte[] complete(
      UUID institutionId, UUID transactionId, Claimed claim, byte[] fingerprint, byte[] response) {
    try {
      return redis.complete(institutionId, transactionId, claim, fingerprint, response);
    } catch (RuntimeException redisDown) {
      mode.failed();
      decidedWithoutRedis.add(institutionId);
      return response.clone();
    }
  }

  @Override
  public void release(UUID institutionId, UUID transactionId, Claimed claim) {
    try {
      redis.release(institutionId, transactionId, claim);
    } catch (RuntimeException redisDown) {
      mode.failed();
    }
  }

  @Override
  public void uncertain(UUID institutionId, UUID transactionId, Claimed claim) {
    try {
      redis.uncertain(institutionId, transactionId, claim);
    } catch (RuntimeException redisDown) {
      mode.failed();
      decidedWithoutRedis.add(institutionId);
    }
  }
}
