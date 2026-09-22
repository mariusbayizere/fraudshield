package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import io.github.mariusbayizere.fraudshield.decision.adapter.resilience.DegradedMode;
import java.util.Objects;
import java.util.UUID;

/** Redis idempotency with the PostgreSQL fallback while Redis is down (C.4). */
public final class ResilientIdempotency implements IdempotencyStore {

  private final IdempotencyStore redis;
  private final IdempotencyStore database;
  private final DegradedMode mode;

  /**
   * Creates the store.
   *
   * @param redis primary
   * @param database fallback
   * @param mode shared degraded-mode flag
   */
  public ResilientIdempotency(
      IdempotencyStore redis, IdempotencyStore database, DegradedMode mode) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.database = Objects.requireNonNull(database, "database");
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  @Override
  public Claim claim(UUID institutionId, UUID transactionId, byte[] fingerprint) {
    if (!mode.skipPrimary()) {
      try {
        Claim claim = redis.claim(institutionId, transactionId, fingerprint);
        mode.recovered();
        return claim;
      } catch (RuntimeException redisDown) {
        mode.failed();
      }
    } else {
      mode.fellBack();
    }
    return database.claim(institutionId, transactionId, fingerprint);
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
  public void complete(UUID institutionId, UUID transactionId, byte[] response) {
    try {
      redis.complete(institutionId, transactionId, response);
    } catch (RuntimeException redisDown) {
      mode.failed();
    }
  }

  @Override
  public void release(UUID institutionId, UUID transactionId) {
    try {
      redis.release(institutionId, transactionId);
    } catch (RuntimeException redisDown) {
      mode.failed();
    }
  }
}
