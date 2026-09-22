package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.Clock;
import java.time.Duration;
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
  private final RateLimiter limiter;

  private static final String GUESS_KEY = "password-change:user:";
  private static final int MAX_WRONG_CURRENT_PASSWORDS = 5;
  private static final Duration GUESS_WINDOW = Duration.ofMinutes(15);

  /**
   * Creates the service.
   *
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param hasher password hasher
   * @param sessions session service
   * @param audit audit log
   * @param clock clock
   * @param limiter rate limiter counting wrong current passwords
   */
  public PasswordChangeService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      PasswordHasher hasher,
      SessionService sessions,
      AuditLog audit,
      Clock clock,
      RateLimiter limiter) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
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
    // bcrypt outside the transaction (review finding 4); the write re-checks the hash it compared.
    String storedHash =
        tenants.inTenant(
            claims.institutionId(), () -> accounts.passwordHash(claims.userId()).orElse(null));
    boolean matches = hasher.matches(currentPassword, storedHash);
    boolean changed =
        tenants.inTenant(
            claims.institutionId(),
            () -> {
              StaffAccount account = accounts.findByIdForUpdate(claims.userId()).orElseThrow();
              Instant now = clock.instant();
              boolean valid =
                  matches
                      && Objects.equals(
                          storedHash, accounts.passwordHash(account.id()).orElse(null));
              if (!valid) {
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
      stopGuessing(claims, context);
      throw ProblemException.of(
          "unauthorized", 401, "Authentication failed", "The current password is incorrect");
    }
  }

  /**
   * A stolen access token must not allow unthrottled guessing of the current password (review
   * finding 13). The contract has no 429 for this operation, so the fifth wrong guess within the
   * window ends every session of the account instead, which ends the stolen token too.
   */
  private void stopGuessing(StaffClaims claims, RequestContext context) {
    String key = GUESS_KEY + claims.userId();
    if (limiter.attempt(key, MAX_WRONG_CURRENT_PASSWORDS - 1, GUESS_WINDOW).allowed()) {
      return;
    }
    tenants.runInTenant(
        claims.institutionId(),
        () -> {
          StaffAccount account = accounts.findByIdForUpdate(claims.userId()).orElseThrow();
          sessions.endAllSessions(
              account, "SESSIONS_ENDED_PASSWORD_GUESSING", SessionService.actor(account), context);
        });
  }
}
