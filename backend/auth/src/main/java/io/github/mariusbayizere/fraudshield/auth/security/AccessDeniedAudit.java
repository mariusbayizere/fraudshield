package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyPrincipal;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Records an AUTH/ACCESS_DENIED audit event when an authenticated staff member or API key is
 * refused by the authorisation policy (review finding 5): an attempt to reach an operation outside
 * one's role is a security event, not only a 403. Anonymous refusals carry no institution and are
 * not audited. A failure to write the record is logged and never changes the 403.
 */
public final class AccessDeniedAudit {

  private static final Logger LOG = LoggerFactory.getLogger(AccessDeniedAudit.class);
  private static final int MAX_PATH = 128;

  private final TenantTransactions tenants;
  private final StaffAccountRepository accounts;
  private final AuditLog audit;
  private final Clock clock;

  /**
   * Creates the recorder.
   *
   * @param tenants tenant transactions
   * @param accounts account repository
   * @param audit audit log
   * @param clock clock
   */
  public AccessDeniedAudit(
      TenantTransactions tenants, StaffAccountRepository accounts, AuditLog audit, Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Records a refusal.
   *
   * @param request the refused request
   * @param caller the authenticated caller, or null
   */
  public void record(HttpServletRequest request, Authentication caller) {
    try {
      String operation = request.getMethod() + " " + request.getRequestURI();
      if (operation.length() > MAX_PATH) {
        operation = operation.substring(0, MAX_PATH);
      }
      Map<String, Object> after = Map.of("method", request.getMethod(), "operation", operation);
      if (caller instanceof JwtAuthenticationToken jwt) {
        StaffClaims claims = AccessTokens.claims(jwt.getToken());
        String entity = operation;
        tenants.runInTenant(
            claims.institutionId(),
            () ->
                accounts
                    .findById(claims.userId())
                    .ifPresent(
                        account ->
                            audit.record(
                                AuditEvent.of(
                                        account.institutionId(),
                                        AuditEventType.AUTH,
                                        "ACCESS_DENIED")
                                    .entity("operation", entity)
                                    .actor(SessionService.actor(account))
                                    .after(after)
                                    .context(Requests.context(request))
                                    .at(clock.instant()))));
      } else if (caller instanceof ApiKeyAuthentication key) {
        recordApiKey(key.getPrincipal(), after, request);
      }
    } catch (RuntimeException e) {
      LOG.warn("could not audit a refused request: {}", e.getClass().getSimpleName());
    }
  }

  private void recordApiKey(
      ApiKeyPrincipal principal, Map<String, Object> after, HttpServletRequest request) {
    if (principal == null) {
      return;
    }
    UUID institution = principal.institutionId();
    tenants.runInTenant(
        institution,
        () ->
            audit.record(
                AuditEvent.of(institution, AuditEventType.AUTH, "ACCESS_DENIED")
                    .entity("api_key", principal.keyId())
                    .after(after)
                    .context(Requests.context(request))
                    .at(clock.instant())));
  }
}
