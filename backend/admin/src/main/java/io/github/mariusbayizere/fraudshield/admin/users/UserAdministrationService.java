package io.github.mariusbayizere.fraudshield.admin.users;

import io.github.mariusbayizere.fraudshield.admin.support.AdminContext;
import io.github.mariusbayizere.fraudshield.admin.support.Cursor;
import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordHasher;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.PasswordPolicy;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.StaffUserView;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import io.github.mariusbayizere.fraudshield.common.identity.PersonName;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * Staff account administration (FR-06-01, FR-06-02, D-23, D-24, ADR 0014 decision 5).
 *
 * <ul>
 *   <li>Every operation runs in the administrator's institution; another institution's accounts are
 *       not found (row-level security).
 *   <li>A role or status change increments the token version and revokes every refresh token, so
 *       the account's sessions end within the session-cache bound (FR-06-02: under 5 s).
 *   <li>An administrator cannot change their own role or status (403 {@code self-modification},
 *       FR-07-01), and the last ACTIVE ADMIN cannot be demoted, locked or deactivated (409 {@code
 *       last-active-admin}).
 *   <li>Every write records a USER_ADMIN audit event with before and after values.
 * </ul>
 */
public final class UserAdministrationService {

  private static final int TEMPORARY_PASSWORD_LENGTH = 16;
  private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
  private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
  private static final String DIGITS = "23456789";
  private static final String SPECIAL = "!#%+-=?@";

