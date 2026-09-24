package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.persistence.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** The demo seed creates the dual-control accounts with generated credentials only (ADR 0019). */
@Tag("requires-docker")
@Tag("FR-05-07")
@Tag("D-21")
class DemoDataSeederTest {

  private static final String API_KEY = "fsk_dev_a1b2c3d4e5f6_" + "Kq3".repeat(14) + "Z";
  private static final String PEPPER = "0f".repeat(32);
  private static final DemoSeedProperties.Passwords PASSWORDS =
      new DemoSeedProperties.Passwords(
          "Analyst-Gen3rated!pw",
          "Senior-Gen3rated!pw",
          "RiskA-Gen3rated!pw",
          "RiskB-Gen3rated!pw",
          "Admin-Gen3rated!pw");

  private static TestDatabase db;
  private static DemoDataSeeder seeder;

  @BeforeAll
  static void seedOnce() {
    db = TestDatabase.create();
    seeder = seeder(db, new DemoSeedProperties(true, PASSWORDS, API_KEY, PEPPER));
    assertThat(seeder.seed()).isTrue();
  }

  private static DemoDataSeeder seeder(TestDatabase database, DemoSeedProperties properties) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            database.url(), "fs_migrator", database.password("fs_migrator"));
    return new DemoDataSeeder(
        properties,
        new JdbcTemplate(dataSource),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        Clock.systemUTC());
  }

  @Test
  void seedsTwoRiskOfficersAndTheOtherRolesWithBcryptHashes() throws SQLException {
    Map<String, String> hashes = new TreeMap<>();
    Map<String, String> roles = new TreeMap<>();
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT email, role, password_hash FROM fraudshield.users")) {
      while (rows.next()) {
        roles.put(rows.getString(1), rows.getString(2));
        hashes.put(rows.getString(1), rows.getString(3));
      }
    }
    assertThat(roles.values())
        .containsExactlyInAnyOrder(
            "ADMIN", "ANALYST", "SENIOR_ANALYST", "RISK_OFFICER", "RISK_OFFICER");
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    assertThat(
            encoder.matches(
                PASSWORDS.riskOfficerA(), hashes.get("risk.officer.a.demo@example.com")))
        .isTrue();
    assertThat(
            encoder.matches(
                PASSWORDS.riskOfficerB(), hashes.get("risk.officer.b.demo@example.com")))
        .isTrue();
    assertThat(hashes.values()).allSatisfy(hash -> assertThat(hash).startsWith("$2a$12$"));
  }

  @Test
  void storesNoPlaintextSecret() throws SQLException {
    ApiKeyHasher.ParsedKey key = ApiKeyHasher.parse(API_KEY);
    try (Connection admin = db.superuser()) {
      for (String secret :
          List.of(PASSWORDS.admin(), PASSWORDS.riskOfficerA(), key.secret(), API_KEY)) {
        try (PreparedStatement search =
            admin.prepareStatement(
                """
                SELECT (SELECT count(*) FROM fraudshield.users u WHERE strpos(u::text, ?) > 0)
                     + (SELECT count(*) FROM fraudshield.api_keys k WHERE strpos(k::text, ?) > 0)
                """)) {
          search.setString(1, secret);
          search.setString(2, secret);
          try (ResultSet rows = search.executeQuery()) {
            rows.next();
            assertThat(rows.getLong(1)).as("plaintext secret stored").isZero();
          }
        }
      }
      try (Statement statement = admin.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT secret_hmac, last_four, key_id FROM fraudshield.api_keys")) {
        rows.next();
        assertThat(rows.getBytes(1))
            .isEqualTo(ApiKeyHasher.hmac(HexFormat.of().parseHex(PEPPER), key.secret()));
        assertThat(rows.getString(2)).isEqualTo(key.lastFour());
        assertThat(rows.getString(3)).isEqualTo("a1b2c3d4e5f6");
      }
    }
  }

  @Test
  void seedsInitialRiskConfigurationAndAuditEvent() throws SQLException {
    try (Connection admin = db.superuser();
        Statement statement = admin.createStatement()) {
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT count(*) FROM fraudshield.risk_thresholds WHERE version = 1")) {
        rows.next();
        assertThat(rows.getLong(1)).isEqualTo(6);
      }
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT seq FROM fraudshield.audit_events WHERE action = 'DEMO_DATA_SEEDED'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isPositive();
      }
    }
  }

  @Test
  void seedingIsIdempotent() {
    assertThat(seeder.seed()).isFalse();
  }

  @Test
  void rejectsWeakPasswordsAndNonDevelopmentKeys() {
    TestDatabase other = TestDatabase.create();
    DemoSeedProperties.Passwords weak =
        new DemoSeedProperties.Passwords("short", "x", "y", "z", "w");
    assertThatThrownBy(
            () -> seeder(other, new DemoSeedProperties(true, weak, API_KEY, PEPPER)).seed())
        .isInstanceOf(IllegalStateException.class);
    String productionKey = API_KEY.replace("fsk_dev_", "fsk_prod_");
    assertThatThrownBy(
            () ->
                seeder(other, new DemoSeedProperties(true, PASSWORDS, productionKey, PEPPER))
                    .seed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dev key");
  }
}
