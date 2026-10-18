package io.github.mariusbayizere.fraudshield.audit.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.testing.AppDatabase;
import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

@Tag("requires-docker")
@Tag("FR-06-06")
class JdbcAuditLogTest {

  private static final Instant EVENT_AT = Instant.parse("2026-09-22T08:00:00Z");
  private static TestDatabase db;
  private static AppDatabase app;
  private static UUID bankA;
  private static UUID bankB;

  @BeforeAll
  static void createDatabase() {
    db = TestDatabase.create();
    app = AppDatabase.of(db, "fs_app");
    bankA = db.createInstitution("bank-a");
    bankB = db.createInstitution("bank-b");
  }

  private static AuditEvent event(UUID institution, String action) {
    return AuditEvent.of(institution, AuditEventType.USER_ADMIN, action)
        .entity("user", "u-1")
        .actor(new AuditActor(UUID.randomUUID(), "Amani", "Mukiza", StaffRole.ADMIN))
        .before(Map.of("role", "ANALYST"))
        .after(Map.of("role", "SENIOR_ANALYST"))
        .context(new RequestContext("192.0.2.10", "junit", UUID.randomUUID()))
        .at(EVENT_AT);
  }

  @Test
  void writesTheEventWithActorValuesAndContextAndTheDatabaseAssignsTheChain() {
    JdbcAuditLog log = new JdbcAuditLog(app.jdbc(), (short) 3);
    app.tenants().runInTenant(bankA, () -> log.record(event(bankA, "ROLE_CHANGED")));
    List<Map<String, Object>> rows =
        app.tenants()
            .inTenant(
                bankA,
                () ->
                    app.jdbc()
                        .queryForList(
                            """
                            SELECT seq, event_type, action, user_first_name, user_role,
                              after_value->>'role' AS after_role, host(ip_address) AS ip,
                              octet_length(row_hash) AS hash_bytes
                            FROM v_audit_events WHERE writer_partition = 3
                            """));
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.getFirst();
    assertThat(row)
        .containsEntry("seq", 1L)
        .containsEntry("event_type", "USER_ADMIN")
        .containsEntry("action", "ROLE_CHANGED")
        .containsEntry("user_first_name", "Amani")
        .containsEntry("user_role", "ADMIN")
        .containsEntry("after_role", "SENIOR_ANALYST")
        .containsEntry("ip", "192.0.2.10")
        .containsEntry("hash_bytes", 32);
  }

  @Test
  void refusesToWriteOutsideTransaction() {
    JdbcAuditLog log = new JdbcAuditLog(app.jdbc(), (short) 4);
    assertThatThrownBy(() -> log.record(event(bankA, "ROLE_CHANGED")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cannotWriteAnEventForAnotherInstitution() {
    JdbcAuditLog log = new JdbcAuditLog(app.jdbc(), (short) 5);
    assertThatThrownBy(
            () -> app.tenants().runInTenant(bankA, () -> log.record(event(bankB, "X_Y"))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void rejectsPartitionOutsideTheSchemaRange() {
    assertThatThrownBy(() -> new JdbcAuditLog(app.jdbc(), (short) 64))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JdbcAuditLog(app.jdbc(), (short) -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transactionRefusedByTheChainIsRetriedFromTheStart() {
    JdbcAuditLog log = new JdbcAuditLog(app.jdbc(), (short) 6);
    AppDatabase other = AppDatabase.of(db, "fs_app");
    JdbcAuditLog otherLog = new JdbcAuditLog(other.jdbc(), (short) 6);
    AtomicInteger attempts = new AtomicInteger();
    app.tenants()
        .runInTenant(
            bankA,
            () -> {
              // Pin this transaction's start time, then let another writer append to the same chain
              // and commit: the chain must now refuse this transaction's older recorded_at.
              app.jdbc().queryForObject("SELECT now()", Instant.class);
              if (attempts.incrementAndGet() == 1) {
                other
                    .tenants()
                    .runInTenant(bankA, () -> otherLog.record(event(bankA, "OTHER_WRITER")));
              }
              log.record(event(bankA, "RETRIED_WRITER"));
            });
    assertThat(attempts).hasValue(2);
    assertThat(
            app.tenants()
                .inTenant(
                    bankA,
                    () ->
                        app.jdbc()
                            .queryForList(
                                "SELECT action FROM v_audit_events WHERE writer_partition = 6"
                                    + " ORDER BY seq",
                                String.class)))
        .containsExactly("OTHER_WRITER", "RETRIED_WRITER");
  }

  @Test
  void otherFailuresAreNotRetried() {
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                app.tenants()
                    .runInTenant(
                        bankA,
                        () -> {
                          attempts.incrementAndGet();
                          app.jdbc().execute("SELECT 1/0");
                        }))
        .isInstanceOf(DataAccessException.class);
    assertThat(attempts).hasValue(1);
  }

  @Test
  void persistentSerializationFailureGivesUpAfterTheConfiguredAttempts() {
    TenantTransactions twice = new TenantTransactions(app.transactions(), app.jdbc(), 2);
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                twice.runInTenant(
                    bankA,
                    () -> {
                      attempts.incrementAndGet();
                      app.jdbc()
                          .execute(
                              "DO $$ BEGIN RAISE EXCEPTION 'x' USING ERRCODE = '40001'; END $$");
                    }))
        .isInstanceOf(DataAccessException.class);
    assertThat(attempts).hasValue(2);
    assertThatThrownBy(() -> new TenantTransactions(app.transactions(), app.jdbc(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
