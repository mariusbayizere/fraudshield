package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.crypto.SignedToken;
import io.github.mariusbayizere.fraudshield.auth.domain.AccountStatus;
import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.support.MinimumDuration;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Password reset by emailed one-time code (FR-07-08, E.8).
 *
 * <ol>
 *   <li>Request: same 202 whether or not the email exists, padded to a minimum duration. A new code
 *       cancels any open one. The code is 6 digits, stored as an HMAC keyed by a server secret (a
 *       plain hash of a 6-digit code is reversed in a millisecond), single-use, valid 10 minutes, 5
 *       attempts.
 *   <li>Verify: a correct code is consumed and exchanged for a reset token bound to the current
 *       password hash and token version.
 *   <li>Complete: sets the password, which changes both, so the reset token cannot be reused; every
 *       session ends (FR-07-09).
 * </ol>
 */
public final class PasswordResetService {

  private static final Duration CODE_TTL = Duration.ofMinutes(10);
  private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(10);
  private static final int MAX_ATTEMPTS = 5;
  private static final int CODE_SPACE = 1_000_000;
  private static final int MAX_CODE_GUESSES_PER_DAY = 20;

  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final SessionService sessions;
  private final SignedToken tokens;
  private final StaffMailer mailer;
  private final AuditLog audit;
  private final Clock clock;
  private final byte[] codeKey;
  private final Duration minimumResponse;

