package io.github.mariusbayizere.fraudshield.auth.session;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions: sign-in issue, refresh rotation with reuse detection, sign-out and sign-out everywhere
 * (FR-07-04, FR-07-09, D-27).
 *
 * <p>A sign-in starts a refresh-token family whose ID is the access token's {@code sid}. Each
 * refresh rotates the token; the family keeps the expiry of its first token, so a sign-in lasts at
 * most the refresh lifetime (7 days) however often it is refreshed. Presenting a rotated or revoked
 * token again means it was copied: the whole family is revoked, ending the thief's and the owner's
 * session alike.
 */
public final class SessionService {

  private static final int REFRESH_TOKEN_BYTES = 32;
  private static final int CSRF_TOKEN_BYTES = 32;

  private final TenantTransactions tenants;
  private final StaffAccountRepository accounts;
  private final RefreshTokenRepository tokens;
  private final AccessTokens accessTokens;
  private final SessionStateCache cache;
  private final AuditLog audit;
  private final Clock clock;
  private final Duration refreshTtl;

  /**
   * Creates the service.
   *
   * @param tenants tenant transactions
   * @param accounts account repository
   * @param tokens refresh-token repository
   * @param accessTokens access-token issuer
   * @param cache session-state cache
   * @param audit audit log
   * @param clock clock
   * @param refreshTtl absolute lifetime of a sign-in
   */
  public SessionService(
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      RefreshTokenRepository tokens,
      AccessTokens accessTokens,
      SessionStateCache cache,
      AuditLog audit,
      Clock clock,
      Duration refreshTtl) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
    this.cache = Objects.requireNonNull(cache, "cache");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.refreshTtl = Objects.requireNonNull(refreshTtl, "refreshTtl");
  }

  /**
   * The audit actor of an account.
   *
   * @param account the account
   * @return the actor
   */
  public static AuditActor actor(StaffAccount account) {
    return new AuditActor(account.id(), account.firstName(), account.lastName(), account.role());
  }

  /**
   * Starts a session for an ACTIVE account. Must run in the account's tenant transaction.
   *
   * @param account the account, with its current token version
   * @param context request context
   * @param oauthProvider GOOGLE for a Google sign-in, else null
   * @return the credentials
   */
  public IssuedSession start(StaffAccount account, RequestContext context, String oauthProvider) {
    UUID familyId = UUID.randomUUID();
    return issue(account, familyId, clock.instant().plus(refreshTtl), context, oauthProvider)
        .session();
  }

  private record Issued(IssuedSession session, UUID refreshTokenId) {}

  private Issued issue(
      StaffAccount account,
      UUID familyId,
      Instant refreshExpiresAt,
      RequestContext context,
      String oauthProvider) {
    String refresh = Crypto.randomToken(REFRESH_TOKEN_BYTES);
    UUID refreshTokenId =
        tokens.insert(
            new RefreshTokenRepository.NewToken(
                account.institutionId(),
                account.id(),
                familyId,
                Crypto.sha256(refresh),
                refreshExpiresAt,
                context.ipAddress(),
                oauthProvider,
                context.userAgent()));
    String access =
        accessTokens.issue(
            new StaffClaims(
                account.id(),
                account.institutionId(),
                account.role(),
                account.firstName(),
                account.email(),
                account.tokenVersion(),
                familyId));
    return new Issued(
        new IssuedSession(
            access,
            accessTokens.ttlSeconds(),
            refresh,
            refreshExpiresAt,
            Crypto.randomToken(CSRF_TOKEN_BYTES),
            familyId,
            account),
        refreshTokenId);
  }

  private enum RefreshOutcome {
    ROTATED,
    REUSED,
    REJECTED
  }

  private record RefreshResult(RefreshOutcome outcome, IssuedSession session, UUID familyId) {}

  /**
   * Rotates a refresh token.
   *
   * @param refreshToken the raw token from the cookie
   * @param context request context
   * @return new credentials
   * @throws ProblemException 401 if the token is unknown, expired, reused or the account cannot
   *     sign in
   */
  public IssuedSession refresh(String refreshToken, RequestContext context) {
    Optional<RefreshTokenRepository.TokenRef> ref = tokens.findRef(Crypto.sha256(refreshToken));
    if (ref.isEmpty()) {
      throw ProblemException.unauthorized();
    }
    RefreshResult result =
        tenants.inTenant(ref.get().institutionId(), () -> rotate(ref.get().id(), context));
    if (result.outcome() == RefreshOutcome.ROTATED) {
      return result.session();
    }
    throw ProblemException.unauthorized();
  }

  private RefreshResult rotate(UUID tokenId, RequestContext context) {
    Instant now = clock.instant();
    Optional<RefreshTokenRepository.StoredToken> row = tokens.findForUpdate(tokenId);
    if (row.isEmpty()) {
      return new RefreshResult(RefreshOutcome.REJECTED, null, null);
    }
    RefreshTokenRepository.StoredToken stored = row.get();
    Optional<StaffAccount> found = accounts.findById(stored.userId());
    if (stored.spent()) {
      int revoked = tokens.revokeFamily(stored.familyId(), now);
      found.ifPresent(
          account ->
              audit.record(
                  AuditEvent.of(
                          account.institutionId(), AuditEventType.AUTH, "REFRESH_TOKEN_REUSED")
                      .entity("session", stored.familyId())
                      .actor(actor(account))
                      .after(Map.of("revoked_tokens", revoked))
                      .context(context)
                      .at(now)));
      AfterCommit.run(() -> cache.sessionEnded(stored.familyId()));
      return new RefreshResult(RefreshOutcome.REUSED, null, stored.familyId());
    }
    if (!stored.expiresAt().isAfter(now)
        || found.isEmpty()
        || !canHoldSession(found.get().status())) {
      return new RefreshResult(RefreshOutcome.REJECTED, null, stored.familyId());
    }
    StaffAccount account = found.get();
    Issued issued =
        issue(account, stored.familyId(), stored.expiresAt(), context, stored.oauthProvider());
    tokens.markRotated(stored.id(), issued.refreshTokenId(), now);
    audit.record(
        AuditEvent.of(account.institutionId(), AuditEventType.AUTH, "SESSION_REFRESHED")
            .entity("session", stored.familyId())
            .actor(actor(account))
            .context(context)
            .at(now));
    return new RefreshResult(RefreshOutcome.ROTATED, issued.session(), stored.familyId());
  }

  /**
   * Whether an account in this status may keep or refresh a session. A failure lock does not end a
   * session, because anyone who knows the email can cause one (D-26); an administrator's lock,
   * deactivation and a role change revoke the sessions explicitly.
   *
   * @param status account status
   * @return whether sessions are allowed
   */
  static boolean canHoldSession(AccountStatus status) {
    return status == AccountStatus.ACTIVE || status == AccountStatus.LOCKED;
  }

  /**
   * Ends the session of an access token (FR-07-09).
   *
   * @param claims the token's claims
   * @param context request context
   */
  public void logout(StaffClaims claims, RequestContext context) {
    tenants.runInTenant(
        claims.institutionId(),
        () -> {
          Instant now = clock.instant();
          tokens.revokeFamily(claims.sessionId(), now);
          accounts
              .findById(claims.userId())
              .ifPresent(
                  account ->
                      audit.record(
                          AuditEvent.of(account.institutionId(), AuditEventType.AUTH, "LOGOUT")
                              .entity("session", claims.sessionId())
                              .actor(actor(account))
                              .context(context)
                              .at(now)));
          AfterCommit.run(() -> cache.sessionEnded(claims.sessionId()));
        });
  }

  /**
   * Ends every session of the caller's account by incrementing the token version (D-27).
   *
   * @param claims the token's claims
   * @param context request context
   */
  public void logoutEverywhere(StaffClaims claims, RequestContext context) {
    tenants.runInTenant(
        claims.institutionId(),
        () -> {
          StaffAccount account = accounts.findByIdForUpdate(claims.userId()).orElseThrow();
          endAllSessions(account, "LOGOUT_EVERYWHERE", actor(account), context);
        });
  }

  /**
   * Ends every session of an account: increments the token version, revokes every refresh token and
   * records the audit event. Must run in the account's tenant transaction.
   *
   * @param account the account
   * @param action audit action (AUTH type)
   * @param actor who acted
   * @param context request context
   * @return the new token version
   */
  public long endAllSessions(
      StaffAccount account, String action, AuditActor actor, RequestContext context) {
    Instant now = clock.instant();
    long version = accounts.bumpTokenVersion(account.id());
    int revoked = tokens.revokeAllForUser(account.id(), now);
    audit.record(
        AuditEvent.of(account.institutionId(), AuditEventType.AUTH, action)
            .entity("user", account.id())
            .actor(actor)
            .before(Map.of("token_version", account.tokenVersion()))
            .after(Map.of("token_version", version, "revoked_tokens", revoked))
            .context(context)
            .at(now));
    AfterCommit.run(() -> cache.versionChanged(account.id(), version));
    return version;
  }

  /**
   * Revokes every refresh token of an account and announces a token version that the caller has
   * already written (password change and administrator edits write it in the same UPDATE). Must run
   * in the account's tenant transaction.
   *
   * @param userId account
   * @param newVersion the token version now stored
   */
  public void revokeAfterVersionChange(UUID userId, long newVersion) {
    tokens.revokeAllForUser(userId, clock.instant());
    AfterCommit.run(() -> cache.versionChanged(userId, newVersion));
  }
}
