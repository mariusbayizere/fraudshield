package io.github.mariusbayizere.fraudshield.decision.owner;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.lettuce.core.api.StatefulRedisConnection;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** P16: the bank's poll reads PostgreSQL first (decision-owner contract §8, ADR 0113). */
@Tag("requires-docker")
@Tag("D-14")
class FinalDecisionsTest {

  private TestDatabase db;
  private RedisTestServer redis;
  private StatefulRedisConnection<String, String> connection;
  private RedisDecisionStates cache;
  private FinalDecisions decisions;

  @BeforeEach
  void setUp() throws Exception {
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    redis = new RedisTestServer();
    connection = redis.connect();
    JdbcDecisionStates durable = new JdbcDecisionStates(db.dataSource("fs_app"));
    cache = new RedisDecisionStates(connection, durable, Duration.ofMillis(300));
    decisions = new FinalDecisions(durable, cache);
  }

  @AfterEach
  void tearDown() {
    connection.close();
    redis.close();
  }

  private void persist(DecisionState state) throws Exception {
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      s.execute("SET fraudshield.institution_id = '" + INSTITUTION + "'");
      s.execute(
          "INSERT INTO fraudshield.transaction_ids (institution_id, transaction_id,"
              + " request_fingerprint, transaction_timestamp) VALUES ('"
              + INSTITUTION
              + "', '"
              + state.transactionId()
              + "', decode(repeat('00', 32), 'hex'), now())");
      s.execute(
          "INSERT INTO fraudshield.decision_states (event_id, institution_id, transaction_id,"
              + " decision_sequence, decision, final, decided_at, decided_by) VALUES ('"
              + state.eventId()
              + "', '"
              + INSTITUTION
              + "', '"
              + state.transactionId()
              + "', 1, '"
              + state.decision()
              + "', true, now(), 'MODEL')");
    }
  }

  private static DecisionState approved() {
    return DecisionState.initial(
        INSTITUTION, UUID.randomUUID(), Decision.APPROVE, NOW, List.of(), null);
  }

  @Test
  void theViewWinsOverAnOlderOrLostRedisWrite() throws Exception {
    DecisionState state = approved();
    persist(state);
    // Redis holds another answer for the transaction: the view still wins (P16).
    cache.save(
        DecisionState.initial(
            INSTITUTION, state.transactionId(), Decision.DECLINE, NOW, List.of(), null));
    FinalDecisions.Answer answer = decisions.read(INSTITUTION, state.transactionId());
    assertThat(answer).isInstanceOf(FinalDecisions.Answer.Found.class);
    assertThat(((FinalDecisions.Answer.Found) answer).state().eventId()).isEqualTo(state.eventId());
  }

  @Test
  void beforeTheSinkHasRunRedisAnswersAndWithNeitherTheAnswerIsNotFound() {
    DecisionState state = approved();
    cache.save(state);
    assertThat(decisions.read(INSTITUTION, state.transactionId()))
        .isEqualTo(new FinalDecisions.Answer.Found(state));
    assertThat(decisions.read(INSTITUTION, UUID.randomUUID()))
        .isInstanceOf(FinalDecisions.Answer.NotFound.class);
  }

  @Test
  void contestedTransactionIsUnavailableUntilResolved() throws Exception {
    DecisionState state = approved();
    persist(state);
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO fraudshield.decision_owners (institution_id, transaction_id,"
              + " first_event_id, first_decision, sequence, decision, decided_by,"
              + " contested_since) VALUES ('"
              + INSTITUTION
              + "', '"
              + state.transactionId()
              + "', '"
              + state.eventId()
              + "', 'APPROVE', 1, 'APPROVE', 'MODEL', now())");
    }
    assertThat(decisions.read(INSTITUTION, state.transactionId()))
        .isInstanceOf(FinalDecisions.Answer.Contested.class);
  }

  @Test
  void withRedisDownAndNothingInTheViewTheAnswerIsUnavailableNotNotFound() {
    redis.pause();
    try {
      assertThat(decisions.read(INSTITUTION, UUID.randomUUID()))
          .isInstanceOf(FinalDecisions.Answer.Unavailable.class);
    } finally {
      redis.unpause();
    }
  }
}
