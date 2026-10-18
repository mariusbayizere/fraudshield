package io.github.mariusbayizere.fraudshield.audit.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.AnchorStatement;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.jdbc.JdbcAuditLog;
import io.github.mariusbayizere.fraudshield.audit.testing.AppDatabase;
import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * D-32 end to end on a real database: anchors over chains shared by two institutions, idempotent
 * re-runs, and verification catching edited rows, deleted rows and forged anchors.
 */
@Tag("requires-docker")
@Tag("D-32")
@Tag("FR-06-06")
class AuditAnchoringTest {

  private static final LocalDate DAY_1 = LocalDate.parse("2026-09-20");
  private static final LocalDate DAY_2 = LocalDate.parse("2026-09-21");

  private TestDatabase db;
  private AppDatabase app;
  private AppDatabase compliance;
  private UUID bankA;
  private UUID bankB;
  private KeyPair keys;
  private AuditAnchorService anchoring;

  @BeforeEach
  void createDatabase() throws Exception {
    db = TestDatabase.create();
    app = AppDatabase.of(db, "fs_app");
    compliance = AppDatabase.of(db, "fs_compliance_ro");
    bankA = db.createInstitution("bank-a");
    bankB = db.createInstitution("bank-b");
    keys = AnchorSignerTest.keyPair();
    anchoring =
        new AuditAnchorService(
            app.jdbc(), app.transactions(), new AnchorSigner("anchor-1", keys.getPrivate()));
  }

  private void write(short partition, UUID institution, int count) {
    JdbcAuditLog log = new JdbcAuditLog(app.jdbc(), partition);
    for (int i = 0; i < count; i++) {
      app.tenants()
          .runInTenant(
              institution,
              () ->
                  log.record(
                      AuditEvent.of(institution, AuditEventType.AUTH, "LOGIN_SUCCEEDED")
                          .entity("user", UUID.randomUUID())
                          .at(Instant.parse("2026-09-20T10:00:00Z"))));
    }
  }

  private AuditChainVerifier verifier() {
    return new AuditChainVerifier(
        compliance.jdbc(),
        compliance.transactions(),
        Map.of("anchor-1", keys.getPublic()),
        java.time.Clock.systemUTC());
  }

