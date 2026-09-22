package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Password change while signed in (FR-07-09): the current password must match, and every session of
 * the account ends, including the one that made the change. An old access token is refused within
 * the session-cache bound (D-27).
 */
public final class PasswordChangeService {

  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final PasswordHasher hasher;
  private final SessionService sessions;
  private final AuditLog audit;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param hasher password hasher
   * @param sessions session service
   * @param audit audit log
   * @param clock clock
   */
  public PasswordChangeService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      PasswordHasher hasher,
      SessionService sessions,
      AuditLog audit,
      Clock clock) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Changes the caller's password.
   *
   * @param claims caller
   * @param currentPassword current password (over 72 bytes is answered 401, ADR 0014)
   * @param newPassword new password (already validated against the policy)
   * @param context request context
   * @throws ProblemException 401 if the current password does not match
   */
  public void change(
      StaffClaims claims, String currentPassword, String newPassword, RequestContext context) {
    String newHash = hasher.hash(newPassword);
    boolean changed =
        tenants.inTenant(
            claims.institutionId(),
            () -> {
              StaffAccount account = accounts.findByIdForUpdate(claims.userId()).orElseThrow();
              Instant now = clock.instant();
              if (!hasher.matches(
                  currentPassword, accounts.passwordHash(account.id()).orElse(null))) {
                audit.record(
                    AuditEvent.of(
                            account.institutionId(), AuditEventType.AUTH, "PASSWORD_CHANGE_REFUSED")
                        .entity("user", account.id())
                        .actor(SessionService.actor(account))
                        .context(context)
                        .at(now));
                return false;
              }
              long version = accounts.changePassword(account.id(), newHash);
              sessions.revokeAfterVersionChange(account.id(), version);
              audit.record(
                  AuditEvent.of(account.institutionId(), AuditEventType.AUTH, "PASSWORD_CHANGED")
                      .entity("user", account.id())
                      .actor(SessionService.actor(account))
                      .before(Map.of("token_version", account.tokenVersion()))
                      .after(Map.of("token_version", version))
                      .context(context)
                      .at(now));
              return true;
            });
    if (!changed) {
      throw ProblemException.of(
          "unauthorized", 401, "Authentication failed", "The current password is incorrect");
    }
  }
}
