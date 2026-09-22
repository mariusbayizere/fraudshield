package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.config.AuthProperties;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.crypto.SignedToken;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.auth.session.IssuedSession;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Email and password sign-in (FR-07-06, FR-07-07, D-26, ADR 0014 decision 8).
 *
 * <ul>
 *   <li>Rate limits come first, before any credential work (429 with Retry-After).
 *   <li>A wrong password is 401 whatever the account's state; correct credentials for a
 *       PENDING_APPROVAL or DEACTIVATED account are 403 {@code account-not-active}.
 *   <li>The fifth consecutive failure locks an ACTIVE account for 30 minutes, answers 423 and
 *       emails an unlock link. The account row is locked for the attempt, so concurrent attempts
 *       count exactly.
 *   <li>Unknown emails and inactive accounts get the same 401-then-423 behaviour from a counter in
 *       the rate limiter, and every path runs one bcrypt, so neither the status codes nor the
 *       timing reveal whether an account exists.
 * </ul>
 */
public final class AuthenticationService {

  private static final String PHANTOM_KEY = "login:phantom:";

  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final RateLimiter limiter;
  private final SessionService sessions;
  private final AuditLog audit;
  private final StaffMailer mailer;
  private final SignedToken tokens;
  private final Clock clock;
  private final AuthProperties.Lockout lockout;
  private final String consoleBaseUrl;

