package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds one synthetic demo institution with five staff accounts (including the two risk officers
 * that dual control needs), a demo API key and the initial risk configuration (ADR 0019).
 *
 * <p>Only generated credentials are used; passwords are stored as bcrypt (cost 12) hashes and the
 * API key as an HMAC. Seeding is idempotent: if the demo institution exists nothing is changed.
 */
public final class DemoDataSeeder implements ApplicationRunner {

  /** Code of the demo institution. */
  public static final String INSTITUTION_CODE = "demo-bank";

  private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);
  private static final int BCRYPT_COST = 12;
  private static final int MAX_PASSWORD_BYTES = 72;
  private static final int MIN_PASSWORD_CHARACTERS = 12;
  private static final List<String> CHANNELS =
      List.of("MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER");

  private final DemoSeedProperties properties;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the seeder.
   *
   * @param properties generated credentials
   * @param jdbc database access
   * @param transactions transaction boundary for the whole seed
   * @param clock time source
   */
  public DemoDataSeeder(
      DemoSeedProperties properties,
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  /**
   * Seeds the demo data if it is not present.
   *
   * @return true if data was inserted, false if the demo institution already existed
   */
  public boolean seed() {
    DemoSeedProperties.Passwords passwords =
        Objects.requireNonNull(properties.passwords(), "demo passwords are not configured");
    ApiKeyHasher.ParsedKey apiKey = ApiKeyHasher.parse(properties.apiKey());
    byte[] pepper = HexFormat.of().parseHex(Objects.requireNonNull(properties.apiKeyPepperHex()));
    Boolean inserted =
        transactions.execute(
            status -> {
              List<UUID> existing =
                  jdbc.queryForList(
                      "SELECT id FROM fraudshield.institutions WHERE code = ?",
                      UUID.class,
                      INSTITUTION_CODE);
              if (!existing.isEmpty()) {
                return false;
              }
              UUID institution = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO fraudshield.institutions (id, code, name, country, synthetic)"
                      + " VALUES (?, ?, ?, ?, true)",
                  institution,
                  INSTITUTION_CODE,
                  "Synthetic Demo Bank (not a real institution)",
                  "RW");
              jdbc.queryForObject(
                  "SELECT set_config('fraudshield.institution_id', ?, true)",
                  String.class,
                  institution.toString());
              BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);
              final UUID admin =
                  user(
                      institution,
                      encoder,
                      new Account("Amani", "Mukiza", "admin", "DEMOADM1", "ADMIN", "IT"),
                      passwords.admin());
              user(
                  institution,
                  encoder,
                  new Account(
                      "Aline", "Uwase", "analyst", "DEMOANL1", "ANALYST", "FRAUD_OPERATIONS"),
                  passwords.analyst());
              user(
                  institution,
                  encoder,
                  new Account(
                      "Jean Bosco",
                      "Habimana",
                      "senior.analyst",
                      "DEMOSNR1",
                      "SENIOR_ANALYST",
                      "FRAUD_OPERATIONS"),
                  passwords.seniorAnalyst());
              user(
                  institution,
                  encoder,
                  new Account(
                      "Hélène", "Ngũgĩ", "risk.officer.a", "DEMORSK1", "RISK_OFFICER", "RISK"),
                  passwords.riskOfficerA());
              user(
                  institution,
                  encoder,
                  new Account(
                      "Wanjirũ", "N'Dri", "risk.officer.b", "DEMORSK2", "RISK_OFFICER", "RISK"),
                  passwords.riskOfficerB());
              apiKey(institution, admin, apiKey, pepper);
              riskConfiguration(institution);
              audit(institution, admin);
              return true;
            });
    boolean seeded = Boolean.TRUE.equals(inserted);
    LOG.info(
        seeded
            ? "Seeded synthetic demo institution {} (credentials are in .demo-credentials)"
            : "Demo institution {} already exists; nothing changed",
        INSTITUTION_CODE);
    return seeded;
  }

  /** A synthetic demo account; names are illustrative, not real people. */
  private record Account(
      String firstName,
      String lastName,
      String login,
      String employeeId,
      String role,
      String department) {}

  private UUID user(
      UUID institution, BCryptPasswordEncoder encoder, Account account, String password) {
    requireUsablePassword(account.login(), password);
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO fraudshield.users (id, institution_id, first_name, last_name, email,
          password_hash,
          role, requested_role, status, email_verified, preferred_locale, employee_id, department)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', true, 'en', ?, ?)
        """,
        id,
        institution,
        account.firstName(),
        account.lastName(),
        account.login() + ".demo@example.com",
        encoder.encode(password),
        account.role(),
        account.role(),
        account.employeeId(),
        account.department());
    return id;
  }

  private static void requireUsablePassword(String login, String password) {
    if (password == null
        || password.length() < MIN_PASSWORD_CHARACTERS
        || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new IllegalStateException(
          "the generated password for the demo " + login + " account is missing or invalid");
    }
  }

  private void apiKey(UUID institution, UUID admin, ApiKeyHasher.ParsedKey key, byte[] pepper) {
    if (!"dev".equals(key.environment())) {
      throw new IllegalStateException("the demo API key must be a dev key (fsk_dev_...)");
    }
    jdbc.update(
        """
        INSERT INTO fraudshield.api_keys (institution_id, key_id, name, secret_hmac, pepper_version,
          last_four, scopes, created_by)
        VALUES (?, ?, 'Demo core banking', ?, 1, ?, ARRAY['ingest:write', 'decisions:read',
          'jobs:read'], ?)
        """,
        institution,
        key.keyId(),
        ApiKeyHasher.hmac(pepper, key.secret()),
        key.lastFour(),
        admin);
  }

  private void riskConfiguration(UUID institution) {
    jdbc.update(
        "INSERT INTO fraudshield.risk_threshold_versions (institution_id, version) VALUES (?, 1)",
        institution);
    for (String channel : CHANNELS) {
      jdbc.update(
          """
          INSERT INTO fraudshield.risk_thresholds (institution_id, version, channel,
            medium_threshold,
            high_threshold, medium_timeout_policy)
          VALUES (?, 1, ?, 0.6000, 0.8500, 'RELEASE_WITH_TIMEOUT_LABEL')
          """,
          institution,
          channel);
    }
    jdbc.update(
        """
        INSERT INTO fraudshield.mcc_circuit_breaker_settings_versions (institution_id, version,
          fraud_rate_threshold, window_minutes, minimum_transactions, clean_reset_minutes)
        VALUES (?, 1, 0.0500, 15, 100, 60)
        """,
        institution);
  }

  private void audit(UUID institution, UUID admin) {
    // seq, prev_hash and row_hash are assigned by the hash-chain trigger (ADR 0017).
    jdbc.update(
        """
        INSERT INTO fraudshield.audit_events (institution_id, writer_partition, event_type, action,
          entity_type, entity_id, user_id, user_first_name, user_last_name, user_role, event_at)
        VALUES (?, 0, 'USER_ADMIN', 'DEMO_DATA_SEEDED', 'institution', ?, ?, 'Amani', 'Mukiza',
          'ADMIN', ?)
        """,
        institution,
        institution.toString(),
        admin,
        Timestamp.from(clock.instant()));
  }
}
