package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.jpa.JpaConfiguration;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolDrainer;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolRecord;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.testing.FactScenarios;
import io.github.mariusbayizere.fraudshield.decision.testing.JpaTesting;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

/** The PostgreSQL writer and readers against the real schema, as {@code fs_app}. */
@Tag("requires-docker")
@Tag("D-15")
@Tag("D-30")
@Tag("FR-03-08")
class PostgresAdaptersTest {

  private static final String[] TABLES = {
    "transaction_ids",
    "v_transactions",
    "v_fraud_scores",
    "decision_states",
    "alert_queue_entries",
    "auto_block_events",
    "customer_notifications",
    "account_freeze_events",
    "label_events",
    "mcc_circuit_breaker_events",
    "alert_decisions",
    "account_profiles"
  };

  @TempDir Path deadLetters;
  private TestDatabase db;
  private PostgresSink sink;
  private JpaTesting jpa;

  @BeforeEach
  void setUp() throws SQLException {
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    sink = new PostgresSink(db.dataSource("fs_app"), deadLetters);
    jpa = new JpaTesting(db.dataSource("fs_app"));
  }

  @org.junit.jupiter.api.AfterEach
  void closeJpa() {
    jpa.close();
  }

  /** An analyst row, because rules reference their author. */
  private UUID analyst() throws SQLException {
    UUID analyst = UUID.randomUUID();
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.users (id, institution_id, first_name,"
              + " last_name, email, role, employee_id, department, password_hash)"
              + " VALUES (?, ?, 'Test', 'Officer', ?, 'RISK_OFFICER', ?,"
              + " 'RISK', '$2b$12$notARealHashJustTheShapeOfOne.............')",
          analyst,
          INSTITUTION,
          analyst + "@example.test",
          "EMP" + analyst.toString().substring(0, 5));
    }
    return analyst;
  }

  private static List<SpoolRecord> records(List<DecisionEvent> facts) {
    List<SpoolRecord> records = new ArrayList<>();
    long offset = 0;
    for (DecisionEvent fact : facts) {
      byte[] payload = FactCodec.encode(List.of(fact));
      records.add(new SpoolRecord(offset, offset + payload.length + 8, payload));
      offset += payload.length + 8;
    }
    return records;
  }

  private Map<String, Long> counts() throws SQLException {
    Map<String, Long> counts = new LinkedHashMap<>();
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      for (String table : TABLES) {
        String name = table.startsWith("v_") ? table.substring(2) : table;
        try (ResultSet rows = s.executeQuery("SELECT count(*) FROM fraudshield." + name)) {
          rows.next();
          counts.put(name, rows.getLong(1));
        }
      }
    }
    return counts;
  }

  private String one(String sql) throws SQLException {
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  @Test
  void everyFactIsWrittenAndReplayingTheSpoolWritesNothingNew() throws Exception {
    List<DecisionEvent> facts = FactScenarios.everyKindOfFact();
    long released =
        facts.stream()
            .filter(DecisionEvent.DecisionChanged.class::isInstance)
            .filter(
                e ->
                    ((DecisionEvent.DecisionChanged) e).state().decision()
                        == DecisionValue.TIMEOUT_RELEASE)
            .count();
    assertThat(released).isEqualTo(2);
    List<SpoolRecord> records = records(facts);
    sink.accept(records);
    Map<String, Long> first = counts();
    assertThat(first).allSatisfy((table, count) -> assertThat(count).as(table).isPositive());
    assertThat(first.get("transaction_ids")).isEqualTo(8);
    assertThat(first.get("account_freeze_events")).isEqualTo(1);
    assertThat(first.get("alert_decisions")).as("one AUTO_RELEASED per hold").isEqualTo(released);

    sink.accept(records);
    assertThat(counts()).isEqualTo(first);
    assertThat(sink.deadLettered()).isZero();
    assertThat(
            one(
                "SELECT status FROM fraudshield.alert_queue_entries WHERE tier = 'MEDIUM'"
                    + " AND status = 'AUTO_RELEASED'"))
        .isEqualTo("AUTO_RELEASED");
    assertThat(
            one("SELECT count(*) FROM fraudshield.fraud_scores" + " WHERE ml_unavailable_fallback"))
        .isEqualTo("1");
  }

  @Test
  void secondDecisionsForTheSameTransactionAreDropped() throws Exception {
    DecisionEvent.TransactionDecided first =
        FactScenarios.everyKindOfFact().stream()
            .filter(DecisionEvent.TransactionDecided.class::isInstance)
            .map(DecisionEvent.TransactionDecided.class::cast)
            .findFirst()
            .orElseThrow();
    DecisionState redecided =
        DecisionState.initial(
            INSTITUTION,
            first.transaction().transactionId(),
            first.outcome().decision(),
            NOW.plusSeconds(5),
            first.outcome().reasonCodes(),
            first.outcome().reviewDeadlineAt());
    DecisionEvent.TransactionDecided second =
        new DecisionEvent.TransactionDecided(
            first.transaction(),
            first.scoring(),
            first.outcome(),
            redecided,
            first.thresholdsVersion(),
            first.requestFingerprint(),
            3);
    sink.accept(records(List.of(first, second)));
    assertThat(sink.duplicateDecisions()).isEqualTo(1);
    assertThat(one("SELECT count(*) FROM fraudshield.decision_states")).isEqualTo("1");
    assertThat(one("SELECT event_id FROM fraudshield.decision_states"))
        .isEqualTo(first.state().eventId().toString());
  }

  @Test
  void refusedRecordsAreDeadLetteredAndTheOthersStillWritten() throws Exception {
    DecisionEvent.CircuitBreakerChanged unknownVersion =
        new DecisionEvent.CircuitBreakerChanged(
            INSTITUTION,
            "6051",
            MccCircuitBreaker.Change.OPENED,
            new MccCircuitBreaker.WindowCounts(100, 6),
            99,
            NOW);
    DecisionEvent.CircuitBreakerChanged fine =
        new DecisionEvent.CircuitBreakerChanged(
            INSTITUTION,
            "5411",
            MccCircuitBreaker.Change.OPENED,
            new MccCircuitBreaker.WindowCounts(100, 6),
            1,
            NOW);
    sink.accept(records(List.of(fine, unknownVersion)));
    assertThat(sink.deadLettered()).isEqualTo(1);
    assertThat(one("SELECT merchant_category_code FROM fraudshield.mcc_circuit_breaker_events"))
        .isEqualTo("5411");
    try (Stream<Path> files = Files.list(deadLetters)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder(
              String.format("%020d.json", records(List.of(fine)).get(0).nextOffset()),
              String.format("%020d.error", records(List.of(fine)).get(0).nextOffset()));
    }
  }

  @Test
  void unreachableDatabasesFailTheBatchForRetry() {
    PGSimpleDataSource nowhere = new PGSimpleDataSource();
    nowhere.setUrl("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1");
    assertThatThrownBy(
            () ->
                new PostgresSink(nowhere, deadLetters)
                    .accept(records(FactScenarios.everyKindOfFact())))
        .isInstanceOf(SpoolDrainer.SinkException.class);
  }

  @Test
  @Tag("FR-05-07")
  @Tag("FR-05-05")
  void configurationIsReadByVersionWithDefaultsWhenMissing() throws Exception {
    UUID analyst = UUID.randomUUID();
    UUID rule = UUID.randomUUID();
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.users (id, institution_id, first_name,"
              + " last_name, email, role, employee_id, department, password_hash)"
              + " VALUES (?, ?, 'Test', 'Officer', 'officer@example.test', 'RISK_OFFICER',"
              + " 'EMP0001',"
              + " 'RISK', '$2b$12$notARealHashJustTheShapeOfOne.............')",
          analyst,
          INSTITUTION);
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.alert_rules (id, institution_id, rule_name,"
              + " created_by) VALUES (?, ?, 'Night cash-out burst', ?)",
          rule,
          INSTITUTION,
          analyst);
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.alert_rule_versions (rule_id, institution_id,"
              + " version, description, rule_expression, risk_tier_override, created_by)"
              + " VALUES (?, ?, 1, 'Three or more cash-outs in an hour', ?::jsonb, 'HIGH', ?)",
          rule,
          INSTITUTION,
          "{\"field\":\"agent_cashout_count_1h\",\"op\":\"gte\",\"value\":3}",
          analyst);
    }
    MutableClock clock = new MutableClock(Instant.now());
    JpaConfiguration configuration = jpa.configuration(clock);
    assertThat(configuration.thresholds(INSTITUTION).version()).isEqualTo(1);
    assertThat(
            configuration.thresholds(INSTITUTION).thresholds().byChannel().get(Channel.USSD).high())
        .isEqualByComparingTo("0.85");
    assertThat(configuration.rules(INSTITUTION).rules())
        .singleElement()
        .satisfies(r -> assertThat(r.ruleId()).isEqualTo(rule));
    assertThat(configuration.breakerSettings(INSTITUTION).version()).isEqualTo(1);

    UUID unconfigured = UUID.randomUUID();
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.institutions (id, code, name, country)"
              + " VALUES (?, 'bare-bank', 'Bare bank', 'RW')",
          unconfigured);
    }
    assertThat(configuration.thresholds(unconfigured).version()).isZero();
    assertThat(configuration.thresholds(unconfigured).thresholds().byChannel().get(Channel.CARD))
        .isEqualTo(JpaConfiguration.SRS_THRESHOLD);
    assertThat(configuration.breakerSettings(unconfigured).settings())
        .isEqualTo(JpaConfiguration.SRS_BREAKER);
    assertThat(configuration.defaultsUsed()).isPositive();

    // A new version becomes effective on refresh; a broken stored rule is skipped, not fatal.
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.config_changes (id, institution_id, kind,"
              + " direction, status, proposed_by, reason, base_version, previous, proposed,"
              + " confirm_by,"
              + " effective_at, proposed_at) VALUES (?, ?, 'CHANNEL_THRESHOLDS', 'TIGHTENING',"
              + " 'APPLIED_PENDING_CONFIRMATION', ?, 'Tighten USSD for a campaign', 1, '{}', '{}',"
              + " now() + interval '24 hours', now(), now())",
          UUID.randomUUID(),
          INSTITUTION,
          analyst);
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.risk_threshold_versions (institution_id,"
              + " version, config_change_id, effective_at) SELECT institution_id, 2, id, now()"
              + " FROM fraudshield.config_changes",
          new Object[0]);
      for (Channel channel : Channel.values()) {
        TestDatabase.exec(
            c,
            "INSERT INTO fraudshield.risk_thresholds (institution_id, version,"
                + " channel, medium_threshold, high_threshold, medium_timeout_policy)"
                + " VALUES (?, 2, ?, 0.50, ?, 'DECLINE_AND_VERIFY')",
            INSTITUTION,
            channel.name(),
            channel == Channel.USSD ? new BigDecimal("0.70") : new BigDecimal("0.85"));
      }
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.alert_rule_versions (rule_id,"
              + " institution_id, version, description, rule_expression, risk_tier_override,"
              + " created_by) VALUES (?, ?, 2, 'A version that no longer compiles', ?::jsonb,"
              + " 'HIGH', ?)",
          rule,
          INSTITUTION,
          "{\"field\":\"gone\",\"op\":\"eq\",\"value\":1}",
          analyst);
      TestDatabase.exec(c, "UPDATE fraudshield.alert_rules SET current_version = 2", new Object[0]);
    }
    // Past the effective_at of the rows just written, which the database stamped with its own
    // clock while these inserts ran.
    clock.advance(Duration.ofMinutes(1));
    assertThat(configuration.refreshAll()).isEqualTo(2);
    assertThat(configuration.thresholds(INSTITUTION).version()).isEqualTo(2);
    assertThat(
            configuration
                .thresholds(INSTITUTION)
                .thresholds()
                .byChannel()
                .get(Channel.USSD)
                .timeoutPolicy())
        .isEqualTo(MediumTimeoutPolicy.DECLINE_AND_VERIFY);
    assertThat(configuration.rules(INSTITUTION).rules()).isEmpty();
    assertThat(configuration.invalidRules()).isEqualTo(1);
  }

  /**
   * ADR 0068: no listing may grow into N + 1 queries. A configuration load is three statements —
   * thresholds, rules and breaker settings — whatever the number of channels or rules, and it
   * navigates no association lazily.
   */
  @Test
  @Tag("FR-05-05")
  void oneConfigurationLoadIsThreeStatementsHoweverManyRules() throws Exception {
    UUID analyst = analyst();
    for (int i = 0; i < 5; i++) {
      UUID rule = UUID.randomUUID();
      try (Connection c = db.superuser()) {
        TestDatabase.exec(
            c,
            "INSERT INTO fraudshield.alert_rules (id, institution_id, rule_name, created_by)"
                + " VALUES (?, ?, ?, ?)",
            rule,
            INSTITUTION,
            "Counted rule " + i,
            analyst);
        TestDatabase.exec(
            c,
            "INSERT INTO fraudshield.alert_rule_versions (rule_id, institution_id, version,"
                + " description, rule_expression, risk_tier_override, created_by)"
                + " VALUES (?, ?, 1, 'A rule that counts statements', ?::jsonb, 'HIGH', ?)",
            rule,
            INSTITUTION,
            "{\"field\":\"agent_cashout_count_1h\",\"op\":\"gte\",\"value\":3}",
            analyst);
      }
    }
    JpaConfiguration configuration = jpa.configuration(new MutableClock(Instant.now()));
    org.hibernate.stat.Statistics statistics = jpa.statistics();
    statistics.clear();
    assertThat(configuration.rules(INSTITUTION).rules()).hasSize(5);
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    assertThat(statistics.getEntityFetchCount()).as("no lazy entity fetch").isZero();
    assertThat(statistics.getCollectionFetchCount()).as("no lazy collection fetch").isZero();
  }

  @Test
  @Tag("D-14")
  void theLatestPersistedStateIsReadBack() throws Exception {
    List<DecisionEvent> facts = FactScenarios.everyKindOfFact();
    sink.accept(records(facts));
    DecisionState released =
        facts.stream()
            .filter(DecisionEvent.DecisionChanged.class::isInstance)
            .map(e -> ((DecisionEvent.DecisionChanged) e).state())
            .filter(s -> s.decidedBy() == DecidedBy.TIMEOUT_POLICY)
            .findFirst()
            .orElseThrow();
    JdbcDecisionStates states = new JdbcDecisionStates(db.dataSource("fs_app"));
    assertThat(states.latest(INSTITUTION, released.transactionId())).contains(released);
    assertThat(states.latest(INSTITUTION, UUID.randomUUID())).isEmpty();
    assertThat(states.latest(UUID.randomUUID(), released.transactionId()))
        .as("other tenant")
        .isEmpty();
    assertThat(released.decision()).isEqualTo(DecisionValue.TIMEOUT_RELEASE);
  }

  @Test
  @Tag("FR-02-09")
  void firstSeenIsDurableAndOnlyMovesEarlier() throws Exception {
    List<DecisionEvent> facts = FactScenarios.everyKindOfFact();
    sink.accept(records(facts));
    assertThat(
            one(
                "SELECT first_seen_at = '"
                    + NOW
                    + "'::timestamptz FROM fraudshield.account_profiles WHERE account_token ="
                    + " 'tok_AccountAaaaBbbbCcccDddd01'"))
        .isEqualTo("t");
    assertThat(
            one(
                "SELECT count(*) FROM fraudshield.account_profiles WHERE account_token ="
                    + " 'tok_NeverSeenAccountCcccDddd01'"))
        .isEqualTo("0");
    try (Connection c = db.dataSource("fs_app").getConnection()) {
      c.setAutoCommit(false);
      Tenant.use(c, INSTITUTION);
      assertThatThrownBy(
              () ->
                  TestDatabase.exec(
                      c,
                      "UPDATE account_profiles SET first_seen_at"
                          + " = first_seen_at + interval '1 day'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("only move earlier");
      c.rollback();
      Tenant.use(c, INSTITUTION);
      TestDatabase.exec(
          c,
          "UPDATE account_profiles SET opened_at = first_seen_at - interval"
              + " '400 days' WHERE account_token = 'tok_AccountAaaaBbbbCcccDddd01'");
      assertThatThrownBy(
              () ->
                  TestDatabase.exec(
                      c,
                      "UPDATE account_profiles SET opened_at =" + " opened_at - interval '1 day'"))
          .isInstanceOf(SQLException.class);
      c.rollback();
      Tenant.use(c, INSTITUTION);
      assertThatThrownBy(() -> TestDatabase.exec(c, "DELETE FROM account_profiles"))
          .isInstanceOf(SQLException.class);
      c.rollback();
    }
  }

  @Test
  @Tag("D-43")
  void fxRatesAreReadAsOfTheTransactionDate() throws Exception {
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.fx_rates (currency, rate_date, rwf_per_unit,"
              + " source) VALUES ('KES', '2026-09-01', 10.5, 'synthetic test rate'), ('KES',"
              + " '2026-09-20', 11, 'synthetic test rate')",
          new Object[0]);
    }
    JdbcFxRates rates = new JdbcFxRates(db.dataSource("fs_app"), new MutableClock(NOW));
    assertThat(rates.rwfPerUnit(CurrencyCode.KES, LocalDate.parse("2026-09-10")))
        .contains(new BigDecimal("10.50000000"));
    assertThat(rates.rwfPerUnit(CurrencyCode.KES, LocalDate.parse("2026-09-22")))
        .contains(new BigDecimal("11.00000000"));
    assertThat(rates.rwfPerUnit(CurrencyCode.KES, LocalDate.parse("2026-08-01"))).isEmpty();
    assertThat(rates.rwfPerUnit(CurrencyCode.UGX, LocalDate.parse("2026-09-22"))).isEmpty();
    assertThat(rates.rwfPerUnit(CurrencyCode.RWF, LocalDate.parse("2026-09-22")))
        .contains(BigDecimal.ONE);
    try (Connection c = db.superuser()) {
      assertThatThrownBy(
              () -> TestDatabase.exec(c, "UPDATE fraudshield.fx_rates SET" + " rwf_per_unit = 1"))
          .isInstanceOf(SQLException.class);
    }
    assertThat(RiskTier.LOW).isNotNull();
  }
}