  /**
   * Creates the service.
   *
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param hasher password hasher
   * @param throttle request limits
   * @param limiter rate limiter (for the unknown-account counter)
   * @param sessions session service
   * @param audit audit log
   * @param mailer staff mailer
   * @param tokens single-use token codec
   * @param clock clock
   * @param lockout lockout settings
   * @param consoleBaseUrl console base URL, for the unlock link
   */
  public AuthenticationService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      PasswordHasher hasher,
      LoginThrottle throttle,
      RateLimiter limiter,
      SessionService sessions,
      AuditLog audit,
      StaffMailer mailer,
      SignedToken tokens,
      Clock clock,
      AuthProperties.Lockout lockout,
      String consoleBaseUrl) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.mailer = Objects.requireNonNull(mailer, "mailer");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lockout = Objects.requireNonNull(lockout, "lockout");
    this.consoleBaseUrl = Objects.requireNonNull(consoleBaseUrl, "consoleBaseUrl");
  }

  private enum Result {
    SUCCESS,
    INVALID,
    LOCKED,
    NOT_ACTIVE,
    /**
     * Wrong password for a PENDING_APPROVAL or DEACTIVATED account: counted like an unknown email.
     */
    INACTIVE_FAILURE
  }

  private record Outcome(Result result, IssuedSession session) {}

  /**
   * Signs in.
   *
   * @param email submitted email
   * @param password submitted password (up to 1,024 characters; over 72 bytes never matches)
   * @param context request context
   * @return the session
   * @throws ProblemException 401, 403, 423 or 429
   */
  public IssuedSession login(String email, String password, RequestContext context) {
    String normalised = email.toLowerCase(Locale.ROOT);
    Optional<StaffAccountRepository.AccountRef> ref = accounts.findRefByEmail(normalised);
    throttle.login(
        context.ipAddress(),
        normalised,
        ref.map(StaffAccountRepository.AccountRef::institutionId).orElse(null));
    if (ref.isEmpty()) {
      hasher.burn();
      throw phantomFailure(normalised);
    }
    UUID institution = ref.get().institutionId();
    UUID userId = ref.get().userId();
    // bcrypt runs outside any transaction: no row lock or audit-chain position is held for its
    // ~250 ms, and a transaction retried for a chain serialization failure does not repeat it
    // (review finding 4).
    String storedHash =
        tenants.inTenant(institution, () -> accounts.passwordHash(userId).orElse(null));
    boolean matches = hasher.matches(password, storedHash);
    Outcome outcome =
        tenants.inTenant(institution, () -> attempt(userId, storedHash, matches, context));
    if (outcome.result() == Result.INACTIVE_FAILURE) {
      throw phantomFailure(normalised);
    }
    return switch (outcome.result()) {
      case SUCCESS -> outcome.session();
      case LOCKED -> throw locked();
      case NOT_ACTIVE ->
          throw ProblemException.of(
              "account-not-active",
              403,
              "Account not active",
              "This account is awaiting approval or has been deactivated");
      default -> throw invalidCredentials();
    };
  }

  /**
   * Applies a password check made outside the transaction. If the password changed in between, the
   * check no longer counts: the attempt is treated as invalid.
   */
  private Outcome attempt(
      UUID userId, String checkedHash, boolean matches, RequestContext context) {
    StaffAccount account = accounts.findByIdForUpdate(userId).orElseThrow();
    Instant now = clock.instant();
    boolean valid =
        matches && Objects.equals(checkedHash, accounts.passwordHash(userId).orElse(null));
    if (account.status() == AccountStatus.LOCKED) {
      if (!account.lockExpired(now)) {
        record(account, "LOGIN_REFUSED_LOCKED", Map.of(), context, now);
        return new Outcome(Result.LOCKED, null);
      }
      accounts.unlock(account.id());
      record(account, "ACCOUNT_AUTO_UNLOCKED", Map.of(), context, now);
      account = accounts.findByIdForUpdate(userId).orElseThrow();
    }
    if (account.status() != AccountStatus.ACTIVE) {
      if (valid) {
        record(
            account,
            "LOGIN_REFUSED_NOT_ACTIVE",
            Map.of("status", account.status().name()),
            context,
            now);
        return new Outcome(Result.NOT_ACTIVE, null);
      }
      record(account, "LOGIN_FAILED", Map.of("status", account.status().name()), context, now);
      return new Outcome(Result.INACTIVE_FAILURE, null);
    }
    if (valid) {
      accounts.recordLoginSuccess(account.id(), now);
      StaffAccount signedIn = accounts.findById(account.id()).orElseThrow();
      IssuedSession session = sessions.start(signedIn, context, null);
      record(
          signedIn,
          "LOGIN_SUCCEEDED",
          Map.of("session_id", session.sessionId().toString()),
          context,
          now);
      return new Outcome(Result.SUCCESS, session);
    }
    int failures = accounts.recordLoginFailure(account.id());
    record(account, "LOGIN_FAILED", Map.of("failed_login_count", failures), context, now);
    if (failures < lockout.maxFailures()) {
      return new Outcome(Result.INVALID, null);
    }
    lock(account, failures, context, now);
    return new Outcome(Result.LOCKED, null);
  }

  private void lock(StaffAccount account, int failures, RequestContext context, Instant now) {
    Instant until = now.plus(lockout.lockDuration()).truncatedTo(ChronoUnit.MICROS);
    accounts.lock(account.id(), until);
    record(
        account,
        "ACCOUNT_LOCKED",
        Map.of("failed_login_count", failures, "locked_until", until.toString()),
        context,
        now);
    String token =
        tokens.issue(
            SignedToken.Purpose.ACCOUNT_UNLOCK,
            account.id(),
            now.plus(lockout.unlockTokenTtl()),
            AccountTokens.unlockState(account.id(), until));
    StaffMailer.Message message =
        new StaffMailer.Message(
            account.email(),
            account.preferredLocale(),
            StaffMailer.Template.ACCOUNT_LOCKED,
            account.firstName(),
            consoleBaseUrl + "/unlock#token=" + token);
    AfterCommit.run(() -> mailer.send(message));
  }

  private void record(
      StaffAccount account,
      String action,
      Map<String, Object> after,
      RequestContext context,
      Instant now) {
    audit.record(
        AuditEvent.of(account.institutionId(), AuditEventType.AUTH, action)
            .entity("user", account.id())
            .actor(SessionService.actor(account))
            .after(after.isEmpty() ? null : after)
            .context(context)
            .at(now));
  }

  /** Counts a failure for an email with no usable account; false once it would have locked. */
  private boolean phantomCounted(String email) {
    String key = PHANTOM_KEY + Crypto.base64url(Crypto.sha256(email)).substring(0, 22);
    return limiter.attempt(key, lockout.maxFailures() - 1, lockout.lockDuration()).allowed();
  }

  private ProblemException phantomFailure(String email) {
    return phantomCounted(email) ? invalidCredentials() : locked();
  }

  private static ProblemException invalidCredentials() {
    return ProblemException.of(
        "unauthorized", 401, "Authentication failed", "The email or password is incorrect");
  }

  private static ProblemException locked() {
    return ProblemException.of(
        "account-locked",
        423,
        "Account locked",
        "The account is locked after repeated failed sign-ins; use the emailed link, ask an"
            + " administrator, or try again after 30 minutes");
  }
}