  /**
   * Creates the service.
   *
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param jdbc JDBC template of the application role
   * @param hasher password hasher
   * @param throttle request limits
   * @param sessions session service
   * @param tokens single-use token codec
   * @param mailer staff mailer
   * @param audit audit log
   * @param clock clock
   * @param codeKey HMAC key for codes, at least 32 bytes
   * @param minimumResponse floor on the request response time
   */
  public PasswordResetService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      PasswordHasher hasher,
      LoginThrottle throttle,
      SessionService sessions,
      SignedToken tokens,
      StaffMailer mailer,
      AuditLog audit,
      Clock clock,
      byte[] codeKey,
      Duration minimumResponse) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.mailer = Objects.requireNonNull(mailer, "mailer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (codeKey.length < Crypto.MIN_KEY_BYTES) {
      throw new IllegalStateException("the code key must be at least 32 bytes");
    }
    this.codeKey = codeKey.clone();
    this.minimumResponse = Objects.requireNonNull(minimumResponse, "minimumResponse");
  }

  /**
   * Emails a reset code if the email belongs to an ACTIVE or LOCKED account.
   *
   * @param email submitted email
   * @param context request context
   */
  public void request(String email, RequestContext context) {
    String normalised = email.toLowerCase(Locale.ROOT);
    throttle.publicEndpoint("reset-request", context.ipAddress());
    throttle.resetForEmail(normalised);
    MinimumDuration.atLeast(
        minimumResponse,
        () -> {
          accounts
              .findRefByEmail(normalised)
              .ifPresent(
                  ref ->
                      tenants.runInTenant(
                          ref.institutionId(), () -> issueCode(ref.userId(), context)));
          return null;
        });
  }

  private void issueCode(UUID userId, RequestContext context) {
    StaffAccount account = accounts.findByIdForUpdate(userId).orElseThrow();
    if (account.status() != AccountStatus.ACTIVE && account.status() != AccountStatus.LOCKED) {
      return;
    }
    Instant now = clock.instant();
    jdbc.update(
        "UPDATE password_reset_otps SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL",
        Timestamp.from(now),
        userId);
    UUID codeId = UUID.randomUUID();
    String code = String.format(Locale.ROOT, "%06d", Crypto.randomInt(CODE_SPACE));
    jdbc.update(
        """
        INSERT INTO password_reset_otps (id, institution_id, user_id, code_hash, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        codeId,
        account.institutionId(),
        userId,
        codeHash(codeId, code),
        Timestamp.from(now.plus(CODE_TTL)),
        Timestamp.from(now));
    record(account, "PASSWORD_RESET_REQUESTED", context, now);
    StaffMailer.Message message =
        new StaffMailer.Message(
            account.email(),
            account.preferredLocale(),
            StaffMailer.Template.PASSWORD_RESET_CODE,
            account.firstName(),
            code);
    AfterCommit.run(() -> mailer.send(message));
  }

  /**
   * A reset token for a verified code.
   *
   * @param resetToken the token
   * @param expiresAt its expiry
   */
  public record ResetToken(String resetToken, Instant expiresAt) {

    @Override
    public String toString() {
      return "ResetToken[<redacted>]";
    }
  }

  private record CodeRow(UUID id, byte[] hash, int attempts) {}

  /**
   * Verifies a code and returns a reset token.
   *
   * @param email submitted email
   * @param code submitted 6-digit code
   * @param context request context
   * @return the reset token
   * @throws ProblemException 422 {@code invalid_format} on the code if it is wrong, used, expired
   *     or out of attempts (one message for all, so nothing about the account is revealed)
   */
  public ResetToken verify(String email, String code, RequestContext context) {
    throttle.publicEndpoint("reset-verify", context.ipAddress());
    String normalised = email.toLowerCase(Locale.ROOT);
    // A daily cap per email bounds cumulative guessing across codes (review finding 15).
    throttle.limit("reset-verify:email", normalised, MAX_CODE_GUESSES_PER_DAY, Duration.ofDays(1));
    // Padded, so an unknown email is not answered faster than a known one (review finding 9).
    Optional<ResetToken> token =
        MinimumDuration.atLeast(
            minimumResponse,
            () ->
                accounts
                    .findRefByEmail(normalised)
                    .flatMap(
                        ref ->
                            tenants.inTenant(
                                ref.institutionId(), () -> check(ref.userId(), code, context))));
    return token.orElseThrow(PasswordResetService::invalidCode);
  }

  private Optional<ResetToken> check(UUID userId, String code, RequestContext context) {
    Instant now = clock.instant();
    List<CodeRow> rows =
        jdbc.query(
            """
            SELECT id, code_hash, attempts FROM password_reset_otps
            WHERE user_id = ? AND consumed_at IS NULL AND expires_at > ?
            ORDER BY created_at DESC LIMIT 1 FOR UPDATE
            """,
            (row, i) -> new CodeRow(row.getObject(1, UUID.class), row.getBytes(2), row.getInt(3)),
            userId,
            Timestamp.from(now));
    StaffAccount account = accounts.findById(userId).orElseThrow();
    if (rows.isEmpty() || rows.getFirst().attempts() >= MAX_ATTEMPTS) {
      record(account, "PASSWORD_RESET_CODE_REJECTED", context, now);
      return Optional.empty();
    }
    CodeRow row = rows.getFirst();
    jdbc.update("UPDATE password_reset_otps SET attempts = attempts + 1 WHERE id = ?", row.id());
    if (!Crypto.constantTimeEquals(row.hash(), codeHash(row.id(), code))) {
      record(account, "PASSWORD_RESET_CODE_REJECTED", context, now);
      return Optional.empty();
    }
    jdbc.update(
        "UPDATE password_reset_otps SET consumed_at = ? WHERE id = ?",
        Timestamp.from(now),
        row.id());
    record(account, "PASSWORD_RESET_CODE_VERIFIED", context, now);
    Instant expiresAt = now.plus(RESET_TOKEN_TTL);
    String token =
        tokens.issue(
            SignedToken.Purpose.PASSWORD_RESET,
            userId,
            expiresAt,
            AccountTokens.resetState(
                userId,
                account.tokenVersion(),
                accounts.passwordHash(userId).orElse(""),
                row.id()));
    return Optional.of(new ResetToken(token, expiresAt));
  }

  /**
   * Sets a new password with a reset token and ends every session.
   *
   * @param resetToken the token from {@link #verify}
   * @param newPassword the new password (already validated against the policy)
   * @param context request context
   * @throws ProblemException 410 if the token is invalid, expired or used
   */
  public void complete(String resetToken, String newPassword, RequestContext context) {
    Instant now = clock.instant();
    SignedToken.Claims claims =
        tokens
            .verify(resetToken, SignedToken.Purpose.PASSWORD_RESET, now)
            .orElseThrow(AccountUnlockService::gone);
    UUID institution =
        accounts.findInstitution(claims.userId()).orElseThrow(AccountUnlockService::gone);
    String hash = hasher.hash(newPassword);
    boolean done =
        tenants.inTenant(
            institution,
            () -> {
              StaffAccount account = accounts.findByIdForUpdate(claims.userId()).orElse(null);
              if (account == null || !resetStillValid(account, claims)) {
                return false;
              }
              long version = accounts.changePassword(account.id(), hash);
              sessions.revokeAfterVersionChange(account.id(), version);
              audit.record(
                  AuditEvent.of(
                          account.institutionId(), AuditEventType.AUTH, "PASSWORD_RESET_COMPLETED")
                      .entity("user", account.id())
                      .actor(SessionService.actor(account))
                      .before(Map.of("token_version", account.tokenVersion()))
                      .after(Map.of("token_version", version))
                      .context(context)
                      .at(now));
              return true;
            });
    if (!done) {
      throw AccountUnlockService.gone();
    }
  }

  private boolean resetStillValid(StaffAccount account, SignedToken.Claims claims) {
    if (account.status() != AccountStatus.ACTIVE && account.status() != AccountStatus.LOCKED) {
      return false;
    }
    String passwordHash = accounts.passwordHash(account.id()).orElse("");
    List<UUID> codes =
        jdbc.queryForList(
            "SELECT id FROM password_reset_otps WHERE user_id = ? AND consumed_at IS NOT NULL",
            UUID.class,
            account.id());
    return codes.stream()
        .anyMatch(
            codeId ->
                claims.boundTo(
                    AccountTokens.resetState(
                        account.id(), account.tokenVersion(), passwordHash, codeId)));
  }

  private byte[] codeHash(UUID codeId, String code) {
    return Crypto.hmacSha256(
        codeKey,
        "password-reset-code|".getBytes(StandardCharsets.UTF_8),
        codeId.toString().getBytes(StandardCharsets.UTF_8),
        "|".getBytes(StandardCharsets.UTF_8),
        code.getBytes(StandardCharsets.UTF_8));
  }

  private void record(StaffAccount account, String action, RequestContext context, Instant now) {
    audit.record(
        AuditEvent.of(account.institutionId(), AuditEventType.AUTH, action)
            .entity("user", account.id())
            .actor(SessionService.actor(account))
            .context(context)
            .at(now));
  }

  private static ProblemException invalidCode() {
    return ProblemException.validation(
        "code", "invalid_format", "The code is incorrect, has expired or has already been used");
  }
}
