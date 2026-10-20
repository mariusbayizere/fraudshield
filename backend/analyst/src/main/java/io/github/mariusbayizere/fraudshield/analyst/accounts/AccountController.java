package io.github.mariusbayizere.fraudshield.analyst.accounts;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Objects;
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

/** The account's context in the investigation drawer (FR-04-06 to FR-04-08). */
@RestController
@Validated
@RequestMapping("/api/v1/accounts/{account_token}")
public class AccountController {

  private static final Pattern TOKEN = Pattern.compile("^tok_[A-Za-z0-9]{24,64}$");

  private final TenantTransactions tenants;
  private final AccountQueries accounts;

  /**
   * Creates the controller.
   *
   * @param tenants tenant transactions
   * @param accounts account queries
   */
  public AccountController(TenantTransactions tenants, AccountQueries accounts) {
    this.tenants = Objects.requireNonNull(tenants);
    this.accounts = Objects.requireNonNull(accounts);
  }

  /**
   * The account's history, most recent first, 30 per page (FR-04-06).
   *
   * @param jwt caller
   * @param accountToken the account
   * @param cursor cursor
   * @param limit page size
   * @return a page of transactions
   */
  @ContractOperation("listAccountTransactions")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/transactions")
  public Map<String, Object> transactions(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("account_token") String accountToken,
      @RequestParam(required = false) @Size(max = 512) String cursor,
      @RequestParam(defaultValue = "30") @Min(1) @Max(30) int limit) {
    String account = token(accountToken);
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants
        .inTenant(claims.institutionId(), () -> accounts.history(account, cursor, limit))
        .orElseThrow(() -> ProblemException.notFound("account"));
  }

  /**
   * The behavioural fingerprint against one transaction (FR-04-07).
   *
   * @param jwt caller
   * @param accountToken the account
   * @param transactionId the compared transaction
   * @return the profile
   */
  @ContractOperation("getBehaviourProfile")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/behaviour-profile")
  public Map<String, Object> profile(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("account_token") String accountToken,
      @RequestParam("transaction_id") UUID transactionId) {
    String account = token(accountToken);
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants
        .inTenant(claims.institutionId(), () -> accounts.profile(account, transactionId))
        .orElseThrow(() -> ProblemException.notFound("transaction of this account"));
  }

  /**
   * The counterparty network up to the second degree (FR-04-08).
   *
   * @param jwt caller
   * @param accountToken the account
   * @return the network
   */
  @ContractOperation("getAccountNetwork")
  @PreAuthorize("hasAnyRole('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')")
  @GetMapping("/network")
  public Map<String, Object> network(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("account_token") String accountToken) {
    String account = token(accountToken);
    StaffClaims claims = AccessTokens.claims(jwt);
    return tenants
        .inTenant(claims.institutionId(), () -> accounts.network(account))
        .orElseThrow(() -> ProblemException.notFound("account"));
  }

  private static String token(String value) {
    if (!TOKEN.matcher(value).matches()) {
      throw ProblemException.validation("account_token", "not_a_token", "Not an account token");
    }
    return value;
  }
}
