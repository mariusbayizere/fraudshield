package io.github.mariusbayizere.fraudshield.decision.adapter.resilience;

import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatusPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.FreezePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Wraps the Redis adapters with the C.4 fallbacks, so a Redis outage degrades the decision path
 * rather than stopping it. Every fallback use marks {@link DegradedMode}.
 *
 * <ul>
 *   <li>Account state: PostgreSQL arrivals through the same calculator.
 *   <li>Freeze counter: persisted auto-block events in the hour.
 *   <li>Hold schedule: an in-process schedule on this instance, with the durable overdue-hold sweep
 *       as the net for a process that dies holding it.
 *   <li>Decision states: reads from PostgreSQL; a failed cache write is tolerated, because the
 *       spool already holds the state durably.
 *   <li>Circuit breakers: counting is skipped (the monitor cannot open a breaker on counts it does
 *       not have, which errs towards not forcing holds) and the last known open set is kept.
 * </ul>
 */
public final class ResilientPorts {

  private ResilientPorts() {}

  private static <T> T attempt(DegradedMode mode, Supplier<T> primary, Supplier<T> fallback) {
    if (mode.skipPrimary()) {
      mode.fellBack();
      return fallback.get();
    }
    try {
      T value = primary.get();
      mode.recovered();
      return value;
    } catch (RuntimeException redisFailed) {
      mode.failed();
      return fallback.get();
    }
  }

  /**
   * Account state with the PostgreSQL fallback.
   *
   * @param redis primary
   * @param database fallback
   * @param mode degraded-mode flag
   * @return the resilient port
   */
  public static AccountStatusPort accountStatus(
      AccountStatusPort redis, AccountStatusPort database, DegradedMode mode) {
    return (institutionId, accountToken) ->
        attempt(
            mode,
            () -> redis.frozen(institutionId, accountToken),
            () -> database.frozen(institutionId, accountToken));
  }

  /**
   * Freeze counter with the PostgreSQL fallback.
   *
   * @param redis primary
   * @param database fallback
   * @param mode degraded-mode flag
   * @return the resilient port
   */
  public static FreezePort freezes(FreezePort redis, FreezePort database, DegradedMode mode) {
    return (institution, account, transaction, at) ->
        attempt(
            mode,
            () -> redis.recordHigh(institution, account, transaction, at),
            () -> database.recordHigh(institution, account, transaction, at));
  }

  /**
   * Decision states that tolerate a failed cache write.
   *
   * @param redis primary, which reads PostgreSQL itself when Redis lacks a state
   * @param database PostgreSQL reader, used directly while Redis is down
   * @param mode degraded-mode flag
   * @return the resilient port
   */
  public static DecisionStatePort decisionStates(
      DecisionStatePort redis, JdbcDecisionStates database, DegradedMode mode) {
    return new DecisionStatePort() {
      @Override
      public boolean save(DecisionState state) {
        return attempt(mode, () -> redis.save(state), () -> true);
      }

      @Override
      public Optional<DecisionState> latest(UUID institutionId, UUID transactionId) {
        return attempt(
            mode,
            () -> redis.latest(institutionId, transactionId),
            () -> {
              try {
                return database.latest(institutionId, transactionId);
              } catch (SQLException e) {
                throw new IllegalStateException("decision states are unavailable", e);
              }
            });
      }
    };
  }

  /**
   * Circuit breakers that keep deciding without Redis.
   *
   * @param redis primary
   * @param mode degraded-mode flag
   * @return the resilient port
   */
  public static CircuitBreakerPort breakers(CircuitBreakerPort redis, DegradedMode mode) {
    return new CircuitBreakerPort() {
      @Override
      public boolean isOpen(UUID institutionId, String mcc) {
        return redis.isOpen(institutionId, mcc);
      }

      @Override
      public void count(UUID institutionId, String mcc, Instant at, boolean fraud) {
        attempt(
            mode,
            () -> {
              redis.count(institutionId, mcc, at, fraud);
              return null;
            },
            () -> null);
      }

      @Override
      public void countConfirmedFraud(UUID institutionId, String mcc, Instant at) {
        attempt(
            mode,
            () -> {
              redis.countConfirmedFraud(institutionId, mcc, at);
              return null;
            },
            () -> null);
      }

      @Override
      public Collection<Key> active(Instant now) {
        return attempt(mode, () -> redis.active(now), List::of);
      }

      @Override
      public MccCircuitBreaker.WindowCounts counts(Key key, int window, Instant now) {
        return redis.counts(key, window, now);
      }

      @Override
      public CircuitBreakerState state(Key key) {
        return redis.state(key);
      }

      @Override
      public boolean compareAndSet(
          Key key, CircuitBreakerState expected, CircuitBreakerState next) {
        return redis.compareAndSet(key, expected, next);
      }
    };
  }

  /**
   * A hold schedule that falls back to this process while Redis is down.
   *
   * @param redis primary
   * @param mode degraded-mode flag
   * @return the resilient port
   */
  public static HoldSchedulePort holds(HoldSchedulePort redis, DegradedMode mode) {
    return new LocalFallbackHolds(redis, mode);
  }

  private static final class LocalFallbackHolds implements HoldSchedulePort {
    private final HoldSchedulePort redis;
    private final DegradedMode mode;
    private final ConcurrentHashMap<UUID, DueHold> local = new ConcurrentHashMap<>();

    LocalFallbackHolds(HoldSchedulePort redis, DegradedMode mode) {
      this.redis = Objects.requireNonNull(redis, "redis");
      this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    public void schedule(DueHold hold) {
      attempt(
          mode,
          () -> {
            redis.schedule(hold);
            return null;
          },
          () -> local.put(hold.transactionId(), hold));
    }

    @Override
    public boolean cancel(UUID institutionId, UUID transactionId) {
      boolean localRemoved = local.remove(transactionId) != null;
      return localRemoved
          || attempt(mode, () -> redis.cancel(institutionId, transactionId), () -> false);
    }

    @Override
    public List<DueHold> claimDue(Instant now, int max) {
      List<DueHold> due = new ArrayList<>();
      for (DueHold hold : List.copyOf(local.values())) {
        if (due.size() < max
            && !hold.deadline().isAfter(now)
            && local.remove(hold.transactionId()) != null) {
          due.add(hold);
        }
      }
      due.addAll(attempt(mode, () -> redis.claimDue(now, max - due.size()), List::of));
      return due;
    }

    @Override
    public boolean acquireLeadership(String instanceId) {
      drainLocal();
      // Without Redis there is no lease; every instance times out the holds it holds locally.
      return attempt(mode, () -> redis.acquireLeadership(instanceId), () -> !local.isEmpty());
    }

    /**
     * Moves holds scheduled during an outage back into Redis once it answers again.
     *
     * <p>Otherwise an instance that is not the leader after recovery never looks at its own local
     * holds, and they wait for the reconciliation sweep, outside D-18's ±500 ms (Principal Review
     * finding 19).
     */
    private void drainLocal() {
      if (local.isEmpty() || mode.skipPrimary()) {
        return;
      }
      for (DueHold hold : List.copyOf(local.values())) {
        try {
          redis.schedule(hold);
          local.remove(hold.transactionId());
        } catch (RuntimeException stillDown) {
          mode.failed();
          return;
        }
      }
    }
  }
}
