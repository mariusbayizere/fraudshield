package io.github.mariusbayizere.fraudshield.auth.jwt;

import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.util.Objects;
import java.util.UUID;

/**
 * The claims of a staff access token (FR-07-04, E.8, ADR 0014 decision 8): {@code sub}, {@code
 * role}, {@code first_name}, {@code email}, {@code institution_id}, {@code token_version} and
 * {@code sid}, plus {@code iss}, {@code aud}, {@code iat}, {@code exp}, {@code jti} and the header
 * {@code kid}. The role is fixed at issue (FR-07-01); a role change increments the token version,
 * which ends every token carrying the old role.
 *
 * @param userId account ID
 * @param institutionId institution
 * @param role role at issue
 * @param firstName first name
 * @param email email
 * @param tokenVersion account token version at issue
 * @param sessionId session and refresh-token family ID
 */
public record StaffClaims(
    UUID userId,
    UUID institutionId,
    StaffRole role,
    String firstName,
    String email,
    long tokenVersion,
    UUID sessionId) {

  /** Validates the fields. */
  public StaffClaims {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(institutionId, "institutionId");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(sessionId, "sessionId");
  }
}
