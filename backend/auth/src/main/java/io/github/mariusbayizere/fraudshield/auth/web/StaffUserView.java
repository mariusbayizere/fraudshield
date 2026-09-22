package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.auth.domain.StaffAccount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The contract's StaffUser: profile, role and status, never credential material.
 *
 * @param userId account ID
 * @param firstName first name
 * @param lastName last name
 * @param email email
 * @param phone phone or null
 * @param avatarUrl avatar or null
 * @param role role
 * @param requestedRole requested role or null
 * @param status status
 * @param employeeId employee ID
 * @param department department
 * @param preferredLocale locale
 * @param emailVerified whether the email was verified
 * @param linkedProviders linked sign-in providers
 * @param lastLoginAt last sign-in or null
 * @param createdAt creation time
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StaffUserView(
    UUID userId,
    String firstName,
    String lastName,
    String email,
    String phone,
    String avatarUrl,
    String role,
    String requestedRole,
    String status,
    String employeeId,
    String department,
    String preferredLocale,
    boolean emailVerified,
    List<String> linkedProviders,
    Instant lastLoginAt,
    Instant createdAt) {

  /** Copies the providers. */
  public StaffUserView {
    linkedProviders = List.copyOf(linkedProviders);
  }

  /**
   * The view of an account.
   *
   * @param account the account
   * @return the view
   */
  public static StaffUserView of(StaffAccount account) {
    return new StaffUserView(
        account.id(),
        account.firstName(),
        account.lastName(),
        account.email(),
        account.phone(),
        account.avatarUrl(),
        account.role().name(),
        account.requestedRole() == null ? null : account.requestedRole().name(),
        account.status().name(),
        account.employeeId(),
        account.department().name(),
        account.preferredLocale().name(),
        account.emailVerified(),
        account.googleLinked() ? List.of("GOOGLE") : List.of(),
        account.lastLoginAt(),
        account.createdAt());
  }
}
