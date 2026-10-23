package io.github.mariusbayizere.fraudshield.auth.domain;

import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A staff account as stored, without credential material (the password hash is read separately and
 * only by the code that compares it).
 *
 * @param id account ID
 * @param institutionId institution
 * @param firstName first name
 * @param lastName last name
 * @param email lower-case email, unique across institutions
 * @param phone E.164 phone, or null
 * @param role granted role; for a PENDING_APPROVAL account a placeholder with no access
 * @param requestedRole role asked for at registration, or null
 * @param status status
 * @param lockedUntil end of a lock, or null
 * @param failedLoginCount consecutive failed sign-ins
 * @param tokenVersion session generation (D-27)
 * @param emailVerified whether the email was verified
 * @param preferredLocale language
 * @param avatarUrl avatar, or null
 * @param googleLinked whether a Google account is linked
 * @param employeeId employee ID, unique per institution
 * @param department department
 * @param lastLoginAt last successful sign-in, or null
 * @param createdAt creation time
 * @param version optimistic-locking version of the administrator-editable fields
 */
public record StaffAccount(
    UUID id,
    UUID institutionId,
    String firstName,
    String lastName,
    String email,
    String phone,
    StaffRole role,
    StaffRole requestedRole,
    AccountStatus status,
    Instant lockedUntil,
    int failedLoginCount,
    long tokenVersion,
    boolean emailVerified,
    StaffLocale preferredLocale,
    String avatarUrl,
    boolean googleLinked,
    String employeeId,
    Department department,
    Instant lastLoginAt,
    Instant createdAt,
    long version) {

  /** Validates the required fields. */
  public StaffAccount {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(institutionId, "institutionId");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(status, "status");
  }

  /**
   * Whether a failure lock has run out (D-26: failed-sign-in locks lift after 30 minutes; an
   * administrator's lock has no end).
   *
   * @param now the current time
   * @return whether the account is LOCKED with a lock that has ended
   */
  public boolean lockExpired(Instant now) {
    return status == AccountStatus.LOCKED && lockedUntil != null && !lockedUntil.isAfter(now);
  }
}
