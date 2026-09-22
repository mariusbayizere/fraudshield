package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.Department;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleIdTokenVerifier;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleIdentity;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleIdentityClient;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleTokenVault;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleTokens;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleUnavailableException;
import io.github.mariusbayizere.fraudshield.auth.session.IssuedSession;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import io.github.mariusbayizere.fraudshield.common.identity.PersonName;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Google sign-in (FR-07-03, FR-07-09, D-23).
 *
 * <ul>
 *   <li>The authorization code is exchanged with its PKCE verifier and the ID token verified
 *       server-side.
 *   <li>An existing account signs in if it is ACTIVE (a failed-sign-in lock does not stop a
 *       verified Google identity; an administrator's lock does). It is linked on first use; a link
 *       to a different Google subject is refused. PENDING_APPROVAL and DEACTIVATED accounts get 403
 *       {@code account-not-active} (FR-06-02).
 *   <li>A new email on a self-service domain creates an ANALYST account in PENDING_APPROVAL with no
 *       access (202); any other email is refused (403). Names and avatar come from Google.
 *   <li>The Google access token is held, encrypted, only for a session that signed in, so sign-out
 *       can revoke it; otherwise it is revoked at once.
 * </ul>
 */
public final class GoogleSignInService {

  private static final int EMPLOYEE_ID_HEX = 19;

  private final GoogleIdentityClient google;
  private final GoogleIdTokenVerifier verifier;
  private final GoogleTokenVault vault;
  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final SelfServiceDomains domains;
  private final SessionService sessions;
  private final LoginThrottle throttle;
  private final AuditLog audit;
  private final Clock clock;
  private final List<String> allowedRedirectUris;

  /**
   * Creates the service.
   *
   * @param google Google endpoints
   * @param verifier ID-token verifier
   * @param vault Google token vault
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param domains self-service domains
   * @param sessions session service
   * @param throttle request limits
   * @param audit audit log
   * @param clock clock
   * @param allowedRedirectUris redirect URIs the console may use
   */
  public GoogleSignInService(
      GoogleIdentityClient google,
      GoogleIdTokenVerifier verifier,
      GoogleTokenVault vault,
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      SelfServiceDomains domains,
      SessionService sessions,
      LoginThrottle throttle,
      AuditLog audit,
      Clock clock,
      List<String> allowedRedirectUris) {
    this.google = Objects.requireNonNull(google, "google");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.vault = Objects.requireNonNull(vault, "vault");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.domains = Objects.requireNonNull(domains, "domains");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.allowedRedirectUris = List.copyOf(allowedRedirectUris);
  }

  /**
   * The outcome of a Google sign-in.
   *
   * @param session the session when an ACTIVE account signed in, else null
   * @param pendingApproval whether a new account awaiting approval was created
   */
  public record Result(IssuedSession session, boolean pendingApproval) {}

  /**
   * Signs in with an authorization code.
   *
   * @param code authorization code
   * @param codeVerifier PKCE verifier
   * @param redirectUri redirect URI
   * @param context request context
   * @return a session, or a pending-approval result
   * @throws ProblemException 401, 403, 422, 429 or 503
   */
  public Result signIn(
      String code, String codeVerifier, String redirectUri, RequestContext context) {
    throttle.publicEndpoint("google", context.ipAddress());
    if (!allowedRedirectUris.contains(redirectUri)) {
      throw ProblemException.validation(
          "redirect_uri", "unsupported_value", "The redirect URI is not registered");
    }
    GoogleTokens tokens;
    GoogleIdentity identity;
    try {
      tokens =
          google
              .exchange(code, codeVerifier, redirectUri)
              .orElseThrow(ProblemException::unauthorized);
      identity = verifier.verify(tokens.idToken()).orElseThrow(ProblemException::unauthorized);
    } catch (GoogleUnavailableException e) {
      throw ProblemException.of(
              "service-unavailable",
              503,
              "Google sign-in temporarily unavailable",
              "Sign in with your email and password, or try Google again shortly")
          .withRetryAfter(30);
    }
    Result result = null;
    try {
      result = accountFor(identity, context);
      return result;
    } finally {
      // The Google token is kept only for a session that signed in; otherwise (refusal, pending
      // account or any failure) it is revoked at once.
      if (result != null && result.session() != null && tokens.accessToken() != null) {
        vault.store(result.session().sessionId(), tokens.accessToken(), tokens.expiresInSeconds());
      } else {
        revokeQuietly(tokens);
      }
    }
  }

  private void revokeQuietly(GoogleTokens tokens) {
    if (tokens.accessToken() != null) {
      google.revoke(tokens.accessToken());
    }
  }

  private Result accountFor(GoogleIdentity identity, RequestContext context) {
    Optional<StaffAccountRepository.AccountRef> ref = accounts.findRefByEmail(identity.email());
    if (ref.isPresent()) {
      Existing outcome =
          tenants.inTenant(
              ref.get().institutionId(),
              () -> signInExisting(ref.get().userId(), identity, context));
      return switch (outcome.refusal()) {
        case NONE -> new Result(outcome.session(), false);
        case NOT_ACTIVE ->
            throw ProblemException.of(
                "account-not-active",
                403,
                "Account not active",
                "This account is awaiting approval, locked by an administrator or deactivated");
        case OTHER_GOOGLE_ACCOUNT -> throw ProblemException.unauthorized();
      };
    }
    UUID institution =
        domains
            .institutionOf(identity.email())
            .orElseThrow(
                () ->
                    ProblemException.of(
                        "forbidden",
                        403,
                        "Forbidden",
                        "This Google account's domain cannot create FraudShield accounts"));
    String firstName = validName(identity.givenName(), "given_name");
    String lastName = validName(identity.familyName(), "family_name");
    tenants.runInTenant(
        institution, () -> create(institution, identity, firstName, lastName, context));
    return new Result(null, true);
  }

  private enum Refusal {
    NONE,
    NOT_ACTIVE,
    OTHER_GOOGLE_ACCOUNT
  }

  /** Refusals are returned, not thrown, so their audit records commit. */
  private record Existing(IssuedSession session, Refusal refusal) {}

  private Existing signInExisting(UUID userId, GoogleIdentity identity, RequestContext context) {
    StaffAccount account = accounts.findByIdForUpdate(userId).orElseThrow();
    Instant now = clock.instant();
    boolean adminLock =
        account.status() == AccountStatus.LOCKED
            && StaffAccountRepository.ADMIN_LOCK_UNTIL.equals(account.lockedUntil());
    if (account.status() == AccountStatus.PENDING_APPROVAL
        || account.status() == AccountStatus.DEACTIVATED
        || adminLock) {
      record(
          account,
          "GOOGLE_SIGN_IN_REFUSED",
          Map.of("status", account.status().name()),
          context,
          now);
      return new Existing(null, Refusal.NOT_ACTIVE);
    }
    Optional<String> linked = accounts.googleSubject(account.id());
    if (linked.isPresent() && !linked.get().equals(identity.subject())) {
      record(
          account,
          "GOOGLE_SIGN_IN_REFUSED",
          Map.of("reason", "OTHER_GOOGLE_ACCOUNT"),
          context,
          now);
      return new Existing(null, Refusal.OTHER_GOOGLE_ACCOUNT);
    }
    accounts.linkGoogle(account.id(), identity.subject(), identity.picture());
    if (linked.isEmpty()) {
      record(account, "GOOGLE_ACCOUNT_LINKED", Map.of(), context, now);
    }
    accounts.recordLoginSuccess(account.id(), now);
    StaffAccount signedIn = accounts.findById(account.id()).orElseThrow();
    IssuedSession session = sessions.start(signedIn, context, "GOOGLE");
    record(
        signedIn,
        "GOOGLE_SIGN_IN",
        Map.of("session_id", session.sessionId().toString()),
        context,
        now);
    return new Existing(session, Refusal.NONE);
  }

  private void create(
      UUID institution,
      GoogleIdentity identity,
      String firstName,
      String lastName,
      RequestContext context) {
    Instant now = clock.instant();
    StaffAccount account =
        new StaffAccount(
            UUID.randomUUID(),
            institution,
            firstName,
            lastName,
            identity.email(),
            null,
            StaffRole.ANALYST,
            StaffRole.ANALYST,
            AccountStatus.PENDING_APPROVAL,
            null,
            0,
            0,
            true,
            StaffLocale.en,
            identity.picture(),
            true,
            placeholderEmployeeId(identity.subject()),
            Department.OTHER,
            null,
            now,
            0);
    accounts.insert(account, null, identity.subject());
    audit.record(
        AuditEvent.of(institution, AuditEventType.AUTH, "GOOGLE_ACCOUNT_CREATED")
            .entity("user", account.id())
            .after(Map.of("status", "PENDING_APPROVAL", "role", "ANALYST"))
            .context(context)
            .at(now));
  }

  /**
   * Employee ID for a Google-created account until an administrator records the real one: 'G' and
   * 19 hex characters of SHA-256 of the Google subject (fits {@code ^[A-Za-z0-9]{4,20}$}).
   */
  static String placeholderEmployeeId(String subject) {
    return "G" + HexFormat.of().formatHex(Crypto.sha256(subject)).substring(0, EMPLOYEE_ID_HEX);
  }

  private static String validName(String name, String field) {
    if (name == null || PersonName.check(name).isPresent()) {
      throw ProblemException.validation(
          field,
          "person_name",
          "Google did not provide a usable name; register with the form instead");
    }
    return PersonName.parse(name).value();
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
}