  private void superuser(String sql) throws Exception {
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute(sql);
    }
  }

  @Test
  void anchorsCoverEachPartitionsNewRowsAndVerify() {
    write((short) 1, bankA, 3);
    write((short) 1, bankB, 2);
    write((short) 2, bankB, 4);

    List<AnchorStatement> day1 = anchoring.anchorAll(DAY_1);
    assertThat(day1)
        .extracting(
            AnchorStatement::writerPartition, AnchorStatement::afterSeq, AnchorStatement::lastSeq)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple((short) 1, 0L, 5L),
            org.assertj.core.groups.Tuple.tuple((short) 2, 0L, 4L));
    assertThat(anchoring.anchorAll(DAY_1)).as("a second run for the day does nothing").isEmpty();

    write((short) 1, bankA, 2);
    assertThat(anchoring.anchorAll(DAY_2))
        .extracting(
            AnchorStatement::writerPartition, AnchorStatement::afterSeq, AnchorStatement::lastSeq)
        .containsExactly(org.assertj.core.groups.Tuple.tuple((short) 1, 5L, 7L));
    assertThat(anchoring.anchor((short) 1, DAY_1))
        .as("never anchors before a later anchor")
        .isEmpty();

    AuditChainVerifier.Report report = verifier().verify(DAY_1, DAY_2);
    assertThat(report.problems()).isEmpty();
    assertThat(report.verified()).isTrue();
    assertThat(report.rowsChecked()).isEqualTo(11);
    assertThat(report.anchorsChecked()).isEqualTo(3);
    assertThat(report.anchorsRecomputed()).isEqualTo(3);

    AuditChainVerifier.Report fromDay2 = verifier().verify(DAY_2, DAY_2);
    assertThat(fromDay2.verified()).isTrue();
    assertThat(fromDay2.anchorsRecomputed()).isEqualTo(1);
    assertThat(fromDay2.rowsChecked())
        .as("the chain check starts at the day-1 anchor")
        .isEqualTo(2);
  }

  @Test
  void editedRowIsReportedByTheChainAndByItsAnchor() throws Exception {
    write((short) 1, bankA, 4);
    anchoring.anchorAll(DAY_1);
    superuser(
        "UPDATE fraudshield.audit_events SET action = 'TAMPERED'"
            + " WHERE writer_partition = 1 AND seq = 2");

    AuditChainVerifier.Report report = verifier().verify(DAY_1, DAY_1);
    assertThat(report.verified()).isFalse();
    assertThat(report.problems())
        .extracting(AuditChainVerifier.Problem::description)
        .contains("row content does not match row_hash");
  }

  @Test
  void editedRowWithRecomputedHashIsCaughtByTheSignedAnchor() throws Exception {
    write((short) 1, bankA, 4);
    anchoring.anchorAll(DAY_1);
    // A superuser rewrites the last row and its hash consistently: the chain alone re-verifies,
    // because nothing follows the last row, but the signed anchor does not.
    superuser(
        """
        UPDATE fraudshield.audit_events e SET action = 'TAMPERED' WHERE writer_partition = 1 AND seq = 4;
        UPDATE fraudshield.audit_events e SET row_hash = fraudshield.audit_row_hash(e)
          WHERE writer_partition = 1 AND seq = 4;
        UPDATE fraudshield.audit_chain_heads h SET last_hash = e.row_hash
          FROM fraudshield.audit_events e WHERE e.writer_partition = 1 AND e.seq = 4 AND h.writer_partition = 1;
        """);
    AuditChainVerifier.Report report = verifier().verify(DAY_1, DAY_1);
    assertThat(report.problems())
        .extracting(AuditChainVerifier.Problem::description)
        .anySatisfy(d -> assertThat(d).contains("differs from the signed"));
  }

  @Test
  void deletedRowIsReported() throws Exception {
    write((short) 1, bankA, 4);
    anchoring.anchorAll(DAY_1);
    superuser("DELETE FROM fraudshield.audit_events WHERE writer_partition = 1 AND seq = 3");
    AuditChainVerifier.Report report = verifier().verify(DAY_1, DAY_1);
    assertThat(report.problems()).extracting(AuditChainVerifier.Problem::seq).contains(3L);
  }

  @Test
  void forgedOrUnknownKeyAnchorIsReported() throws Exception {
    write((short) 1, bankA, 2);
    anchoring.anchorAll(DAY_1);
    superuser("UPDATE fraudshield.audit_anchors SET signature = '\\x" + "00".repeat(64) + "'");
    assertThat(verifier().verify(DAY_1, DAY_1).problems())
        .extracting(AuditChainVerifier.Problem::description)
        .contains("anchor 2026-09-20 has an invalid signature");

    AuditChainVerifier unknownKey =
        new AuditChainVerifier(
            compliance.jdbc(), compliance.transactions(), Map.of(), java.time.Clock.systemUTC());
    assertThat(unknownKey.verify(DAY_1, DAY_1).problems())
        .extracting(AuditChainVerifier.Problem::description)
        .contains("anchor 2026-09-20 is signed by unknown key anchor-1");
  }

  @Test
  void anchoringJobRefusesToSignBrokenChain() throws Exception {
    write((short) 1, bankA, 3);
    superuser(
        "UPDATE fraudshield.audit_events SET prev_hash = '\\x"
            + "11".repeat(32)
            + "' WHERE writer_partition = 1 AND seq = 2");
    assertThatThrownBy(() -> anchoring.anchor((short) 1, DAY_1))
        .isInstanceOf(AuditChainBrokenException.class)
        .hasMessageContaining("prev_hash of seq 2");
    superuser("DELETE FROM fraudshield.audit_events WHERE writer_partition = 1 AND seq = 3");
    assertThatThrownBy(() -> anchoring.anchor((short) 1, DAY_2))
        .isInstanceOf(AuditChainBrokenException.class);
  }

  @Test
  void theRangesMustBeOrdered() {
    assertThatThrownBy(() -> verifier().verify(DAY_2, DAY_1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private AuditChainVerifier verifierThreeDaysLater() {
    return new AuditChainVerifier(
        compliance.jdbc(),
        compliance.transactions(),
        Map.of("anchor-1", keys.getPublic()),
        java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofDays(3)));
  }

  @Test
  void rowsOfDueDaysMustBeCoveredSoDeletingTheLatestAnchorsIsDetected() throws Exception {
    LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
    write((short) 1, bankA, 3);
    assertThat(verifierThreeDaysLater().verify(today, today.plusDays(1)).problems())
        .as("no anchor at all for rows of a due day")
        .extracting(AuditChainVerifier.Problem::description)
        .anySatisfy(d -> assertThat(d).contains("not covered by any anchor"));

    anchoring.anchorAll(today);
    assertThat(verifierThreeDaysLater().verify(today, today.plusDays(1)).verified()).isTrue();

    // The attack of review finding 3: rewrite the tail consistently and delete the anchor.
    superuser(
        """
        UPDATE fraudshield.audit_events e SET action = 'TAMPERED' WHERE writer_partition = 1 AND seq = 3;
        UPDATE fraudshield.audit_events e SET row_hash = fraudshield.audit_row_hash(e)
          WHERE writer_partition = 1 AND seq = 3;
        UPDATE fraudshield.audit_chain_heads h SET last_hash = e.row_hash
          FROM fraudshield.audit_events e WHERE e.writer_partition = 1 AND e.seq = 3
          AND h.writer_partition = 1;
        DELETE FROM fraudshield.audit_anchors WHERE writer_partition = 1;
        """);
    AuditChainVerifier.Report report = verifierThreeDaysLater().verify(today, today.plusDays(1));
    assertThat(report.verified()).isFalse();
    assertThat(report.problems())
        .extracting(AuditChainVerifier.Problem::description)
        .anySatisfy(d -> assertThat(d).contains("not covered by any anchor"));
  }
}
