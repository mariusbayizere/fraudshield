package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The bank's poll, {@code GET /api/v1/decisions/{transaction_id}} (decision-owner contract §8, ADR
 * 0113): PostgreSQL first, Redis only when PostgreSQL holds nothing or cannot answer.
 */
public final class FinalDecisions {

  /** What the poll answers. */
  public sealed interface Answer {
    /**
     * A state.
     *
     * @param state the latest state
     */
    record Found(DecisionState state) implements Answer {}

    /** 503 {@code decision-contested}: two different first decisions, until a resolution. */
    record Contested() implements Answer {}

    /** 503: neither PostgreSQL nor Redis can say; the transaction may be decided. */
    record Unavailable() implements Answer {}

    /** 404: PostgreSQL answers, and neither it nor Redis holds the transaction. */
    record NotFound() implements Answer {}
  }

  private final JdbcDecisionStates durable;
  private final RedisDecisionStates cache;

  /**
   * Creates the reader.
   *
   * @param durable PostgreSQL
   * @param cache the Redis state cache, read directly
   */
  public FinalDecisions(JdbcDecisionStates durable, RedisDecisionStates cache) {
    this.durable = Objects.requireNonNull(durable, "durable");
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /**
   * Reads the final decision.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @return the answer
   * @throws IllegalStateException on a database error other than unavailability, which is a 500
   */
  public Answer read(UUID institutionId, UUID transactionId) {
    JdbcDecisionStates.Poll poll;
    try {
      poll = durable.poll(institutionId, transactionId);
    } catch (SQLException e) {
      if (!unavailable(e)) {
        throw new IllegalStateException("the final decision could not be read", e);
      }
      // §8 step 4: Redis's state, which can be stale for at most the outage; a contested
      // transaction is answered from it too, because the contest cannot be read.
      return fromCache(institutionId, transactionId, new Answer.Unavailable());
    }
    if (poll.contested()) {
      return new Answer.Contested();
    }
    if (poll.state().isPresent()) {
      return new Answer.Found(poll.state().get());
    }
    // §8 step 3: the model's decision can be in Redis before the sink writes it.
    return fromCache(institutionId, transactionId, new Answer.NotFound());
  }

  private Answer fromCache(UUID institutionId, UUID transactionId, Answer absent) {
    Optional<DecisionState> cached;
    try {
      cached = cache.cached(institutionId, transactionId);
    } catch (RuntimeException redisDown) {
      return new Answer.Unavailable();
    }
    return cached.<Answer>map(Answer.Found::new).orElse(absent);
  }

  /**
   * §8: a connection failure or a timeout, never any other error.
   *
   * @param e the error
   * @return whether PostgreSQL is unavailable
   */
  static boolean unavailable(SQLException e) {
    String state = e.getSQLState();
    return e instanceof SQLTransientConnectionException
        || (state != null && (state.startsWith("08") || "57014".equals(state)));
  }
}
