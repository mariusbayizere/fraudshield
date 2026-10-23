package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The latest decision state per transaction for {@code GET /decisions/{id}} (D-14): Redis first,
 * stored only if its sequence follows the stored one (a Lua compare-and-set), with PostgreSQL as
 * the durable fallback when Redis does not hold the transaction. A Redis failure propagates, so the
 * resilient wrapper can mark degraded mode and read PostgreSQL directly (C.4).
 */
public final class RedisDecisionStates implements DecisionStatePort {

  /** How long a state stays in Redis; older ones are read from PostgreSQL. */
  public static final Duration RETENTION = Duration.ofDays(8);

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SAVE =
      """
      local current = tonumber(redis.call('HGET', KEYS[1], 'sequence') or '0')
      if current + 1 ~= tonumber(ARGV[1]) then return 0 end
      redis.call('HSET', KEYS[1], 'sequence', ARGV[1], 'state', ARGV[2])
      redis.call('PEXPIRE', KEYS[1], ARGV[3])
      return 1
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final JdbcDecisionStates durable;
  private final Duration timeout;

  /**
   * Creates the adapter.
   *
   * @param connection shared Lettuce connection
   * @param durable PostgreSQL reader for the fallback
   * @param timeout bound on each Redis call
   */
  public RedisDecisionStates(
      StatefulRedisConnection<String, String> connection,
      JdbcDecisionStates durable,
      Duration timeout) {
    this.redis = connection.async();
    this.durable = Objects.requireNonNull(durable, "durable");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public boolean save(DecisionState state) {
    ObjectNode json = FactCodec.finalDecision(state);
    json.put("institution_id", state.institutionId().toString());
    Long saved =
        RedisSupport.await(
            redis.eval(
                SAVE,
                ScriptOutputType.INTEGER,
                new String[] {RedisKeys.decision(state.institutionId(), state.transactionId())},
                Integer.toString(state.sequence()),
                new String(JSON.writeValueAsBytes(json), StandardCharsets.UTF_8),
                Long.toString(RETENTION.toMillis())),
            timeout);
    return saved == 1L;
  }

  @Override
  public Optional<DecisionState> latest(UUID institutionId, UUID transactionId) {
    String cached = null;
    try {
      cached =
          RedisSupport.await(
              redis.hget(RedisKeys.decision(institutionId, transactionId), "state"), timeout);
    } catch (RedisSupport.RedisUnavailableException redisDown) {
      // fall through to PostgreSQL
    }
    if (cached != null) {
      return Optional.of(FactCodec.state(JSON.readTree(cached)));
    }
    try {
      return durable.latest(institutionId, transactionId);
    } catch (SQLException e) {
      throw new IllegalStateException("decision states are unavailable", e);
    }
  }
}
