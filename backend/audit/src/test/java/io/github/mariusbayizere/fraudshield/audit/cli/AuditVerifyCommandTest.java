package io.github.mariusbayizere.fraudshield.audit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.anchor.AnchorKeys;
import io.github.mariusbayizere.fraudshield.audit.anchor.AnchorSigner;
import io.github.mariusbayizere.fraudshield.audit.anchor.AuditAnchorService;
import io.github.mariusbayizere.fraudshield.audit.jdbc.JdbcAuditLog;
import io.github.mariusbayizere.fraudshield.audit.testing.AppDatabase;
import io.github.mariusbayizere.fraudshield.audit.testing.Pem;
import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("D-32")
class AuditVerifyCommandTest {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private AuditVerifyCommand command(Map<String, String> environment) {
    PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
    return new AuditVerifyCommand(environment, outStream::println, errStream::println);
  }

  @Test
  void rejectsMalformedArgumentsWithTheUsage() {
    assertThat(command(Map.of()).run("audit", "verify")).isEqualTo(AuditVerifyCommand.USAGE_ERROR);
    assertThat(
            command(Map.of()).run("audit", "check", "--from", "2026-09-01", "--to", "2026-09-02"))
        .isEqualTo(2);
    assertThat(
            command(Map.of()).run("audit", "verify", "--from", "yesterday", "--to", "2026-09-02"))
        .isEqualTo(2);
    assertThat(
            command(Map.of()).run("audit", "verify", "--since", "2026-09-01", "--to", "2026-09-02"))
        .isEqualTo(2);
    assertThat(
            command(Map.of()).run("audit", "verify", "--from", "2026-09-03", "--to", "2026-09-02"))
        .isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: fraudshield audit verify");
  }

  @Test
  void requiresTheDatabaseUrlAndWellFormedKeys() {
    assertThat(
            command(Map.of()).run("audit", "verify", "--from", "2026-09-01", "--to", "2026-09-02"))
        .isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("FRAUDSHIELD_DB_URL is not set");
    assertThatThrownBy(() -> AuditVerifyCommand.publicKeys("no-equals-sign"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuditVerifyCommand.publicKeys(" , ")).isEmpty();
  }

  @Test
  @Tag("requires-docker")
  void verifiesRealDatabaseThenReportsTampering(@TempDir Path dir) throws Exception {
    TestDatabase db = TestDatabase.create();
    AppDatabase app = AppDatabase.of(db, "fs_app");
    UUID bank = db.createInstitution("bank-a");
    JdbcAuditLog log = new JdbcAuditLog(app.jdbc(), (short) 0);
    for (int i = 0; i < 3; i++) {
      app.tenants()
          .runInTenant(
              bank,
              () ->
                  log.record(
                      AuditEvent.of(bank, AuditEventType.AUTH, "LOGOUT")
                          .entity("user", "u")
                          .at(Instant.now())));
    }
    KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Path publicPem = dir.resolve("anchor.pub.pem");
    Files.writeString(publicPem, Pem.of("PUBLIC KEY", keys.getPublic().getEncoded()));
    new AuditAnchorService(
            app.jdbc(), app.transactions(), new AnchorSigner("k1", keys.getPrivate()))
        .anchorAll(LocalDate.parse("2026-09-21"));
    assertThat(AnchorKeys.publicKey(publicPem)).isEqualTo(keys.getPublic());

    Map<String, String> environment =
        Map.of(
            "FRAUDSHIELD_DB_URL", db.url(),
            "FRAUDSHIELD_DB_COMPLIANCE_PASSWORD", db.password("fs_compliance_ro"),
            "FRAUDSHIELD_AUDIT_ANCHOR_PUBLIC_KEYS", "k1=" + publicPem);
    String[] args = {"audit", "verify", "--from", "2026-09-01", "--to", "2026-09-30"};
    assertThat(command(environment).run(args)).isEqualTo(AuditVerifyCommand.VERIFIED);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("3 rows re-hashed")
        .contains("VERIFIED");

    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement()) {
      statement.execute("SET session_replication_role = replica");
      statement.execute(
          "UPDATE fraudshield.audit_events SET entity_id = 'someone-else' WHERE seq = 2");
    }
    out.reset();
    assertThat(command(environment).run(args)).isEqualTo(AuditVerifyCommand.PROBLEMS_FOUND);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("TAMPERING OR ERROR partition=0 seq=2")
        .contains("FAILED");
  }
}
