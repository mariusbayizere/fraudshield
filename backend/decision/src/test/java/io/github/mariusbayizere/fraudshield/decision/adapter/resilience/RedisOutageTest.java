package io.github.mariusbayizere.fraudshield.decision.adapter.resilience;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcAccountHistory;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcAccountProfiles;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcConfiguration;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcFreezes;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcOverdueHolds;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.PostgresSink;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisAccountState;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisCircuitBreakers;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisFreezes;
import io.github.mariusbayizere.fraudshield.decision.adapter.redis.RedisHoldSchedule;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.DurableSpool;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolDrainer;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolingEventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.HoldTimeoutService;
import io.github.mariusbayizere.fraudshield.decision.application.IngestDecision;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.ReasonCodes;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C.4 Redis failure: with Redis frozen mid-run, the decision path keeps deciding on its PostgreSQL
 * fallbacks (account state, freeze counter, decision states) and its local hold schedule, and
 * reports degraded mode; afterwards the overdue-hold sweep catches a hold whose schedule entry was
 * lost.
 */
@Tag("requires-docker")
@Tag("NFR-REL-01")
@Tag("FR-03-06")
@Tag("FR-03-02")
class RedisOutageTest {

  private static final Duration TIMEOUT = Duration.ofMillis(300);

  @TempDir Path spoolDirectory;

  private RedisTestServer redis;
  private StatefulRedisConnection<String, String> connection;
  private TestDatabase db;
  private DurableSpool spool;
  private SpoolDrainer writer;
  private final MutableClock clock = new MutableClock(NOW);
  private final DegradedMode mode = new DegradedMode();
  private final InMemoryPorts scorer = new InMemoryPorts();
  private DecisionService decisions;
  private HoldTimeoutService timeouts;
  private DecisionStatePort states;
  private JdbcOverdueHolds overdue;
  private HoldSchedulePort redisHolds;

  @BeforeEach
  void wire() throws Exception {
    redis = new RedisTestServer();
    connection = redis.connect();
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    DataSource app = db.dataSource("fs_app");
    spool = new DurableSpool(spoolDirectory, DurableSpool.Settings.DEFAULTS, Set.of("postgres"));
    writer =
        new SpoolDrainer(
            spool,
            "postgres",
            new PostgresSink(app, spoolDirectory.resolve("dead")),
            100,
            Duration.ofMillis(10),
            Duration.ofMillis(200));
    JdbcAccountProfiles profiles = new JdbcAccountProfiles(app);
    JdbcDecisionStates durableStates = new JdbcDecisionStates(app);
    redisHolds = new RedisHoldSchedule(connection, TIMEOUT);
    HoldSchedulePort holds = ResilientPorts.holds(redisHolds, mode);
    states =
        ResilientPorts.decisionStates(
            new RedisDecisionStates(connection, durableStates, TIMEOUT), durableStates, mode);
    SpoolingEventRecorder recorder = new SpoolingEventRecorder(spool, Duration.ofSeconds(5));
    decisions =
        new DecisionService(
            new JdbcConfiguration(app, clock),
            ResilientPorts.accountState(
                new RedisAccountState(connection, profiles, TIMEOUT, Duration.ofSeconds(5)),
                new JdbcAccountHistory(app, profiles),
                mode),
            scorer,
            ResilientPorts.freezes(
                new RedisFreezes(connection, TIMEOUT), new JdbcFreezes(app), mode),
            ResilientPorts.breakers(new RedisCircuitBreakers(connection, TIMEOUT), mode),
            holds,
            states,
            recorder,
            scorer,
            DecisionMetrics.NONE,
            DecisionSettings.DEFAULTS,
            clock);
    timeouts =
        new HoldTimeoutService(holds, states, recorder, DecisionMetrics.NONE, clock, "api-0");
    overdue = new JdbcOverdueHolds(app);
  }

  @AfterEach
  void stop() {
    writer.close();
    spool.close();
    connection.close();
    redis.close();
  }

