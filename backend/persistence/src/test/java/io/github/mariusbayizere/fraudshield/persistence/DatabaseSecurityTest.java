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
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M1 gate and database integrity tests (ADR 0017): append-only tables, tenant isolation, role
 * privileges, the audit hash chain and key constraints, against the real migrations.
 */
@Tag("requires-docker")
@Tag("D-30")
@Tag("D-31")
@Tag("D-32")
class DatabaseSecurityTest {

  private static final UUID BANK_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID BANK_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String PERMISSION_DENIED = "42501";

  private static TestDatabase db;
  private static UUID analystA;
  private static UUID decisionA;

  @BeforeAll
  static void createDatabase() throws SQLException {
    db = TestDatabase.create();
    try (Connection admin = db.superuser()) {
      exec(
          admin,
          "INSERT INTO fraudshield.institutions (id, code, name, country, time_zone) VALUES ('"
              + BANK_A
              + "', 'bank-a', 'Synthetic Bank A', 'RW', 'Africa/Kigali'), ('"
              + BANK_B
              + "', 'bank-b', 'Synthetic Bank B', 'KE', 'Africa/Nairobi')");
    }
    analystA = insertUser(BANK_A, "analyst.a@example.com", "EMPA0001", "ANALYST");
    insertUser(BANK_B, "analyst.b@example.com", "EMPB0001", "ANALYST");
    decisionA = insertDecision(BANK_A, analystA);
    insertDecision(
        BANK_B, insertUser(BANK_B, "senior.b@example.com", "EMPB0002", "SENIOR_ANALYST"));
    try (Connection app = tenant("fs_app", BANK_A)) {
      exec(
          app,
          "INSERT INTO auto_block_events (institution_id, transaction_id, fraud_score_id, "
              + "account_token,"
              + " blocked_at, block_reason) VALUES ('"
              + BANK_A
              + "', gen_random_uuid(), gen_random_uuid(),"
              + " 'tok_exampleAccount0000000001', now(), 'HIGH_RISK')");
      insertAudit(app, BANK_A, 0, "LOGIN");
      app.commit();
    }
  }

  // --- M1 gate: UPDATE and DELETE on append-only tables are denied to fs_app
  // ------------------------

  @ParameterizedTest
  @ValueSource(strings = {"audit_events", "auto_block_events", "alert_decisions"})
  @Tag("FR-06-06")
  @Tag("NFR-SEC-05")
  void appRoleCannotUpdateOrDeleteAppendOnlyTables(String table) throws SQLException {
    try (Connection app = tenant("fs_app", BANK_A)) {
      String column = table.equals("alert_decisions") ? "analyst_comment" : "entity_id";
      if (table.equals("auto_block_events")) {
        column = "block_reason";
      }
      assertSqlState(
          app,
          "UPDATE " + table + " SET " + column + " = 'changed by attacker'",
          PERMISSION_DENIED);
      assertSqlState(app, "DELETE FROM " + table, PERMISSION_DENIED);
      assertSqlState(app, "TRUNCATE " + table, PERMISSION_DENIED);
    }
  }

