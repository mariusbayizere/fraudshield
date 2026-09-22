package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.ACCOUNT;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcAccountStatus;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.PostgresSink;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolRecord;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.FreezePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.testing.FactScenarios;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("requires-docker")
class RedisAdaptersTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static RedisTestServer server;
  private static StatefulRedisConnection<String, String> connection;
  private static TestDatabase db;

  @BeforeAll
  static void start() throws Exception {
    server = new RedisTestServer();
    connection = server.connect();
    db = TestDatabase.create();
    db.institution(INSTITUTION);
  }

  @AfterAll
  static void stop() {
    connection.close();
    server.close();
  }

  @BeforeEach
  void flush() {
    server.flush();
  }

  @Test
  @Tag("FR-03-06")
  @Tag("FR-02-09")
  void flushesRestoreTheFreezeAndFirstSeenStaysDurableForTheScorer() throws Exception {
    PostgresSink sink =
        new PostgresSink(db.dataSource("fs_app"), Files.createTempDirectory("dead"));
    List<SpoolRecord> records = new ArrayList<>();
    for (var fact : FactScenarios.everyKindOfFact()) {
      byte[] payload = FactCodec.encode(List.of(fact));
      records.add(new SpoolRecord(records.size(), records.size() + 1, payload));
    }
    sink.accept(records);
    RedisAccountStatus status =
        new RedisAccountStatus(
            connection,
            new JdbcAccountStatus(db.dataSource("fs_app")),
            TIMEOUT,
            Duration.ofSeconds(5));
    assertThat(status.frozen(INSTITUTION, ACCOUNT))
        .as("frozen by the scenario's third HIGH")
        .isTrue();
    // Written back, so the next read needs no database.
    assertThat(connection.sync().exists(RedisKeys.account(INSTITUTION, ACCOUNT, "frozen"))).isOne();
    final String other = "tok_NeverFrozenAaaaBbbbCccc01";
    assertThat(status.frozen(INSTITUTION, other)).isFalse();
    assertThat(connection.sync().exists(RedisKeys.account(INSTITUTION, other, "known"))).isOne();

    // PB-37: the account's durable first-seen is in account_profiles, where the scorer's database
    // fallback reads it with the read-only role (ADR 0033, ADR 0062).
    try (java.sql.Connection c = db.dataSource("fs_app_readonly").getConnection()) {
      c.setAutoCommit(false);
      try (java.sql.PreparedStatement tenant =
              c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)");
          java.sql.PreparedStatement read =
              c.prepareStatement(
                  "SELECT first_seen_at FROM account_profiles WHERE account_token = ?")) {
        tenant.setString(1, INSTITUTION.toString());
        tenant.execute();
        read.setString(1, ACCOUNT);
        try (java.sql.ResultSet row = read.executeQuery()) {
          assertThat(row.next()).isTrue();
          assertThat(row.getObject(1, java.time.OffsetDateTime.class).toInstant()).isEqualTo(NOW);
        }
      }
      c.rollback();
    }
  }

  @Test
  @Tag("FR-03-06")
  @Tag("NFR-REL-01")
  void stalledPostgresNeverStallsTheFreezeCheck() {
    javax.sql.DataSource stalled =
        new org.postgresql.ds.PGSimpleDataSource() {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            try {
              Thread.sleep(2_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            throw new java.sql.SQLException("stalled (test)", "08006");
          }
        };
    RedisAccountStatus status =
        new RedisAccountStatus(
            connection, new JdbcAccountStatus(stalled), TIMEOUT, Duration.ofMillis(50));
    final String account = "tok_StalledAccountAaaaBbbbCc1";
    long start = System.nanoTime();
    assertThat(status.frozen(INSTITUTION, account)).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
    assertThat(connection.sync().exists(RedisKeys.account(INSTITUTION, account, "known")))
        .as("unknown, so asked again next time")
        .isZero();
  }

  @Test
  @Tag("FR-03-06")
  void exactlyOneOfManyConcurrentThirdHighsFreezes() throws Exception {
    RedisFreezes freezes = new RedisFreezes(connection, TIMEOUT);
    assertThat(freezes.recordHigh(INSTITUTION, ACCOUNT, UUID.randomUUID(), NOW).frozeNow())
        .isFalse();
    assertThat(
            freezes
                .recordHigh(INSTITUTION, ACCOUNT, UUID.randomUUID(), NOW.plusSeconds(60))
                .highDecisionsInWindow())
        .isEqualTo(2);
    List<Callable<FreezePort.FreezeCheck>> racing = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      racing.add(
          () -> freezes.recordHigh(INSTITUTION, ACCOUNT, UUID.randomUUID(), NOW.plusSeconds(120)));
    }
    int froze = 0;
    try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
      for (Future<FreezePort.FreezeCheck> result : pool.invokeAll(racing)) {
        froze += result.get().frozeNow() ? 1 : 0;
      }
    }
    assertThat(froze).isEqualTo(1);
    // A HIGH more than 60 minutes after the others starts a new window.
    String other = "tok_AnotherAccountDdddEeeeFff1";
    freezes.recordHigh(INSTITUTION, other, UUID.randomUUID(), NOW);
    freezes.recordHigh(INSTITUTION, other, UUID.randomUUID(), NOW.plusSeconds(1));
    assertThat(
            freezes
                .recordHigh(INSTITUTION, other, UUID.randomUUID(), NOW.plus(Duration.ofMinutes(60)))
                .highDecisionsInWindow())
        .isEqualTo(2);
  }

  @Test
  @Tag("FR-03-02")
  @Tag("D-14")
  void holdsAreClaimedOrCancelledExactlyOnceAndOneInstanceLeads() throws Exception {
    RedisHoldSchedule holds = new RedisHoldSchedule(connection, TIMEOUT);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      holds.schedule(
          new HoldSchedulePort.DueHold(
              INSTITUTION,
              id,
              "USSD",
              MediumTimeoutPolicy.DECLINE_AND_VERIFY,
              NOW.plusSeconds(30)));
    }
    assertThat(holds.claimDue(NOW.plusSeconds(29), 100)).isEmpty();
    assertThat(holds.cancel(INSTITUTION, ids.get(0))).isTrue();
    assertThat(holds.cancel(INSTITUTION, ids.get(0))).isFalse();
    List<Callable<List<HoldSchedulePort.DueHold>>> pollers = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      pollers.add(() -> holds.claimDue(NOW.plusSeconds(30), 7));
    }
    List<UUID> claimed = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (Future<List<HoldSchedulePort.DueHold>> result : pool.invokeAll(pollers)) {
        result.get().forEach(h -> claimed.add(h.transactionId()));
      }
    }
    claimed.addAll(
        holds.claimDue(NOW.plusSeconds(30), 100).stream()
            .map(HoldSchedulePort.DueHold::transactionId)
            .toList());
    assertThat(claimed).doesNotHaveDuplicates().hasSize(49).doesNotContain(ids.get(0));
    assertThat(holds.cancel(INSTITUTION, ids.get(1)))
        .as("claimed holds cannot be cancelled")
        .isFalse();

    assertThat(holds.acquireLeadership("api-0")).isTrue();
    assertThat(holds.acquireLeadership("api-1")).isFalse();
    assertThat(holds.acquireLeadership("api-0")).isTrue();
    Thread.sleep(RedisHoldSchedule.LEASE.toMillis() + 200);
    assertThat(holds.acquireLeadership("api-1")).as("the lease lapsed").isTrue();
  }

  @Test
  @Tag("FR-03-07")
  void breakerCountsRollByMinuteAndStateChangesAreCompareAndSet() {
    RedisCircuitBreakers breakers = new RedisCircuitBreakers(connection, TIMEOUT);
    final RedisCircuitBreakers other = new RedisCircuitBreakers(connection, TIMEOUT);
    Instant base = Instant.parse("2026-09-22T10:00:30Z");
    for (int i = 0; i < 100; i++) {
      breakers.count(INSTITUTION, "6051", base.plusSeconds(i % 60), i < 6);
    }
    breakers.count(INSTITUTION, "6051", base.minus(Duration.ofMinutes(15)), true);
    breakers.countConfirmedFraud(INSTITUTION, "6051", base);
    CircuitBreakerPort.Key key = new CircuitBreakerPort.Key(INSTITUTION, "6051");
    assertThat(breakers.active(base)).contains(key);
    var counts = breakers.counts(key, 15, base.plusSeconds(30));
    assertThat(counts.transactions()).isEqualTo(100);
    assertThat(counts.fraud()).isEqualTo(7);
    // 09:45:30 is outside 09:47-10:01 (15 buckets) and inside 09:45-10:01 (17 buckets).
    assertThat(breakers.counts(key, 17, base.plusSeconds(30)).transactions()).isEqualTo(101);

    CircuitBreakerState current = breakers.state(key);
    CircuitBreakerState seenByOther = other.state(key);
    CircuitBreakerState opened = new CircuitBreakerState(true, base, base);
    assertThat(breakers.compareAndSet(key, current, opened)).isTrue();
    assertThat(other.compareAndSet(key, seenByOther, opened)).as("stale version").isFalse();
    assertThat(breakers.isOpen(INSTITUTION, "6051")).isTrue();
    assertThat(other.isOpen(INSTITUTION, "6051")).isFalse();
    assertThat(other.state(key)).isEqualTo(opened);
    assertThat(other.isOpen(INSTITUTION, "6051")).isTrue();
  }

  @Test
  @Tag("D-14")
  void decisionStatesAreCompareAndSetAndFallBackToPostgres() throws Exception {
    RedisDecisionStates states =
        new RedisDecisionStates(
            connection, new JdbcDecisionStates(db.dataSource("fs_app")), TIMEOUT);
    UUID tx = UUID.randomUUID();
    DecisionState hold =
        DecisionState.initial(INSTITUTION, tx, Decision.HOLD, NOW, List.of(), NOW.plusSeconds(30));
    DecisionState released =
        hold.next(
            DecisionValue.TIMEOUT_RELEASE,
            DecidedBy.TIMEOUT_POLICY,
            NOW.plusSeconds(30),
            List.of("REVIEW_TIMEOUT"));
    assertThat(states.save(released)).as("sequence 2 before 1").isFalse();
    assertThat(states.save(hold)).isTrue();
    assertThat(states.save(hold)).isFalse();
    assertThat(states.save(released)).isTrue();
    assertThat(states.latest(INSTITUTION, tx)).contains(released);
    assertThat(states.latest(INSTITUTION, UUID.randomUUID())).isEmpty();
  }
}