  private final TenantTransactions tenants;
  private final StaffAccountRepository accounts;
  private final AdminContext admins;
  private final PasswordHasher hasher;
  private final SessionService sessions;
  private final StaffMailer mailer;
  private final AuditLog audit;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param tenants tenant transactions
   * @param accounts account repository
   * @param admins acting-administrator resolver
   * @param hasher password hasher
   * @param sessions session service
   * @param mailer staff mailer
   * @param audit audit log
   * @param clock clock
   */
  public UserAdministrationService(
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      AdminContext admins,
      PasswordHasher hasher,
      SessionService sessions,
      StaffMailer mailer,
      AuditLog audit,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.admins = Objects.requireNonNull(admins, "admins");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.mailer = Objects.requireNonNull(mailer, "mailer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * A page of accounts.
   *
   * @param items accounts
   * @param nextCursor cursor of the next page, or null
   */
  public record Page(List<StaffAccount> items, String nextCursor) {

    /** Copies the items. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * Lists accounts of the administrator's institution, newest first.
   *
   * @param admin caller
   * @param status filter or null
   * @param role filter or null
   * @param cursor cursor or null
   * @param limit page size, 1-200
   * @return the page
   */
  public Page list(
      StaffClaims admin, AccountStatus status, StaffRole role, String cursor, int limit) {
    Cursor after = Cursor.decode(cursor);
    return tenants.inTenant(
        admin.institutionId(),
        () -> {
          List<StaffAccount> rows =
              accounts.page(
                  status,
                  role,
                  after == null
                      ? null
                      : new StaffAccountRepository.Position(after.at(), after.id()),
                  limit);
          if (rows.size() <= limit) {
            return new Page(rows, null);
          }
          StaffAccount last = rows.get(limit - 1);
          return new Page(rows.subList(0, limit), new Cursor(last.createdAt(), last.id()).encode());
        });
  }

  /**
   * One account.
   *
   * @param admin caller
   * @param userId account
   * @return the account
   */
  public StaffAccount get(StaffClaims admin, UUID userId) {
    return tenants.inTenant(
        admin.institutionId(),
        () ->
            accounts.findById(userId).orElseThrow(() -> ProblemException.notFound("The account")));
  }

  /**
   * Creates an ACTIVE account and emails a temporary password (FR-06-01).
   *
   * @param admin caller
   * @param request validated request
   * @param context request context
   * @return the account
   */
  public StaffAccount create(
      StaffClaims admin, UserRequests.Create request, RequestContext context) {
    String temporaryPassword = temporaryPassword();
    String hash = hasher.hash(temporaryPassword);
    String email = request.email().toLowerCase(Locale.ROOT);
    try {
      return tenants.inTenant(
          admin.institutionId(),
          () -> {
            AuditActor actor = admins.actor(admin);
            if (accounts.findRefByEmail(email).isPresent()
                || accounts.employeeIdTaken(request.employeeId())) {
              throw duplicate();
            }
            Instant now = clock.instant();
            StaffAccount account =
                new StaffAccount(
                    UUID.randomUUID(),
                    admin.institutionId(),
                    PersonName.parse(request.firstName()).value(),
                    PersonName.parse(request.lastName()).value(),
                    email,
                    request.phone(),
                    StaffRole.valueOf(request.role()),
                    null,
                    AccountStatus.ACTIVE,
                    null,
                    0,
                    0,
                    false,
                    StaffLocale.valueOf(request.preferredLocale()),
                    null,
                    false,
                    request.employeeId(),
                    Department.valueOf(request.department()),
                    null,
                    now,
                    0);
            accounts.insert(account, hash, null);
            StaffAccount created = accounts.findById(account.id()).orElseThrow();
            audit.record(
                AuditEvent.of(admin.institutionId(), AuditEventType.USER_ADMIN, "USER_CREATED")
                    .entity("user", created.id())
                    .actor(actor)
                    .after(describe(created))
                    .context(context)
                    .at(now));
            StaffMailer.Message welcome =
                new StaffMailer.Message(
                    created.email(),
                    created.preferredLocale(),
                    StaffMailer.Template.WELCOME,
                    created.firstName(),
                    temporaryPassword);
            AfterCommit.run(() -> mailer.send(welcome));
            return created;
          });
    } catch (DuplicateKeyException e) {
      throw duplicate();
    }
  }

  private static ProblemException duplicate() {
    return ProblemException.of(
        "conflict", 409, "Conflict", "An account with this email or employee ID already exists");
  }

  /**
   * Applies an administrator's edit (FR-06-02).
   *
   * @param admin caller
   * @param userId account
   * @param request validated request with at least one change
   * @param context request context
   * @return the account after the edit
   */
  public StaffAccount update(
      StaffClaims admin, UUID userId, UserRequests.Update request, RequestContext context) {
    return tenants
        .<Outcome>inTenant(
            admin.institutionId(),
            () -> {
              // A role or status change may remove an administrator: lock the institution's active
              // administrators first, in ID order, before any other row, so concurrent edits
              // serialise
              // without deadlocking and the count used below is exact.
              boolean mayRemoveAdmin = request.role() != null || request.status() != null;
              final long otherActiveAdmins =
                  mayRemoveAdmin ? accounts.otherActiveAdmins(userId) : -1;
              // Resolved before the target is locked: the administrator check must pass first.
              final AuditActor actor = admins.actor(admin);
              StaffAccount current =
                  accounts
                      .findByIdForUpdate(userId)
                      .orElseThrow(() -> ProblemException.notFound("The account"));
              if (current.version() != request.version()) {
                throw ProblemException.of(
                        "conflict", 409, "Conflict", "The account changed since you loaded it")
                    .with("current", StaffUserView.of(current))
                    .with("version", current.version());
              }
              StaffRole role =
                  request.role() == null ? current.role() : StaffRole.valueOf(request.role());
              AccountStatus status =
                  request.status() == null
                      ? current.status()
                      : AccountStatus.valueOf(request.status());
              boolean roleChanged = role != current.role();
              boolean statusChanged = status != current.status();
              // Only an explicit LOCKED in the request converts a failure lock (re-review N1).
              final boolean adminLockRequested =
                  request.status() != null
                      && status == AccountStatus.LOCKED
                      && !StaffAccountRepository.ADMIN_LOCK_UNTIL.equals(current.lockedUntil());
              if ((roleChanged || statusChanged) && userId.equals(admin.userId())) {
                return refused(
                    actor,
                    current,
                    context,
                    ProblemException.of(
                        "self-modification",
                        403,
                        "Forbidden",
                        "Administrators cannot change their own role or status"));
              }
              if (statusChanged && current.status() == AccountStatus.PENDING_APPROVAL) {
                return refused(
                    actor,
                    current,
                    context,
                    ProblemException.of(
                        "conflict",
                        409,
                        "Conflict",
                        "A pending account is approved or rejected through approvals"));
              }
              boolean leavesActiveAdmin =
                  current.role() == StaffRole.ADMIN
                      && current.status() == AccountStatus.ACTIVE
                      && (role != StaffRole.ADMIN || status != AccountStatus.ACTIVE);
              if (leavesActiveAdmin && otherActiveAdmins == 0) {
                return refused(
                    actor,
                    current,
                    context,
                    ProblemException.of(
                        "last-active-admin",
                        409,
                        "Conflict",
                        "The institution's last active administrator cannot be demoted, locked or"
                            + " deactivated"));
              }
              // Locking a failure-locked account turns it into an administrator's lock, which never
              // lifts by itself and ends the sessions (review finding 12).
              Instant lockedUntil;
              if (status != AccountStatus.LOCKED) {
                lockedUntil = null;
              } else if (statusChanged || adminLockRequested) {
                lockedUntil = StaffAccountRepository.ADMIN_LOCK_UNTIL;
              } else {
                lockedUntil = current.lockedUntil(); // unchanged lock (re-review N1)
              }
              boolean endsSessions = roleChanged || statusChanged || adminLockRequested;
              accounts.applyAdminEdit(
                  userId,
                  new StaffAccountRepository.AdminEdit(
                      request.firstName() == null
                          ? current.firstName()
                          : PersonName.parse(request.firstName()).value(),
                      request.lastName() == null
                          ? current.lastName()
                          : PersonName.parse(request.lastName()).value(),
                      request.phone() == null ? current.phone() : request.phone(),
                      request.department() == null
                          ? current.department()
                          : Department.valueOf(request.department()),
                      role,
                      status,
                      request.preferredLocale() == null
                          ? current.preferredLocale()
                          : StaffLocale.valueOf(request.preferredLocale()),
                      lockedUntil,
                      endsSessions));
              StaffAccount updated = accounts.findById(userId).orElseThrow();
              if (endsSessions) {
                sessions.revokeAfterVersionChange(userId, updated.tokenVersion());
              }
              audit.record(
                  AuditEvent.of(
                          admin.institutionId(),
                          AuditEventType.USER_ADMIN,
                          action(roleChanged, statusChanged || adminLockRequested, status))
                      .entity("user", userId)
                      .actor(actor)
                      .before(describe(current))
                      .after(describe(updated))
                      .context(context)
                      .at(clock.instant()));
              return new Outcome(updated, null);
            })
        .orThrow();
  }

  /**
   * The result of an administrator's write: the account, or a refusal whose audit record commits
   * with the transaction and which is thrown only afterwards (review finding 5).
   */
  private record Outcome(StaffAccount account, ProblemException refusal) {

    StaffAccount orThrow() {
      if (refusal != null) {
        throw refusal;
      }
      return account;
    }
  }

  private Outcome refused(
      AuditActor actor, StaffAccount target, RequestContext context, ProblemException refusal) {
    audit.record(
        AuditEvent.of(target.institutionId(), AuditEventType.USER_ADMIN, "USER_CHANGE_REFUSED")
            .entity("user", target.id())
            .actor(actor)
            .after(Map.of("problem", refusal.type(), "detail", refusal.getMessage()))
            .context(context)
            .at(clock.instant()));
    return new Outcome(null, refusal);
  }

  private static String action(boolean roleChanged, boolean statusChanged, AccountStatus status) {
    if (statusChanged) {
      return switch (status) {
        case DEACTIVATED -> "USER_DEACTIVATED";
        case LOCKED -> "USER_LOCKED";
        case ACTIVE -> "USER_REACTIVATED";
        case PENDING_APPROVAL -> "USER_UPDATED";
      };
    }
    return roleChanged ? "USER_ROLE_CHANGED" : "USER_UPDATED";
  }

  /**
   * Accounts awaiting approval.
   *
   * @param admin caller
   * @return the accounts, oldest first
   */
  public List<StaffAccount> pending(StaffClaims admin) {
    return tenants.inTenant(admin.institutionId(), accounts::pendingApproval);
  }

  /**
   * Approves a pending account with a granted role, or rejects it (D-23, D-24). A self-registered
   * account must have verified its email first.
   *
   * @param admin caller
   * @param userId account
   * @param decision validated decision
   * @param context request context
   * @return the account after the decision
   */
  public StaffAccount decide(
      StaffClaims admin,
      UUID userId,
      UserRequests.ApprovalDecision decision,
      RequestContext context) {
    boolean approve =
        UserRequests.Decision.valueOf(decision.decision()) == UserRequests.Decision.APPROVE;
    if (approve && decision.grantedRole() == null) {
      throw ProblemException.validation(
          "granted_role", "required", "A granted role is required to approve");
    }
    return tenants
        .<Outcome>inTenant(
            admin.institutionId(),
            () -> {
              final AuditActor actor = admins.actor(admin);
              StaffAccount current =
                  accounts
                      .findByIdForUpdate(userId)
                      .orElseThrow(() -> ProblemException.notFound("The account"));
              if (current.status() != AccountStatus.PENDING_APPROVAL) {
                throw ProblemException.of(
                    "conflict", 409, "Conflict", "The account is not awaiting approval");
              }
              if (approve && !current.emailVerified()) {
                return refused(
                    actor,
                    current,
                    context,
                    ProblemException.of(
                        "conflict",
                        409,
                        "Conflict",
                        "The account's email address has not been verified"));
              }
              StaffRole role = approve ? StaffRole.valueOf(decision.grantedRole()) : current.role();
              AccountStatus status = approve ? AccountStatus.ACTIVE : AccountStatus.DEACTIVATED;
              accounts.applyAdminEdit(
                  userId,
                  new StaffAccountRepository.AdminEdit(
                      current.firstName(),
                      current.lastName(),
                      current.phone(),
                      current.department(),
                      role,
                      status,
                      current.preferredLocale(),
                      null,
                      true));
              StaffAccount updated = accounts.findById(userId).orElseThrow();
              sessions.revokeAfterVersionChange(userId, updated.tokenVersion());
              Map<String, Object> after = describe(updated);
              after.put("reason", decision.reason());
              audit.record(
                  AuditEvent.of(
                          admin.institutionId(),
                          AuditEventType.USER_ADMIN,
                          approve ? "USER_APPROVED" : "USER_REJECTED")
                      .entity("user", userId)
                      .actor(actor)
                      .before(describe(current))
                      .after(after)
                      .context(context)
                      .at(clock.instant()));
              return new Outcome(updated, null);
            })
        .orThrow();
  }

  /**
   * The audited values of an account: profile, role and status, never credentials.
   *
   * @param account the account
   * @return the values
   */
  static Map<String, Object> describe(StaffAccount account) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("first_name", account.firstName());
    values.put("last_name", account.lastName());
    values.put("email", account.email());
    values.put("phone", account.phone());
    values.put("employee_id", account.employeeId());
    values.put("department", account.department().name());
    values.put("role", account.role().name());
    values.put(
        "requested_role", account.requestedRole() == null ? null : account.requestedRole().name());
    values.put("status", account.status().name());
    values.put("preferred_locale", account.preferredLocale().name());
    values.put("token_version", account.tokenVersion());
    values.put("version", account.version());
    return values;
  }

  /**
   * A temporary password meeting the policy: 16 characters from unambiguous upper, lower, digit and
   * special sets, at least one of each (about 94 bits).
   *
   * @return the password
   */
  static String temporaryPassword() {
    String all = UPPER + LOWER + DIGITS + SPECIAL;
    char[] password = new char[TEMPORARY_PASSWORD_LENGTH];
    String[] required = {UPPER, LOWER, DIGITS, SPECIAL};
    for (int i = 0; i < password.length; i++) {
      String set = i < required.length ? required[i] : all;
      password[i] = set.charAt(Crypto.randomInt(set.length()));
    }
    for (int i = password.length - 1; i > 0; i--) {
      int j = Crypto.randomInt(i + 1);
      char swap = password[i];
      password[i] = password[j];
      password[j] = swap;
    }
    String result = new String(password);
    if (PasswordPolicy.check(result).isPresent()) {
      throw new IllegalStateException("temporary password generator broke the policy");
    }
    return result;
  }
}
