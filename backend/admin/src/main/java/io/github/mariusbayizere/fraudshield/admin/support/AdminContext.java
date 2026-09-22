package io.github.mariusbayizere.fraudshield.admin.support;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.util.Objects;

/** The administrator behind a request, as recorded in audit events (FR-06-06). */
public final class AdminContext {

  private final StaffAccountRepository accounts;

  /**
   * Creates the resolver.
   *
   * @param accounts account repository
   */
  public AdminContext(StaffAccountRepository accounts) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
  }

  /**
   * The acting administrator's account. Must run in the administrator's tenant transaction.
   *
   * @param claims access-token claims
   * @return the account
   * @throws ProblemException 401 if the account no longer exists or is not active; 403 if it is no
   *     longer an administrator (a demotion within the session-cache window, D-27)
   */
  public StaffAccount account(StaffClaims claims) {
    StaffAccount account =
        accounts.findById(claims.userId()).orElseThrow(ProblemException::unauthorized);
    if (account.status() != AccountStatus.ACTIVE) {
      throw ProblemException.unauthorized();
    }
    if (account.role() != StaffRole.ADMIN) {
      throw ProblemException.forbidden();
    }
    return account;
  }

  /**
   * The acting administrator as an audit actor. Must run in the tenant transaction.
   *
   * @param claims access-token claims
   * @return the actor
   */
  public AuditActor actor(StaffClaims claims) {
    return SessionService.actor(account(claims));
  }
}
