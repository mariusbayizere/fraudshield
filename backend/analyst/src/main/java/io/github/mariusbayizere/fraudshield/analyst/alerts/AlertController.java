package io.github.mariusbayizere.fraudshield.analyst.alerts;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The alert feed, an alert's detail and its explanation (FR-04-02 to FR-04-05, FR-04-09). */
@RestController
@Validated
@RequestMapping("/api/v1/alerts")
public class AlertController {

  /** The contract's {@code Token}. */
  static final Pattern TOKEN = Pattern.compile("^tok_[A-Za-z0-9]{24,64}$");

  private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]{0,13})(\\.[0-9]{1,4})?$");
  private static final Set<String> CHANNELS =
      Set.of("MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER");
  private static final Set<String> TIERS = Set.of("HIGH", "MEDIUM", "ANOMALY");
  private static final Set<String> STATUSES =
      Set.of(
          "PENDING",
          "IN_REVIEW",
          "CONFIRMED_FRAUD",
          "MARKED_LEGITIMATE",
          "AUTO_RELEASED",
          "ESCALATED",
          "OVERRIDDEN");

  private static final Map<String, AlertQueries.Queue> QUEUES =
      Map.of(
          "standard", AlertQueries.Queue.STANDARD,
          "anomaly", AlertQueries.Queue.ANOMALY,
          "escalated", AlertQueries.Queue.ESCALATED,
          "all", AlertQueries.Queue.ALL);
  private static final Map<String, AlertQueries.Sort> SORTS =
      Map.of(
          "priority", AlertQueries.Sort.PRIORITY,
          "expected_loss", AlertQueries.Sort.EXPECTED_LOSS,
          "newest", AlertQueries.Sort.NEWEST);

  private final TenantTransactions tenants;
  private final AlertQueries alerts;

  /**
   * Creates the controller.
   *
   * @param tenants tenant transactions
   * @param alerts alert queries
   */
  public AlertController(TenantTransactions tenants, AlertQueries alerts) {
    this.tenants = Objects.requireNonNull(tenants);
    this.alerts = Objects.requireNonNull(alerts);
  }

  /**
   * Lists alerts (ADR 0110 point 2).
   *
   * @param jwt caller
   * @param queue the queue
   * @param transactionId a transaction
   * @param accountId an account token
   * @param amountMin least amount in RWF
   * @param amountMax greatest amount in RWF
   * @param channel channels
   * @param from earliest transaction time, inclusive
   * @param to latest transaction time, exclusive
   * @param tier tiers
   * @param analystId an analyst who is assigned to or decided the entry
   * @param outcome statuses
   * @param sort the order
   * @param cursor cursor
   * @param limit page size
   * @return a page of alerts
   */
  @ContractOperation("listAlerts")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping
  public Map<String, Object> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "standard") String queue,
      @RequestParam(name = "transaction_id", required = false) UUID transactionId,
      @RequestParam(name = "account_id", required = false) String accountId,
      @RequestParam(name = "amount_min", required = false) String amountMin,
      @RequestParam(name = "amount_max", required = false) String amountMax,
      @RequestParam(required = false) List<String> channel,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) List<String> tier,
      @RequestParam(name = "analyst_id", required = false) UUID analystId,
      @RequestParam(required = false) List<String> outcome,
      @RequestParam(defaultValue = "priority") String sort,
      @RequestParam(required = false) @Size(max = 512) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    if (accountId != null && !TOKEN.matcher(accountId).matches()) {
      throw ProblemException.validation("account_id", "not_a_token", "Not an account token");
    }
    AlertQueries.Filter filter =
        new AlertQueries.Filter(
            choice("queue", queue, QUEUES),
            transactionId,
            accountId,
            amount("amount_min", amountMin),
            amount("amount_max", amountMax),
            members("channel", channel, CHANNELS),
            timestamp("from", from),
            timestamp("to", to),
            members("tier", tier, TIERS),
            analystId,
            members("outcome", outcome, STATUSES),
            choice("sort", sort, SORTS));
    StaffClaims claims = AccessTokens.claims(jwt);
    AlertQueries.Caller caller = new AlertQueries.Caller(claims.userId(), claims.role());
    return tenants.inTenant(
        claims.institutionId(), () -> alerts.list(caller, filter, cursor, limit));
  }

  /**
   * One alert, for the investigation drawer.
   *
   * @param jwt caller
   * @param alertId the alert
   * @return the alert
   */
  @ContractOperation("getAlert")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/{alert_id}")
  public Map<String, Object> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("alert_id") UUID alertId) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants
        .inTenant(claims.institutionId(), () -> alerts.detail(alertId))
        .orElseThrow(() -> ProblemException.notFound("alert"));
  }

  /**
   * All 44 contributions of an alert's score (FR-04-05, D-45).
   *
   * @param jwt caller
   * @param alertId the alert
   * @return the explanation
   */
  @ContractOperation("getAlertExplanation")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/{alert_id}/explanation")
  public Map<String, Object> explanation(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("alert_id") UUID alertId) {
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants
        .inTenant(claims.institutionId(), () -> alerts.explanation(alertId))
        .orElseThrow(() -> ProblemException.notFound("explanation"));
  }

  /** One of the contract's enum values, matched exactly as the contract spells it. */
  private static <E> E choice(String field, String value, Map<String, E> values) {
    E choice = values.get(value);
    if (choice == null) {
      throw ProblemException.validation(field, "unsupported_value", "Unsupported value");
    }
    return choice;
  }

  private static List<String> members(String field, List<String> values, Set<String> allowed) {
    if (values == null) {
      return List.of();
    }
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      if (!allowed.contains(value)) {
        throw ProblemException.validation(field, "unsupported_value", "Unsupported value");
      }
      if (!seen.add(value)) {
        throw ProblemException.validation(field, "duplicate_items", "Each value at most once");
      }
    }
    return List.copyOf(values);
  }

  private static BigDecimal amount(String field, String value) {
    if (value == null) {
      return null;
    }
    if (!DECIMAL.matcher(value).matches()) {
      throw ProblemException.validation(field, "invalid_format", "Not a decimal amount");
    }
    return new BigDecimal(value);
  }

  private static Instant timestamp(String field, String value) {
    if (value == null) {
      return null;
    }
    try {
      if (!value.endsWith("Z")) {
        throw new DateTimeParseException("not UTC", value, 0);
      }
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw ProblemException.validation(
          field, "invalid_format", "Use an RFC 3339 UTC timestamp ending in Z");
    }
  }
}
