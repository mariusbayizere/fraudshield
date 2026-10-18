package io.github.mariusbayizere.fraudshield.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Schema guarantees that the security tests do not reach: TimescaleDB policies, continuous
 * aggregates, token-only customer identifiers and the derived auto-block state (M1 milestone review
 * MAJOR-2; ADR 0017, ADR 0018).
 */
@Tag("requires-docker")
class SchemaPoliciesTest {

  private static final UUID BANK_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID BANK_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String CHECK_VIOLATION = "23514";
  private static final String ACCOUNT = "tok_policyAccount000000000001";

  private static TestDatabase db;

  @BeforeAll
  static void createDatabase() throws SQLException {
    db = TestDatabase.create();
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement()) {
      statement.execute(
          "INSERT INTO fraudshield.institutions (id, code, name, country) VALUES ('"
              + BANK_A
              + "', 'bank-a', 'Synthetic Bank A', 'RW'), ('"
              + BANK_B
              + "', 'bank-b', 'Synthetic Bank B', 'KE')");
    }
  }

  // --- D-32, ADR 0018: compression and retention policies ---------------------------------------

  @Test
  @Tag("D-32")
  @Tag("D-49")
  void compressionAndRetentionPoliciesMatchTheRetentionRules() throws SQLException {
    Map<String, String> policies = new TreeMap<>();
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                """
                SELECT hypertable_name || ':' || proc_name,
                       coalesce(config->>'drop_after', config->>'compress_after')
                FROM timescaledb_information.jobs
                WHERE hypertable_schema = 'fraudshield'
                  AND proc_name IN ('policy_retention', 'policy_compression')
                """)) {
      while (rows.next()) {
        policies.put(rows.getString(1), rows.getString(2));
      }
    }
    // Regulatory audit evidence is kept 7 years (D-32); nothing else is dropped except shadow
    // scores.
    assertThat(policies)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "audit_events:policy_retention", "7 years",
                "audit_events:policy_compression", "30 days",
                "shadow_scores:policy_retention", "180 days",
                "shadow_scores:policy_compression", "7 days",
                "transactions:policy_compression", "30 days",
                "fraud_scores:policy_compression", "30 days"));
  }

  // --- C.4, FR-03-07: continuous aggregates
  // -------------------------------------------------------

  @Test
  @Tag("D-49")
  @Tag("FR-03-07")
  void continuousAggregatesRefreshAndAreReadOnlyThroughTenantViews() throws SQLException {
    Map<String, String> refresh = new TreeMap<>();
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                """
                SELECT a.view_name, j.schedule_interval::text
                FROM timescaledb_information.continuous_aggregates a
                JOIN timescaledb_information.jobs j
                  ON j.hypertable_schema = a.view_schema
                 AND j.hypertable_name = a.view_name
                 AND j.proc_name = 'policy_refresh_continuous_aggregate'
                WHERE a.view_schema = 'fraudshield'
                """)) {
      while (rows.next()) {
        refresh.put(rows.getString(1), rows.getString(2));
      }
    }
    assertThat(refresh)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("account_activity_hourly", "00:15:00", "merchant_activity_15m", "00:05:00"));

    insertTransaction(BANK_A, "tok_aggregateAccountA0000000001");
    insertTransaction(BANK_B, "tok_aggregateAccountB0000000001");
    try (Connection admin = db.superuser()) {
      for (String role : List.of("fs_app", "fs_app_readonly")) {
        for (String aggregate : List.of("account_activity_hourly", "merchant_activity_15m")) {
          assertThat(privilege(admin, role, aggregate)).as("%s on %s", role, aggregate).isFalse();
          assertThat(privilege(admin, role, "v_" + aggregate)).as(role).isTrue();
        }
      }
    }
    try (Connection app = tenant("fs_app", BANK_A)) {
      // Real-time aggregates (materialized_only = false) include rows not yet materialised.
      assertThat(
              strings(
                  app,
                  "SELECT account_token FROM v_account_activity_hourly"
                      + " WHERE account_token LIKE 'tok_aggregate%'"))
          .containsExactly("tok_aggregateAccountA0000000001");
      assertThat(strings(app, "SELECT DISTINCT institution_id::text FROM v_merchant_activity_15m"))
          .containsExactly(BANK_A.toString());
    }
  }

  // --- NFR-SEC-03: customer identifiers are tokens, never raw PII
  // ----------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"account_token", "counterparty_token", "device_token", "agent_token"})
  @Tag("NFR-SEC-03")
  @Tag("FR-01-02")
  void rawPhoneAndAccountNumbersAreRejectedInTokenColumns(String column) throws SQLException {
    for (String raw :
        List.of("+250788123456", "0788123456", "4000123412341234", "tok_short", "TOK_x")) {
      try (Connection app = tenant("fs_app", BANK_A)) {
        assertThatThrownBy(() -> insertTransaction(app, column, raw))
            .as("%s = %s", column, raw)
            .isInstanceOf(SQLException.class)
            .extracting(e -> ((SQLException) e).getSQLState())
            .isEqualTo(CHECK_VIOLATION);
        app.rollback();
      }
    }
    try (Connection app = tenant("fs_app", BANK_A)) {
      insertTransaction(app, column, "tok_validToken00000000000000001");
      app.rollback();
    }
  }

  // --- D-30: auto-block state is derived from append-only facts
  // ------------------------------------

  @Test
  @Tag("D-30")
  void autoBlockStateIsDerivedFromAppendOnlyFacts() throws SQLException {
    try (Connection app = tenant("fs_app", BANK_A)) {
      UUID block =
          uuid(
              app,
              "INSERT INTO auto_block_events (institution_id, transaction_id, fraud_score_id,"
                  + " account_token, blocked_at, block_reason) VALUES ('"
                  + BANK_A
                  + "', gen_random_uuid(), gen_random_uuid(), '"
                  + ACCOUNT
                  + "', now(), 'HIGH_RISK') RETURNING id");
      assertThat(status(app, block))
          .isEqualTo("sms=false verified=null unblocked=false frozen=null");

      exec(
          app,
          "INSERT INTO customer_notifications (institution_id, notification_id,"
              + " auto_block_event_id, account_token, channel, template_key, locale,"
              + " verification_link_allowed, event) VALUES ('"
              + BANK_A
              + "', gen_random_uuid(), '"
              + block
              + "', '"
              + ACCOUNT
              + "', 'SMS', 'sms.auto_block', 'rw', true, 'SENT')");
      assertThat(status(app, block))
          .isEqualTo("sms=true verified=null unblocked=false frozen=null");

      UUID verification =
          uuid(
              app,
              "INSERT INTO customer_verifications (institution_id, auto_block_event_id,"
                  + " verification_token_hash, verification_channel, expires_at) VALUES ('"
                  + BANK_A
                  + "', '"
                  + block
                  + "', sha256(gen_random_uuid()::text::bytea), 'SMS_LINK',"
                  + " now() + interval '1 hour') RETURNING id");
      exec(
          app,
          "INSERT INTO customer_verification_responses (verification_id, institution_id, answer,"
              + " self_service_allowed) VALUES ('"
              + verification
              + "', '"
              + BANK_A
              + "', 'WAS_ME', true)");
      assertThat(status(app, block))
          .isEqualTo("sms=true verified=true unblocked=false frozen=null");

      exec(
          app,
          "INSERT INTO unblock_events (institution_id, auto_block_event_id, cause,"
              + " verification_id) VALUES ('"
              + BANK_A
              + "', '"
              + block
              + "', 'CUSTOMER_VERIFICATION', '"
              + verification
              + "')");
      assertThat(status(app, block)).isEqualTo("sms=true verified=true unblocked=true frozen=null");

      exec(
          app,
          "INSERT INTO account_freeze_events (institution_id, account_token, event, cause,"
              + " auto_block_event_id) VALUES ('"
              + BANK_A
              + "', '"
              + ACCOUNT
              + "', 'FROZEN', 'repeated high-risk activity', '"
              + block
              + "')");
      assertThat(status(app, block)).isEqualTo("sms=true verified=true unblocked=true frozen=true");
      app.commit();

      try (Connection other = tenant("fs_app", BANK_B)) {
        assertThat(
                strings(
                    other,
                    "SELECT auto_block_event_id::text FROM v_auto_block_status WHERE"
                        + " auto_block_event_id = '"
                        + block
                        + "'"))
            .isEmpty();
      }
    }
  }

  private static String status(Connection connection, UUID block) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT customer_sms_sent, customer_verified, unblocked_at IS NOT NULL,"
                    + " account_frozen FROM v_auto_block_status WHERE auto_block_event_id = '"
                    + block
                    + "'")) {
      assertThat(row.next()).as("status row for %s", block).isTrue();
      return "sms="
          + row.getObject(1)
          + " verified="
          + row.getObject(2)
          + " unblocked="
          + row.getObject(3)
          + " frozen="
          + row.getObject(4);
    }
  }

  private static void insertTransaction(UUID institution, String account) throws SQLException {
    try (Connection app = tenant("fs_app", institution)) {
      insertTransaction(app, "account_token", account);
      app.commit();
    }
  }

  /** Inserts a valid transaction with {@code value} in {@code column}. */
  private static void insertTransaction(Connection connection, String column, String value)
      throws SQLException {
    Map<String, String> tokens = new TreeMap<>();
    tokens.put("account_token", "tok_policyAccount000000000001");
    tokens.put("counterparty_token", "tok_policyCounterparty0000001");
    tokens.put("device_token", "tok_policyDevice0000000000001");
    tokens.put("agent_token", "tok_policyAgent00000000000001");
    tokens.put(column, value);
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO transactions (institution_id, transaction_id, account_token,
              counterparty_token, amount, currency, amount_rwf, channel, merchant_category_code,
              latitude, longitude, device_token, agent_token, transaction_timestamp, received_at)
            SELECT current_institution(), gen_random_uuid(), ?, ?, 1000, 'RWF', 1000,
              'AGENT_BANKING', '5411', -1.9441, 30.0619, ?, ?, now(), now()
            """)) {
      insert.setString(1, tokens.get("account_token"));
      insert.setString(2, tokens.get("counterparty_token"));
      insert.setString(3, tokens.get("device_token"));
      insert.setString(4, tokens.get("agent_token"));
      insert.executeUpdate();
    }
  }

  private static boolean privilege(Connection admin, String role, String relation)
      throws SQLException {
    try (Statement statement = admin.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT has_table_privilege('"
                    + role
                    + "', 'fraudshield."
                    + relation
                    + "', 'SELECT')")) {
      row.next();
      return row.getBoolean(1);
    }
  }

  private static Connection tenant(String role, UUID institution) throws SQLException {
    Connection connection = db.as(role);
    connection.setAutoCommit(false);
    exec(connection, "SET LOCAL fraudshield.institution_id = '" + institution + "'");
    return connection;
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static UUID uuid(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(sql)) {
      row.next();
      return row.getObject(1, UUID.class);
    }
  }

  private static List<String> strings(Connection connection, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }
}
