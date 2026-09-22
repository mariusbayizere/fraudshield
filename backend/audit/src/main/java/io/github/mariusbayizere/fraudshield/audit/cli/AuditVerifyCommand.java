package io.github.mariusbayizere.fraudshield.audit.cli;

import io.github.mariusbayizere.fraudshield.audit.anchor.AnchorKeys;
import io.github.mariusbayizere.fraudshield.audit.anchor.AuditChainVerifier;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code fraudshield audit verify --from <date> --to <date>} (D-32).
 *
 * <p>Connection and keys come from the environment, never from arguments, so no secret appears in a
 * process listing:
 *
 * <ul>
 *   <li>{@code FRAUDSHIELD_DB_URL}: JDBC URL of the database (or of the compliance replica);
 *   <li>{@code FRAUDSHIELD_DB_COMPLIANCE_USER} (default {@code fs_compliance_ro}) and {@code
 *       FRAUDSHIELD_DB_COMPLIANCE_PASSWORD};
 *   <li>{@code FRAUDSHIELD_AUDIT_ANCHOR_PUBLIC_KEYS}: {@code keyId=path.pem} pairs separated by
 *       commas.
 * </ul>
 *
 * <p>Exit codes: 0 verified, 1 tampering or a verification problem found, 2 usage or configuration
 * error.
 */
public final class AuditVerifyCommand {

  static final int VERIFIED = 0;
  static final int PROBLEMS_FOUND = 1;
  static final int USAGE_ERROR = 2;
  private static final String USAGE =
      "usage: fraudshield audit verify --from YYYY-MM-DD --to YYYY-MM-DD";

  private final Map<String, String> environment;
  private final java.time.Clock clock;
  private final Consumer<String> out;
  private final Consumer<String> err;

  /**
   * Creates the command.
   *
   * @param environment environment variables
   * @param out receives each line of standard output
   * @param err receives each line of standard error
   */
  public AuditVerifyCommand(
      Map<String, String> environment, Consumer<String> out, Consumer<String> err) {
    this(environment, out, err, java.time.Clock.systemUTC());
  }

  /**
   * Creates the command with a clock (tests).
   *
   * @param environment environment variables
   * @param out receives each line of standard output
   * @param err receives each line of standard error
   * @param clock clock deciding which days must already be anchored
   */
  public AuditVerifyCommand(
      Map<String, String> environment,
      Consumer<String> out,
      Consumer<String> err,
      java.time.Clock clock) {
    this.clock = clock;
    this.environment = Map.copyOf(environment);
    this.out = out;
    this.err = err;
  }

  /**
   * Runs the command.
   *
   * @param args command-line arguments
   * @return the exit code
   */
  public int run(String... args) {
    List<String> arguments = List.of(args);
    if (arguments.size() != 6
        || !arguments.get(0).equals("audit")
        || !arguments.get(1).equals("verify")) {
      err.accept(USAGE);
      return USAGE_ERROR;
    }
    Map<String, String> options = new HashMap<>();
    for (int i = 2; i < arguments.size(); i += 2) {
      options.put(arguments.get(i), arguments.get(i + 1));
    }
    LocalDate from;
    LocalDate to;
    try {
      from = LocalDate.parse(required(options, "--from"));
      to = LocalDate.parse(required(options, "--to"));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      err.accept(USAGE);
      return USAGE_ERROR;
    }
    if (to.isBefore(from)) {
      err.accept("--to is before --from");
      return USAGE_ERROR;
    }
    AuditChainVerifier verifier;
    try {
      verifier = verifier();
    } catch (IllegalStateException e) {
      err.accept("configuration error: " + e.getMessage());
      return USAGE_ERROR;
    }
    AuditChainVerifier.Report report = verifier.verify(from, to);
    out.accept(
        String.format(
            "audit verify %s..%s: %d rows re-hashed, %d anchor signatures checked,"
                + " %d anchors recomputed",
            from, to, report.rowsChecked(), report.anchorsChecked(), report.anchorsRecomputed()));
    for (AuditChainVerifier.Problem problem : report.problems()) {
      out.accept(
          String.format(
              "TAMPERING OR ERROR partition=%d seq=%s: %s",
              problem.partition(),
              problem.seq() == null ? "-" : problem.seq(),
              problem.description()));
    }
    out.accept(report.verified() ? "VERIFIED" : "FAILED");
    return report.verified() ? VERIFIED : PROBLEMS_FOUND;
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException(name);
    }
    return value;
  }

  private AuditChainVerifier verifier() {
    String url = environment.get("FRAUDSHIELD_DB_URL");
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("FRAUDSHIELD_DB_URL is not set");
    }
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            url,
            environment.getOrDefault("FRAUDSHIELD_DB_COMPLIANCE_USER", "fs_compliance_ro"),
            environment.getOrDefault("FRAUDSHIELD_DB_COMPLIANCE_PASSWORD", ""));
    return new AuditChainVerifier(
        new JdbcTemplate(dataSource),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        publicKeys(environment.getOrDefault("FRAUDSHIELD_AUDIT_ANCHOR_PUBLIC_KEYS", "")),
        clock);
  }

  static Map<String, PublicKey> publicKeys(String spec) {
    Map<String, PublicKey> keys = new HashMap<>();
    for (String entry : spec.split(",")) {
      if (entry.isBlank()) {
        continue;
      }
      int equals = entry.indexOf('=');
      if (equals <= 0) {
        throw new IllegalStateException(
            "FRAUDSHIELD_AUDIT_ANCHOR_PUBLIC_KEYS entries are keyId=path");
      }
      keys.put(
          entry.substring(0, equals).trim(),
          AnchorKeys.publicKey(Path.of(entry.substring(equals + 1).trim())));
    }
    return keys;
  }
}