  @Test
  void everyAppendOnlyTableDeniesUpdateAndDeleteToEveryApplicationRole() throws SQLException {
    List<String> appendOnly = appendOnlyTables();
    assertThat(appendOnly)
        .contains(
            "audit_events",
            "auto_block_events",
            "alert_decisions",
            "unblock_events",
            "account_freeze_events",
            "customer_notifications",
            "label_events",
            "decision_states",
            "risk_thresholds");
    try (Connection admin = db.superuser()) {
      for (String role : List.of("fs_app", "fs_app_readonly", "fs_compliance_ro")) {
        for (String table : appendOnly) {
          for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(
                    bool(
                        admin,
                        "SELECT has_table_privilege('"
                            + role
                            + "', 'fraudshield."
                            + table
                            + "', '"
                            + privilege
                            + "')"))
                .as("%s %s on %s", role, privilege, table)
                .isFalse();
          }
        }
      }
    }
  }

  @Test
  void appendOnlyTriggersStopEvenTheOwner() throws SQLException {
    try (Connection owner = tenant("fs_migrator", BANK_A)) {
      assertSqlState(
          owner,
          "UPDATE alert_decisions SET analyst_comment = 'rewritten by owner'",
          PERMISSION_DENIED);
      assertSqlState(owner, "DELETE FROM audit_events", PERMISSION_DENIED);
    }
  }

  // --- M1 gate: row-level security isolates institutions
  // ---------------------------------------------

  @Test
  void institutionsSeeOnlyTheirOwnRows() throws SQLException {
    try (Connection app = tenant("fs_app", BANK_A)) {
      assertThat(strings(app, "SELECT email FROM users")).containsExactly("analyst.a@example.com");
      assertThat(strings(app, "SELECT code FROM institutions")).containsExactly("bank-a");
      assertThat(count(app, "SELECT count(*) FROM alert_decisions")).isEqualTo(1);
      assertThat(count(app, "SELECT count(*) FROM v_audit_events")).isEqualTo(1);
      assertThat(
              count(
                  app,
                  "SELECT count(*) FROM alert_decisions WHERE institution_id = '" + BANK_B + "'"))
          .isZero();
    }
    try (Connection app = tenant("fs_app", BANK_B)) {
      assertThat(strings(app, "SELECT email FROM users"))
          .containsExactlyInAnyOrder("analyst.b@example.com", "senior.b@example.com");
      assertThat(count(app, "SELECT count(*) FROM v_audit_events")).isZero();
    }
  }

  @Test
  void withoutAnInstitutionNothingIsVisibleOrWritable() throws SQLException {
    try (Connection app = db.as("fs_app")) {
      app.setAutoCommit(false);
      assertThat(count(app, "SELECT count(*) FROM users")).isZero();
      assertThat(count(app, "SELECT count(*) FROM v_audit_events")).isZero();
      assertSqlState(
          app,
          "INSERT INTO users (institution_id, first_name, last_name, email, password_hash, role,"
              + " employee_id, department) VALUES ('"
              + BANK_A
              + "', 'Eve', 'Intruder', 'eve@example.com',"
              + " '$2b$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzabcd', 'ADMIN', "
              + "'EVE00001', 'IT')",
          PERMISSION_DENIED);
    }
  }

  @Test
  void rowsForAnotherInstitutionCannotBeWritten() throws SQLException {
    try (Connection app = tenant("fs_app", BANK_A)) {
      assertSqlState(
          app,
          "INSERT INTO users (institution_id, first_name, last_name, email, password_hash, role,"
              + " employee_id, department) VALUES ('"
              + BANK_B
              + "', 'Eve', 'Intruder', 'eve@example.com',"
              + " '$2b$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzabcd', 'ADMIN', "
              + "'EVE00002', 'IT')",
          PERMISSION_DENIED);
      assertSqlState(app, "UPDATE users SET institution_id = '" + BANK_B + "'", PERMISSION_DENIED);
    }
    try (Connection app = tenant("fs_app", BANK_A)) {
      assertThatThrownBy(() -> insertAudit(app, BANK_B, 1, "FORGED"))
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo(PERMISSION_DENIED);
    }
  }

  @Test
  void referencesCannotCrossInstitutions() throws SQLException {
    UUID alertInB;
    try (Connection admin = db.superuser()) {
      alertInB =
          uuid(
              admin,
              "SELECT alert_queue_entry_id FROM fraudshield.alert_decisions WHERE institution_id "
                  + "= '"
                  + BANK_B
                  + "'");
    }
    try (Connection app = tenant("fs_app", BANK_A)) {
      assertSqlState(
          app,
          "INSERT INTO alert_decisions (institution_id, alert_queue_entry_id, analyst_id, decision,"
              + " analyst_comment, idempotency_key) VALUES ('"
              + BANK_A
              + "', '"
              + alertInB
              + "', '"
              + analystA
              + "', 'CONFIRM_FRAUD', 'links another bank alert', gen_random_uuid())",
          "23503");
    }
  }

  @Test
  void everyForeignKeyBetweenTenantTablesIncludesTheInstitution() throws SQLException {
    // A single-column reference ignores row-level security: it could point at another
    // institution's row and its error would reveal that the row exists (review MAJOR-1).
    try (Connection admin = db.superuser()) {
      assertThat(
              strings(
                  admin,
                  """
                  SELECT c.conrelid::regclass || '.' || c.conname FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = 'fraudshield'
                  WHERE c.contype = 'f'
                    AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.conrelid
                                AND a.attname = 'institution_id')
                    AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.confrelid
                                AND a.attname = 'institution_id')
                    AND NOT EXISTS (
                      SELECT 1 FROM unnest(c.conkey, c.confkey) AS k (source, target)
                      JOIN pg_attribute sa ON sa.attrelid = c.conrelid AND sa.attnum = k.source
                      JOIN pg_attribute ta ON ta.attrelid = c.confrelid AND ta.attnum = k.target
                      WHERE sa.attname = 'institution_id' AND ta.attname = 'institution_id')
                  ORDER BY 1
                  """))
          .isEmpty();
    }
  }

  @Test
  void everyTenantTableIsIsolated() throws SQLException {
    try (Connection admin = db.superuser()) {
      List<String> unprotected =
          strings(
              admin,
              """
              SELECT c.relname FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'fraudshield'
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'institution_id' AND NOT
                a.attisdropped
              WHERE c.relkind = 'r'
                AND NOT c.relrowsecurity
                AND NOT EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND t.tgname =
                  'tenant_insert_guard')
              ORDER BY 1
              """);
      // outbox_events is read by the cross-tenant relay; ADR 0017 records the exception.
      assertThat(unprotected).containsExactly("outbox_events");
      for (String role : List.of("fs_app", "fs_app_readonly", "fs_compliance_ro")) {
        for (String hypertable :
            List.of("transactions", "fraud_scores", "shadow_scores", "audit_events")) {
          assertThat(
                  bool(
                      admin,
                      "SELECT has_table_privilege('"
                          + role
                          + "', 'fraudshield."
                          + hypertable
                          + "', 'SELECT')"))
              .as("%s must read %s only through its tenant view", role, hypertable)
              .isFalse();
        }
      }
      assertThat(
              bool(
                  admin,
                  "SELECT has_table_privilege('fs_app', 'fraudshield.outbox_events', 'SELECT')"))
          .isTrue();
      assertThat(
              bool(
                  admin,
                  "SELECT has_table_privilege('fs_app_readonly', 'fraudshield.outbox_events', "
                      + "'SELECT')"))
          .isFalse();
    }
  }

  @Test
  void theScorerMayCallTheFeatureFallbackAndReadNothingElse() throws SQLException {
    // ADR 0062 point 6: fs_scorer answers the feature store's database fallback through five
    // SECURITY DEFINER functions and holds no grant on any table or view.
    try (Connection admin = db.superuser()) {
      assertThat(
              strings(
                  admin,
                  """
                  SELECT table_name FROM information_schema.role_table_grants
                  WHERE grantee = 'fs_scorer'
                  """))
          .isEmpty();
      assertThat(
              strings(
                  admin,
                  """
                  SELECT p.proname FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'fraudshield'
                  WHERE p.prosecdef AND has_function_privilege('fs_scorer', p.oid, 'EXECUTE')
                  ORDER BY 1
                  """))
          .containsExactly(
              "feature_fallback_account",
              "feature_fallback_device_first_seen",
              "feature_fallback_kyc_tiers",
              "feature_fallback_sim_swaps",
              "feature_fallback_transactions");
      assertThat(
              strings(
                  admin,
                  """
                  SELECT p.proname FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'fraudshield'
                  WHERE p.proname LIKE 'feature_fallback_%'
                    AND (has_function_privilege('fs_app', p.oid, 'EXECUTE')
                      OR has_function_privilege('fs_app_readonly', p.oid, 'EXECUTE')
                      OR NOT coalesce(p.proconfig::text LIKE '%search_path=fraudshield, pg_temp%',
                                      false))
                  """))
          .as("only the scorer may call them, and each pins its search_path")
          .isEmpty();
    }
    try (Connection scorer = db.as("fs_scorer");
        Statement statement = scorer.createStatement()) {
      for (String relation :
          List.of("transactions", "v_transactions", "account_profiles", "decision_states")) {
        assertThatThrownBy(() -> statement.executeQuery("SELECT 1 FROM " + relation + " LIMIT 1"))
            .as(relation)
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("permission denied");
      }
      assertThat(
              strings(
                  scorer,
                  "SELECT count(*)::text FROM feature_fallback_transactions('tok_"
                      + "A".repeat(24)
                      + "', now() - interval '1 day', now())"))
          .containsExactly("0");
    }
  }

  @Test
  void everyOwnerRightsFunctionPinsItsSearchPathAndHasOnlyItsDeclaredCallers() throws SQLException {
    // A SECURITY DEFINER function runs with fs_migrator's rights, so who may call it is a grant
    // like
    // any table's (M7 merge review, finding 4). Every one is listed here with its callers; a new
    // one
    // fails this test until its migration closes it to PUBLIC and grants it to the roles named
    // here.
    try (Connection admin = db.superuser()) {
      assertThat(
              strings(
                  admin,
                  """
                  SELECT p.proname || ' -> ' || coalesce((
                      SELECT string_agg(r.name, ', ' ORDER BY r.name COLLATE "C")
                      FROM (SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC'
                                        ELSE pg_get_userbyid(a.grantee) END AS name
                            FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                            WHERE a.privilege_type = 'EXECUTE' AND a.grantee <> p.proowner) r),
                    'nobody')
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'fraudshield'
                  WHERE p.prosecdef
                  """))
          .containsExactlyInAnyOrder(
              "audit_chain_hashes -> fs_app, fs_compliance_ro",
              "audit_chain_head -> fs_app, fs_compliance_ro",
              "audit_chain_last_seq_before -> fs_compliance_ro",
              "audit_events_chain -> nobody",
              "auth_employee_id_registered -> fs_app",
              "auth_find_api_key -> fs_app",
              "auth_find_email_verification -> fs_app",
              "auth_find_refresh_token -> fs_app",
              "auth_find_user_by_email -> fs_app",
              "auth_find_user_institution -> fs_app",
              "decision_alarm_figures -> fs_app",
              "deployment_has_synthetic_data -> fs_app, fs_app_readonly, fs_compliance_ro",
              "feature_fallback_account -> fs_scorer",
              "feature_fallback_device_first_seen -> fs_scorer",
              "feature_fallback_kyc_tiers -> fs_scorer",
              "feature_fallback_sim_swaps -> fs_scorer",
              "feature_fallback_transactions -> fs_scorer",
              "institutions_with_due_commits -> fs_app",
              "institutions_with_overdue_holds -> fs_app",
              "institutions_with_pending_effects -> fs_app",
              "institutions_with_unapplied_unblocks -> fs_app",
              "institutions_with_unapplied_resolutions -> fs_app",
              "institutions_with_unfinished_batch_jobs -> fs_app",
              "institutions_with_unrecorded_requests -> fs_app",
              "release_failed_claim -> fs_operator",
              "resolution_actor -> nobody",
              "resolve_contested_decision -> fs_operator",
              "verification_find_by_token -> fs_app",
              "verify_audit_chain -> fs_compliance_ro",
              "webhook_deliveries_due -> fs_app");
      assertThat(
              strings(
                  admin,
                  """
                  SELECT p.proname FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'fraudshield'
                  WHERE p.prosecdef
                    AND NOT coalesce('search_path=fraudshield, pg_temp' = ANY (p.proconfig), false)
                  """))
          .as("each pins search_path, so a caller's schema or pg_temp cannot shadow a table")
          .isEmpty();
    }
  }

  @Test
  void functionsTheMigratorCreatesAreNotExecutableByPublic() throws SQLException {
    // V71: the global default privilege, which V11's per-schema form could not set. Checked on a
    // function created after the migrations, as the next migration would create it.
    try (Connection migrator = db.as("fs_migrator")) {
      exec(
          migrator,
          "CREATE FUNCTION fraudshield.v71_probe() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$");
      try {
        for (String role : List.of("public", "fs_app", "fs_app_readonly", "fs_scorer")) {
          assertThat(
                  bool(
                      migrator,
                      "SELECT has_function_privilege('"
                          + role
                          + "', 'fraudshield.v71_probe()', 'EXECUTE')"))
              .as(role)
              .isFalse();
        }
      } finally {
        exec(migrator, "DROP FUNCTION fraudshield.v71_probe()");
      }
    }
  }

  @Test
  void applicationRolesHaveNoElevatedAttributesAndOwnNothing() throws SQLException {
    try (Connection admin = db.superuser()) {
      assertThat(
              strings(
                  admin,
                  """
                  SELECT rolname FROM pg_roles
                  WHERE rolname IN ('fs_migrator', 'fs_app', 'fs_app_readonly', 'fs_compliance_ro',
                                    'fs_scorer')
                    AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)
                  """))
          .isEmpty();
      assertThat(
              strings(
                  admin,
                  """
                  SELECT DISTINCT pg_get_userbyid(c.relowner) FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'fraudshield'
                  """))
          .containsExactly("fs_migrator");
      assertThat(
              bool(
                  admin,
                  "SELECT has_function_privilege('fs_app', "
                      + "'fraudshield.verify_audit_chain(smallint, bigint, bytea)', 'EXECUTE')"))
          .isFalse();
      assertThat(
              bool(
                  admin,
                  "SELECT has_table_privilege('fs_app', 'fraudshield.audit_chain_heads', "
                      + "'SELECT')"))
          .isFalse();
      assertThat(
              bool(
                  admin,
                  "SELECT has_table_privilege('fs_compliance_ro', 'fraudshield.users', 'SELECT')"))
          .isFalse();
    }
  }

  @Test
  void readOnlyRolesCannotReadCredentialMaterial() throws SQLException {
    try (Connection admin = db.superuser()) {
      List<String> readable =
          strings(
              admin,
              """
              SELECT r.rolname || ':' || c.table_name || '.' || c.column_name
              FROM information_schema.columns c
              CROSS JOIN (VALUES ('fs_app_readonly'), ('fs_compliance_ro')) AS r (rolname)
              WHERE c.table_schema = 'fraudshield'
                AND (c.column_name IN ('password_hash', 'secret_hmac', 'webhook_secret_ciphertext',
                                       'token_hash', 'code_hash', 'verification_token_hash',
                                       'oauth_id')
                     OR c.table_name IN ('refresh_tokens', 'password_reset_otps',
                                         'email_verification_tokens'))
                AND has_column_privilege(r.rolname,
                      format('fraudshield.%I', c.table_name), c.column_name, 'SELECT')
              ORDER BY 1
              """);
      assertThat(readable).isEmpty();
    }
    try (Connection readonly = tenant("fs_app_readonly", BANK_A)) {
      assertSqlState(readonly, "SELECT password_hash FROM users", "42501");
      assertSqlState(readonly, "SELECT secret_hmac FROM api_keys", "42501");
      exec(readonly, "SELECT email, role FROM users");
      exec(readonly, "SELECT key_id, last_four FROM api_keys");
      readonly.rollback();
    }
  }

  // --- D-32: audit hash chain
  // ------------------------------------------------------------------------

  @Test
  @Tag("FR-06-06")
  void theChainIsAssignedByTheDatabaseAndDetectsTampering() throws SQLException {
    short partition = 7;
    try (Connection app = tenant("fs_app", BANK_A)) {
      for (int i = 0; i < 5; i++) {
        insertAudit(app, BANK_A, partition, "EVENT_" + (char) ('A' + i));
      }
      try (PreparedStatement forged =
          app.prepareStatement(
              """
              INSERT INTO audit_events (institution_id, writer_partition, event_type, action,
                entity_type, entity_id,
                event_at, seq, prev_hash, row_hash)
              VALUES (?, ?, 'AUTH', 'FORGED', 'user', 'x', now(), 999, '\\x01', '\\x02')
              """)) {
        forged.setObject(1, BANK_A);
        forged.setShort(2, partition);
        forged.executeUpdate();
      }
      app.commit();
      // SET LOCAL ended with the commit: without a tenant the view fails closed.
      assertThat(longs(app, "SELECT seq FROM v_audit_events WHERE writer_partition = 7")).isEmpty();
      exec(app, "SET LOCAL fraudshield.institution_id = '" + BANK_A + "'");
      assertThat(
              longs(app, "SELECT seq FROM v_audit_events WHERE writer_partition = 7 ORDER BY seq"))
          .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }
    assertThat(verify(partition)).isEqualTo("6|null|null");

    try (Connection admin = db.superuser()) {
      exec(admin, "SET session_replication_role = replica");
      exec(
          admin,
          "UPDATE fraudshield.audit_events SET action = 'TAMPERED' WHERE writer_partition = 7 "
              + "AND seq = 3");
    }
    assertThat(verify(partition)).isEqualTo("2|3|row content does not match row_hash");

    try (Connection admin = db.superuser()) {
      exec(admin, "SET session_replication_role = replica");
      exec(
          admin,
          "UPDATE fraudshield.audit_events SET action = 'EVENT_C' WHERE writer_partition = 7 AND "
              + "seq = 3");
      exec(admin, "DELETE FROM fraudshield.audit_events WHERE writer_partition = 7 AND seq = 6");
    }
    assertThat(verify(partition)).isEqualTo("5|6|missing row: chain head is ahead of stored rows");
  }

  @Test
  void theChainSurvivesCompression() throws SQLException {
    short partition = 9;
    try (Connection app = tenant("fs_app", BANK_A)) {
      for (int days = 200; days >= 0; days -= 40) {
        insertAuditAt(app, partition, days);
      }
      app.commit();
    }
    try (Connection owner = db.as("fs_migrator")) {
      // Pause the policy so it cannot compress the same chunk concurrently, then compress every
      // chunk: rows are partitioned by the transaction time, so all of them are recent.
      exec(
          owner,
          """
          SELECT alter_job(job_id, scheduled => false) FROM timescaledb_information.jobs
          WHERE proc_name = 'policy_compression' AND hypertable_name = 'audit_events'
          """);
      assertThat(
              count(
                  owner,
                  "SELECT count(compress_chunk(c, if_not_compressed => true))"
                      + " FROM show_chunks('fraudshield.audit_events') c"))
          .isPositive();
    }
    try (Connection app = tenant("fs_app", BANK_A)) {
      insertAuditAt(app, partition, 0);
      app.commit();
    }
    try (Connection owner = db.as("fs_migrator")) {
      assertThat(
              count(
                  owner,
                  """
                  SELECT count(*) FROM timescaledb_information.chunks
                  WHERE hypertable_name = 'audit_events' AND is_compressed
                  """))
          .isPositive();
    }
    assertThat(verify(partition)).isEqualTo("7|null|null");
  }

  @Test
  @Tag("FR-06-06")
  void retentionFollowsTheChainOrderNotTheWritersEventTime() throws SQLException {
    // Review MAJOR-2: a backdated event_at must not decide which rows retention drops.
    try (Connection admin = db.superuser()) {
      assertThat(
              strings(
                  admin,
                  """
                  SELECT column_name FROM timescaledb_information.dimensions
                  WHERE hypertable_schema = 'fraudshield' AND hypertable_name = 'audit_events'
                  """))
          .containsExactly("recorded_at");
    }
    short partition = 11;
    try (Connection app = tenant("fs_app", BANK_A)) {
      insertAuditAt(app, partition, 3650);
      assertThat(
              bool(
                  app,
                  "SELECT recorded_at = now() AND event_at < now() - interval '9 years'"
                      + " FROM v_audit_events WHERE writer_partition = 11"))
          .isTrue();
      assertSqlState(
          app,
          "INSERT INTO audit_events (institution_id, writer_partition, event_type, action,"
              + " entity_type, entity_id, event_at, recorded_at) VALUES ('"
              + BANK_A
              + "', 11, 'AUTH', 'LOGIN', 'user', 'u1', now(), now() - interval '1 year')",
          "42501");
      app.commit();
    }
    // A transaction that started before the chain's last row must retry, so recorded_at never
    // decreases along a chain and retention can only remove a prefix.
    try (Connection earlier = tenant("fs_app", BANK_A)) {
      exec(earlier, "SELECT now()");
      try (Connection later = tenant("fs_app", BANK_A)) {
        exec(later, "SELECT pg_sleep(0.01)");
        insertAuditAt(later, partition, 0);
        later.commit();
      }
      assertSqlState(
          earlier,
          "INSERT INTO audit_events (institution_id, writer_partition, event_type, action,"
              + " entity_type, entity_id, event_at) VALUES ('"
              + BANK_A
              + "', 11, 'AUTH', 'LOGIN', 'user', 'u1', now())",
          "40001");
      earlier.rollback();
    }
    assertThat(verify(partition)).isEqualTo("2|null|null");
  }

  // --- integrity constraints
  // -------------------------------------------------------------------------

  @Test
  void atMostOneProductionAndOneShadowModel() throws SQLException {
    try (Connection app = db.as("fs_app")) {
      exec(app, modelInsert("fs-model-1", "true", "false"));
      assertSqlState(app, modelInsert("fs-model-2", "true", "false"), "23505");
      exec(app, modelInsert("fs-model-3", "false", "true"));
      assertSqlState(app, modelInsert("fs-model-4", "false", "true"), "23505");
    }
  }

  @Test
  void decisionIsCommittedOrUndoneButNeverBothAndOnlyOnItsWindowsSide()
      throws SQLException, InterruptedException {
    // V81 (decision-owner contract §9): an analyst decision commits only after its 5-second undo
    // window, and is undone only inside it, on DB time; V4: never both.
    UUID early = insertDecision(BANK_A, analystA);
    UUID late = insertDecision(BANK_A, analystA);
    try (Connection app = tenant("fs_app", BANK_A)) {
      assertSqlState(app, commit(early), "23514");
      exec(app, undo(early));
      assertSqlState(app, commit(early), "23514");
    }
    Thread.sleep(5_100);
    try (Connection app = tenant("fs_app", BANK_A)) {
      exec(app, commit(late));
      assertSqlState(app, undo(late), "23514");
    }
  }

  @Test
  @Tag("FR-04-11")
  @Tag("UX-DASH-01")
  void institutionNamesOneZoneTheDatabaseKnowsOnInsertAndOnUpdate() throws SQLException {
    // V83 (ADR 0117 point 1): no default, no fill from the country, and an unknown zone refused.
    try (Connection admin = db.superuser()) {
      admin.setAutoCommit(false);
      assertSqlState(
          admin,
          "INSERT INTO fraudshield.institutions (code, name, country) VALUES"
              + " ('no-zone', 'Synthetic No Zone', 'RW')",
          "23502");
      assertSqlState(
          admin,
          "INSERT INTO fraudshield.institutions (code, name, country, time_zone) VALUES"
              + " ('mars-bank', 'Synthetic Mars Bank', 'RW', 'Mars/Olympus_Mons')",
          "23514");
      assertSqlState(
          admin,
          "UPDATE fraudshield.institutions SET time_zone = 'Africa/Atlantis' WHERE id = '"
              + BANK_A
              + "'",
          "23514");
      exec(
          admin,
          "INSERT INTO fraudshield.institutions (code, name, country, time_zone) VALUES"
              + " ('lubumbashi-bank', 'Synthetic Lubumbashi Bank', 'CD', 'Africa/Lubumbashi')");
      admin.rollback();
    }
  }

  @Test
  @Tag("FR-04-11")
  void timeToDecisionIsComputedOnDbTimeAndNeverNegative() throws SQLException {
    // V83 (ADR 0117 point 3): an entry raised on an instance clock ahead of the database must not
    // fail the decision on V4's CHECK; its time to decision is stored as 0. A value the inserting
    // role supplies is replaced.
    try (Connection app = tenant("fs_app", BANK_A)) {
      final UUID ahead = reviewOf(app, entry(app, "now() + interval '1 hour'"));
      final UUID earlier = reviewOf(app, entry(app, "now() - interval '90 seconds'"));
      List<Long> durations =
          longs(
              app,
              "SELECT d.decision_duration_seconds FROM alert_decisions d WHERE d.id IN ('"
                  + ahead
                  + "', '"
                  + earlier
                  + "') ORDER BY d.decision_duration_seconds");
      assertThat(durations).hasSize(2);
      assertThat(durations.get(0)).isZero();
      assertThat(durations.get(1)).isBetween(90L, 120L);
      app.rollback();
    }
  }

  private static UUID entry(Connection app, String createdAt) throws SQLException {
    return uuid(
        app,
        "INSERT INTO alert_queue_entries (institution_id, transaction_id, fraud_score_id, tier,"
            + " fraud_probability, review_deadline_at, created_at) VALUES ('"
            + BANK_A
            + "', gen_random_uuid(), gen_random_uuid(), 'MEDIUM', 0.7,"
            + " now() + interval '30 seconds', "
            + createdAt
            + ") RETURNING id");
  }

  private static UUID reviewOf(Connection app, UUID entry) throws SQLException {
    return uuid(
        app,
        "INSERT INTO alert_decisions (institution_id, alert_queue_entry_id, analyst_id, decision,"
            + " analyst_comment, idempotency_key, decision_duration_seconds) VALUES ('"
            + BANK_A
            + "', '"
            + entry
            + "', '"
            + analystA
            + "', 'MARK_LEGITIMATE', 'Customer travels every month', gen_random_uuid(), 7)"
            + " RETURNING id");
  }

  private static String commit(UUID decision) {
    return "INSERT INTO alert_decision_commits (decision_id, institution_id) VALUES ('"
        + decision
        + "', '"
        + BANK_A
        + "')";
  }

  private static String undo(UUID decision) {
    return "INSERT INTO alert_decision_undos (decision_id, institution_id, undone_by) VALUES ('"
        + decision
        + "', '"
        + BANK_A
        + "', '"
        + analystA
        + "')";
  }

  @Test
  void operatorExitsAuthoriseRecordAndAuditForAnActiveRiskOfficerOnly() throws SQLException {
    // Decision-owner contract §7.2: the SQL functions authorise and record; they evaluate no rule.
    UUID officer = insertUser(BANK_A, "officer.ops@example.com", "EMPA0201", "RISK_OFFICER");
    UUID tx = UUID.randomUUID();
    String login = "op_" + tx.toString().substring(0, 8);
    try (Connection admin = db.superuser()) {
      exec(admin, "BEGIN");
      exec(admin, "SET LOCAL fraudshield.institution_id = '" + BANK_A + "'");
      exec(
          admin,
          "INSERT INTO fraudshield.decision_owners (institution_id, transaction_id,"
              + " first_event_id, first_decision, sequence, decision, decided_by,"
              + " contested_since) VALUES ('"
              + BANK_A
              + "', '"
              + tx
              + "', gen_random_uuid(), 'APPROVE', 1, 'APPROVE', 'MODEL', now())");
      exec(admin, "COMMIT");
      exec(admin, "CREATE ROLE " + login + " LOGIN PASSWORD 'op-test' IN ROLE fs_operator");
      exec(
          admin,
          "GRANT CONNECT ON DATABASE "
              + db.url().substring(db.url().lastIndexOf('/') + 1)
              + " TO "
              + login);
    }
    String reason = "Two first decisions; the bank was told DECLINE";
    try (Connection op = java.sql.DriverManager.getConnection(db.url(), login, "op-test")) {
      String resolution =
          strings(
                  op,
                  "SELECT fraudshield.resolve_contested_decision('"
                      + tx
                      + "', 'DECLINE', '"
                      + officer
                      + "', '"
                      + reason
                      + "')::text")
              .get(0);
      assertThat(resolution).isNotNull();
      assertSqlState(
          op,
          "SELECT fraudshield.resolve_contested_decision('"
              + tx
              + "', 'DECLINE', '"
              + analystA
              + "', '"
              + reason
              + "')",
          PERMISSION_DENIED);
      assertSqlState(
          op,
          "SELECT fraudshield.release_failed_claim(gen_random_uuid(), '"
              + officer
              + "', '"
              + reason
              + "')",
          "23514");
    }
    try (Connection admin = db.superuser()) {
      assertThat(
              strings(
                  admin,
                  "SELECT db_login || '/' || kind || '/' || chosen_decision FROM"
                      + " fraudshield.decision_conflict_resolutions WHERE transaction_id = '"
                      + tx
                      + "'"))
          .containsExactly(login + "/RESOLVE/DECLINE");
      assertThat(
              strings(
                  admin,
                  "SELECT action FROM fraudshield.audit_events WHERE entity_id = '" + tx + "'"))
          .containsExactly("RESOLUTION_AUTHORISED");
    }
    try (Connection app = db.as("fs_app")) {
      assertSqlState(
          app,
          "SELECT fraudshield.resolve_contested_decision('"
              + tx
              + "', 'DECLINE', '"
              + officer
              + "', '"
              + reason
              + "')",
          PERMISSION_DENIED);
      assertThat(strings(app, "SELECT alarm FROM fraudshield.decision_alarm_figures()"))
          .containsExactlyInAnyOrder(
              "contested",
              "holds_overdue",
              "answers_deferred",
              "effects_pending",
              "claims_failed",
              "commits_overdue",
              "requests_unrecorded");
      assertThat(
              strings(
                  app,
                  "SELECT total::text FROM fraudshield.decision_alarm_figures()"
                      + " WHERE alarm = 'contested'"))
          .as("counts across institutions, contents never")
          .containsExactly("1");
    }
  }

  @Test
  @Tag("FR-05-07")
  void configurationChangesCannotBeSelfReviewedOrStacked() throws SQLException {
    UUID officerA = insertUser(BANK_A, "risk.a@example.com", "EMPA0101", "RISK_OFFICER");
    try (Connection app = tenant("fs_app", BANK_A)) {
      String change =
          "INSERT INTO config_changes (id, institution_id, kind, direction, status, proposed_by, "
              + "reason,"
              + " base_version, previous, proposed) VALUES (%s, '"
              + BANK_A
              + "', 'CHANNEL_THRESHOLDS', 'LOOSENING',"
              + " 'PENDING_APPROVAL', '"
              + officerA
              + "', 'Too many false alarms on CARD', 1, '{}', '{}')";
      UUID first = UUID.randomUUID();
      exec(app, String.format(change, "'" + first + "'"));
      assertSqlState(app, String.format(change, "gen_random_uuid()"), "23505");
      assertSqlState(
          app,
          "UPDATE config_changes SET status = 'APPROVED', reviewed_by = proposed_by, reviewed_at "
              + "= now(),"
              + " effective_at = now() WHERE id = '"
              + first
              + "'",
          "23514");
      assertSqlState(
          app,
          "UPDATE config_changes SET reason = 'Rewritten after the fact' WHERE id = '"
              + first
              + "'",
          PERMISSION_DENIED);
    }
  }

  @Test
  void verificationTokenAnswersOnce() throws SQLException {
    try (Connection app = tenant("fs_app", BANK_A)) {
      UUID block = uuid(app, "SELECT id FROM auto_block_events LIMIT 1");
      UUID verification =
          uuid(
              app,
              "INSERT INTO customer_verifications (institution_id, auto_block_event_id,"
                  + " verification_token_hash, verification_channel, expires_at) VALUES ('"
                  + BANK_A
                  + "', '"
                  + block
                  + "', sha256('token-1'::bytea), 'SMS_LINK', now() + interval '1 hour') "
                  + "RETURNING id");
      exec(
          app,
          "INSERT INTO customer_verification_responses (verification_id, institution_id, answer,"
              + " self_service_allowed) VALUES ('"
              + verification
              + "', '"
              + BANK_A
              + "', 'WAS_ME', true)");
      assertSqlState(
          app,
          "INSERT INTO customer_verification_responses (verification_id, institution_id, answer,"
              + " self_service_allowed) VALUES ('"
              + verification
              + "', '"
              + BANK_A
              + "', 'NOT_ME', true)",
          "23505");
      app.rollback();
    }
  }

  // --- helpers
  // ---------------------------------------------------------------------------------------

  private static List<String> appendOnlyTables() throws SQLException {
    try (Connection admin = db.superuser()) {
      return strings(
          admin,
          """
          SELECT DISTINCT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'fraudshield'
          WHERE t.tgname = 'append_only_rows' AND c.relkind IN ('r', 'p') ORDER BY 1
          """);
    }
  }

  private static String verify(short partition) throws SQLException {
    try (Connection compliance = db.as("fs_compliance_ro");
        Statement statement = compliance.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT * FROM verify_audit_chain(" + partition + "::smallint)")) {
      rows.next();
      return rows.getLong(1) + "|" + rows.getObject(2) + "|" + rows.getString(3);
    }
  }

  private static UUID insertUser(UUID institution, String email, String employeeId, String role)
      throws SQLException {
    try (Connection app = tenant("fs_app", institution)) {
      UUID id =
          uuid(
              app,
              "INSERT INTO users (institution_id, first_name, last_name, email, password_hash, "
                  + "role, status,"
                  + " employee_id, department) VALUES ('"
                  + institution
                  + "', 'Synthetic', 'Person', '"
                  + email
                  + "',"
                  + " '$2b$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzabcd', '"
                  + role
                  + "', 'ACTIVE', '"
                  + employeeId
                  + "', 'FRAUD_OPERATIONS') RETURNING id");
      app.commit();
      return id;
    }
  }

  private static UUID insertDecision(UUID institution, UUID analyst) throws SQLException {
    try (Connection app = tenant("fs_app", institution)) {
      UUID alert =
          uuid(
              app,
              "INSERT INTO alert_queue_entries (institution_id, transaction_id, fraud_score_id, "
                  + "tier,"
                  + " fraud_probability, review_deadline_at) VALUES ('"
                  + institution
                  + "', gen_random_uuid(),"
                  + " gen_random_uuid(), 'MEDIUM', 0.7, now() + interval '30 seconds') RETURNING "
                  + "id");
      UUID decision =
          uuid(
              app,
              "INSERT INTO alert_decisions (institution_id, alert_queue_entry_id, analyst_id, "
                  + "decision,"
                  + " analyst_comment, idempotency_key) VALUES ('"
                  + institution
                  + "', '"
                  + alert
                  + "', '"
                  + analyst
                  + "', 'CONFIRM_FRAUD', 'Velocity spike after SIM swap', gen_random_uuid()) "
                  + "RETURNING id");
      app.commit();
      return decision;
    }
  }

  private static void insertAuditAt(Connection connection, short partition, int daysAgo)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO audit_events (institution_id, writer_partition, event_type, action,
              entity_type, entity_id, event_at)
            VALUES (?, ?, 'RULE_CHANGE', 'UPDATED', 'rule', 'r1', now() - make_interval(days => ?))
            """)) {
      insert.setObject(1, BANK_A);
      insert.setShort(2, partition);
      insert.setInt(3, daysAgo);
      insert.executeUpdate();
    }
  }

  private static void insertAudit(
      Connection connection, UUID institution, int partition, String action) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO audit_events (institution_id, writer_partition, event_type, action,
              entity_type, entity_id,
              event_at, before_value, ip_address)
            VALUES (?, ?, 'AUTH', ?, 'user', 'u1', now(), '{"attempt": 1}', '203.0.113.10')
            """)) {
      insert.setObject(1, institution);
      insert.setShort(2, (short) partition);
      insert.setString(3, action);
      insert.executeUpdate();
    }
  }

  private static String modelInsert(String version, String production, String shadow) {
    return "INSERT INTO model_versions (model_version, algorithm, xgb_weight, lgb_weight, "
        + "training_dataset_size,"
        + " training_date, mlflow_run_id, is_production, is_shadow, deployed_at) VALUES ('"
        + version
        + "', 'xgb+lgb', 0.55, 0.45, 1000, now(), 'run-"
        + version
        + "', "
        + production
        + ", "
        + shadow
        + ", now())";
  }

  private static Connection tenant(String role, UUID institution) throws SQLException {
    Connection connection = db.as(role);
    connection.setAutoCommit(false);
    exec(connection, "SET LOCAL fraudshield.institution_id = '" + institution + "'");
    return connection;
  }

  private static void assertSqlState(Connection connection, String sql, String state)
      throws SQLException {
    exec(connection, "SAVEPOINT expected_failure", connection.getAutoCommit());
    assertThatThrownBy(() -> exec(connection, sql))
        .isInstanceOf(SQLException.class)
        .extracting(e -> ((SQLException) e).getSQLState())
        .as(sql)
        .isEqualTo(state);
    exec(connection, "ROLLBACK TO SAVEPOINT expected_failure", connection.getAutoCommit());
  }

  private static void exec(Connection connection, String sql, boolean skip) throws SQLException {
    if (!skip) {
      exec(connection, sql);
    }
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long count(Connection connection, String sql) throws SQLException {
    return longs(connection, sql).get(0);
  }

  private static boolean bool(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private static UUID uuid(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getObject(1, UUID.class);
    }
  }

  private static List<Long> longs(Connection connection, String sql) throws SQLException {
    List<Long> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getLong(1));
      }
    }
    return values;
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
