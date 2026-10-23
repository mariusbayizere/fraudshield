package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.crypto.SignedToken;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Unlocks an account with the emailed single-use link (FR-07-06, D-26). */
public final class AccountUnlockService {

  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final SignedToken tokens;
  private final AuditLog audit;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param tokens single-use token codec
   * @param audit audit log
   * @param clock clock
   */
  public AccountUnlockService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      SignedToken tokens,
      AuditLog audit,
      Clock clock) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Unlocks the account the token was issued for.
   *
   * @param token the token from the link
   * @param context request context
   * @throws ProblemException 410 if the token is invalid, expired, used, or the lock has already
   *     ended; the message does not say which
   */
  public void unlock(String token, RequestContext context) {
    Instant now = clock.instant();
    SignedToken.Claims claims =
        tokens
            .verify(token, SignedToken.Purpose.ACCOUNT_UNLOCK, now)
            .orElseThrow(AccountUnlockService::gone);
    Optional<java.util.UUID> institution = accounts.findInstitution(claims.userId());
    if (institution.isEmpty()) {
      throw gone();
    }
    boolean unlocked =
        tenants.inTenant(
            institution.get(),
            () -> {
              StaffAccount account = accounts.findByIdForUpdate(claims.userId()).orElse(null);
              if (account == null
                  || account.status() != AccountStatus.LOCKED
                  || account.lockedUntil() == null
                  || !claims.boundTo(
                      AccountTokens.unlockState(account.id(), account.lockedUntil()))) {
                return false;
              }
              accounts.unlock(account.id());
              audit.record(
                  AuditEvent.of(account.institutionId(), AuditEventType.AUTH, "ACCOUNT_UNLOCKED")
                      .entity("user", account.id())
                      .actor(SessionService.actor(account))
                      .after(java.util.Map.of("method", "EMAIL_LINK"))
                      .context(context)
                      .at(now));
              return true;
            });
    if (!unlocked) {
      throw gone();
    }
  }

  static ProblemException gone() {
    return ProblemException.of(
        "gone", 410, "Link no longer valid", "This link has expired or has already been used");
  }
}