  private IngestDecision decide(String account, double score, Channel channel) {
    scorer.scores = t -> Optional.of(score);
    Transaction t = Fixtures.transaction(UUID.randomUUID(), account, "1000", channel);
    Transaction timed =
        new Transaction(
            t.institutionId(),
            t.transactionId(),
            t.accountToken(),
            t.counterpartyToken(),
            t.amount(),
            t.amountRwf(),
            t.channel(),
            t.merchantCategoryCode(),
            t.latitude(),
            t.longitude(),
            t.deviceToken(),
            t.agentToken(),
            t.counterpartyCountry(),
            clock.instant(),
            clock.instant());
    IngestDecision decision = decisions.decide(timed, new byte[32], System.nanoTime());
    await().atMost(Duration.ofSeconds(10)).until(() -> spool.lagBytes("postgres") == 0);
    clock.advance(Duration.ofSeconds(5));
    return decision;
  }

  @Test
  void decisionsContinueOnFallbacksWhileRedisIsDownAndResumeAfter() throws Exception {
    String frozenEarlier = "tok_FrozenBeforeOutageAaaa01";
    for (int i = 0; i < 3; i++) {
      decide(frozenEarlier, 0.95, Channel.MOBILE_MONEY);
    }
    assertThat(mode.degraded()).isFalse();

    redis.pause();
    try {
      // Still frozen: the flag is restored from account_freeze_events.
      IngestDecision frozen = decide(frozenEarlier, 0.01, Channel.MOBILE_MONEY);
      assertThat(mode.degraded()).isTrue();
      assertThat(frozen.decision()).isEqualTo(Decision.DECLINE);
      assertThat(frozen.reasonCodes()).containsExactly(ReasonCodes.ACCOUNT_FROZEN);

      // The freeze still happens, counted from persisted auto-blocks.
      String duringOutage = "tok_HighDuringOutageAaaaBbb1";
      decide(duringOutage, 0.95, Channel.CARD);
      decide(duringOutage, 0.95, Channel.CARD);
      decide(duringOutage, 0.95, Channel.CARD);
      assertThat(decide(duringOutage, 0.01, Channel.CARD).reasonCodes())
          .containsExactly(ReasonCodes.ACCOUNT_FROZEN);

      // A hold still times out, from this instance's local schedule.
      IngestDecision held = decide("tok_HeldDuringOutageAaaaBbb1", 0.7, Channel.USSD);
      assertThat(held.decision()).isEqualTo(Decision.HOLD);
      clock.advance(Duration.ofSeconds(30));
      assertThat(timeouts.tick()).isEqualTo(1);
      assertThat(states.latest(INSTITUTION, held.transactionId()).orElseThrow().decision())
          .as("read from PostgreSQL once the writer drained")
          .isIn(DecisionValue.HOLD, DecisionValue.TIMEOUT_RELEASE);
      assertThat(mode.fallbacks()).isPositive();
    } finally {
      redis.unpause();
    }

    Thread.sleep(DegradedMode.RETRY_AFTER_NANOS / 1_000_000 + 100);
    IngestDecision after = decide("tok_AfterOutageAaaaBbbbCccc1", 0.2, Channel.ONLINE);
    assertThat(after.decision()).isEqualTo(Decision.APPROVE);
    assertThat(mode.degraded()).isFalse();
  }

  @Test
  void theSweepTimesOutHoldsWhoseScheduleEntryWasLost() {
    IngestDecision held = decide("tok_LostScheduleAaaaBbbbCcc1", 0.7, Channel.MOBILE_MONEY);
    assertThat(redisHolds.cancel(INSTITUTION, held.transactionId()))
        .as("the entry is lost")
        .isTrue();
    clock.advance(Duration.ofSeconds(40));
    assertThat(timeouts.tick()).isZero();
    assertThat(timeouts.reconcile(overdue, Duration.ofSeconds(5))).isEqualTo(1);
    assertThat(states.latest(INSTITUTION, held.transactionId()).orElseThrow().decision())
        .isEqualTo(DecisionValue.TIMEOUT_RELEASE);
    await().atMost(Duration.ofSeconds(10)).until(() -> spool.lagBytes("postgres") == 0);
    assertThat(timeouts.reconcile(overdue, Duration.ofSeconds(5))).isZero();
  }
}
