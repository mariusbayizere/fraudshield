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
import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.support.MinimumDuration;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import io.github.mariusbayizere.fraudshield.common.identity.PersonName;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Self-registration, availability checks and email verification (FR-07-02, D-24, E.8, ADR 0014).
 *
 * <p>Registration always answers the same 202, padded to a minimum duration, whether the details
 * are new, belong to an existing account (whose owner is emailed instead), or use a domain that may
 * not self-register (D-23: nothing is created or sent). A new account is PENDING_APPROVAL with no
 * access; the requested role is recorded and the granted role is set only by an administrator, so
 * nobody can self-elevate (FR-07-01).
 */
public final class RegistrationService {

  private static final Duration VERIFICATION_TTL = Duration.ofHours(24);
  private static final int VERIFICATION_TOKEN_BYTES = 32;

  private final StaffAccountRepository accounts;
  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final SelfServiceDomains domains;
  private final StaffMailer mailer;
  private final AuditLog audit;
  private final Clock clock;
  private final String consoleBaseUrl;
  private final Duration minimumResponse;

  /**
   * Creates the service.
   *
   * @param accounts account repository
   * @param tenants tenant transactions
   * @param jdbc JDBC template of the application role
   * @param hasher password hasher
   * @param throttle request limits
   * @param domains self-service domains
   * @param mailer staff mailer
   * @param audit audit log
   * @param clock clock
   * @param consoleBaseUrl console base URL, for the verification link
   * @param minimumResponse floor on the response time
   */
  public RegistrationService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      PasswordHasher hasher,
      LoginThrottle throttle,
      SelfServiceDomains domains,
      StaffMailer mailer,
      AuditLog audit,
      Clock clock,
      String consoleBaseUrl,
      Duration minimumResponse) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.domains = Objects.requireNonNull(domains, "domains");
    this.mailer = Objects.requireNonNull(mailer, "mailer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.consoleBaseUrl = Objects.requireNonNull(consoleBaseUrl, "consoleBaseUrl");
    this.minimumResponse = Objects.requireNonNull(minimumResponse, "minimumResponse");
  }

  /**
   * A validated registration.
   *
   * @param firstName first name
   * @param lastName last name
   * @param email email
   * @param phone E.164 phone
   * @param employeeId employee ID
   * @param department department
   * @param requestedRole requested role
   * @param password password
   * @param preferredLocale locale
   */
  public record Registration(
      String firstName,
      String lastName,
      String email,
      String phone,
      String employeeId,
      Department department,
      StaffRole requestedRole,
      String password,
      StaffLocale preferredLocale) {

    @Override
    public String toString() {
      return "Registration[<redacted>]";
    }
  }

  /**
   * Registers, or quietly does nothing visible.
   *
   * @param registration validated request
   * @param context request context
   */
  public void register(Registration registration, RequestContext context) {
    throttle.publicEndpoint("register", context.ipAddress());
    MinimumDuration.atLeast(
        minimumResponse,
        () -> {
          // Hash on every path so the time spent does not depend on which branch runs.
          String hash = hasher.hash(registration.password());
          String email = registration.email().toLowerCase(Locale.ROOT);
          Optional<UUID> institution = domains.institutionOf(email);
          if (institution.isEmpty()) {
            return null;
          }
          Optional<StaffAccountRepository.AccountRef> existing = accounts.findRefByEmail(email);
          if (existing.isPresent()) {
            notifyExisting(existing.get(), context);
            return null;
          }
          try {
            tenants.runInTenant(
                institution.get(),
                () -> create(institution.get(), registration, email, hash, context));
          } catch (DuplicateKeyException e) {
            // Registered concurrently: the same quiet outcome as any other duplicate.
          }
          return null;
        });
  }

  private void create(
      UUID institution,
      Registration registration,
      String email,
      String hash,
      RequestContext context) {
    Optional<StaffAccount> sameEmployee = accounts.findByEmployeeId(registration.employeeId());
    if (sameEmployee.isPresent()) {
      sendExistingNotice(sameEmployee.get(), context);
      return;
    }
    Instant now = clock.instant();
    StaffAccount account =
        new StaffAccount(
            UUID.randomUUID(),
            institution,
            PersonName.parse(registration.firstName()).value(),
            PersonName.parse(registration.lastName()).value(),
            email,
            registration.phone(),
            StaffRole.ANALYST,
            registration.requestedRole(),
            AccountStatus.PENDING_APPROVAL,
            null,
            0,
            0,
            false,
            registration.preferredLocale(),
            null,
            false,
            registration.employeeId(),
            registration.department(),
            null,
            now,
            0);
    accounts.insert(account, hash, null);
    String token = Crypto.randomToken(VERIFICATION_TOKEN_BYTES);
    jdbc.update(
        """
        INSERT INTO email_verification_tokens (institution_id, user_id, token_hash, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        institution,
        account.id(),
        Crypto.sha256(token),
        Timestamp.from(now.plus(VERIFICATION_TTL)),
        Timestamp.from(now));
    audit.record(
        AuditEvent.of(institution, AuditEventType.AUTH, "REGISTERED")
            .entity("user", account.id())
            .after(
                Map.of(
                    "status", "PENDING_APPROVAL",
                    "requested_role", registration.requestedRole().name(),
                    "department", registration.department().name(),
                    "employee_id", registration.employeeId()))
            .context(context)
            .at(now));
    StaffMailer.Message message =
        new StaffMailer.Message(
            email,
            account.preferredLocale(),
            StaffMailer.Template.EMAIL_VERIFICATION,
            account.firstName(),
            consoleBaseUrl + "/verify-email#token=" + token);
    AfterCommit.run(() -> mailer.send(message));
  }

  private void notifyExisting(StaffAccountRepository.AccountRef ref, RequestContext context) {
    tenants.runInTenant(
        ref.institutionId(),
        () ->
            accounts
                .findById(ref.userId())
                .ifPresent(account -> sendExistingNotice(account, context)));
  }

  private void sendExistingNotice(StaffAccount account, RequestContext context) {
    audit.record(
        AuditEvent.of(account.institutionId(), AuditEventType.AUTH, "REGISTRATION_DUPLICATE")
            .entity("user", account.id())
            .context(context)
            .at(clock.instant()));
    StaffMailer.Message message =
        new StaffMailer.Message(
            account.email(),
            account.preferredLocale(),
            StaffMailer.Template.REGISTRATION_EXISTING_ACCOUNT,
            account.firstName(),
            null);
    AfterCommit.run(() -> mailer.send(message));
  }

  /** Field of an availability check. */
  public enum AvailabilityField {
    /** Email. */
    email,
    /** Employee ID. */
    employee_id
  }

  /**
   * Whether an email or employee ID is free (E.8). Rate limited and padded to a minimum duration;
   * the answer itself is the accepted residual enumeration channel of ADR 0014.
   *
   * @param field which field
   * @param value the value
   * @param context request context
   * @return whether it is available
   */
  public boolean available(AvailabilityField field, String value, RequestContext context) {
    throttle.publicEndpoint("availability", context.ipAddress());
    return MinimumDuration.atLeast(
        minimumResponse,
        () ->
            switch (field) {
              case email -> accounts.findRefByEmail(value.toLowerCase(Locale.ROOT)).isEmpty();
              case employee_id -> !accounts.employeeIdRegisteredAnywhere(value);
            });
  }

  private record VerificationRow(UUID userId, Instant expiresAt, Instant consumedAt) {}

  /**
   * Confirms an email with the single-use token from the verification email.
   *
   * @param token the token
   * @param context request context
   * @throws ProblemException 410 if the token is unknown, expired or used
   */
  public void verifyEmail(String token, RequestContext context) {
    byte[] hash = Crypto.sha256(token);
    List<UUID[]> refs =
        jdbc.query(
            "SELECT token_id, institution_id FROM auth_find_email_verification(?)",
            (row, i) -> new UUID[] {row.getObject(1, UUID.class), row.getObject(2, UUID.class)},
            (Object) hash);
    if (refs.isEmpty()) {
      throw AccountUnlockService.gone();
    }
    UUID tokenId = refs.getFirst()[0];
    boolean verified =
        tenants.inTenant(
            refs.getFirst()[1],
            () -> {
              Instant now = clock.instant();
              VerificationRow row =
                  jdbc.queryForObject(
                      """
                      SELECT user_id, expires_at, consumed_at FROM email_verification_tokens
                      WHERE id = ? FOR UPDATE
                      """,
                      (r, i) ->
                          new VerificationRow(
                              r.getObject(1, UUID.class),
                              r.getTimestamp(2).toInstant(),
                              r.getTimestamp(3) == null ? null : r.getTimestamp(3).toInstant()),
                      tokenId);
              if (row == null || row.consumedAt() != null || !row.expiresAt().isAfter(now)) {
                return false;
              }
              jdbc.update(
                  "UPDATE email_verification_tokens SET consumed_at = ? WHERE id = ?",
                  Timestamp.from(now),
                  tokenId);
              accounts.markEmailVerified(row.userId());
              accounts
                  .findById(row.userId())
                  .ifPresent(
                      account ->
                          audit.record(
                              AuditEvent.of(
                                      account.institutionId(),
                                      AuditEventType.AUTH,
                                      "EMAIL_VERIFIED")
                                  .entity("user", account.id())
                                  .actor(SessionService.actor(account))
                                  .context(context)
                                  .at(now)));
              return true;
            });
    if (!verified) {
      throw AccountUnlockService.gone();
    }
  }
}
