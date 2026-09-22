package io.github.mariusbayizere.fraudshield.audit;

import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.util.Objects;
import java.util.UUID;

/**
 * The staff member who performed an audited action (FR-06-06: first name, last name and role are
 * stored with the event so the record stays readable after the account changes).
 *
 * @param userId account ID
 * @param firstName first name at the time of the action
 * @param lastName last name at the time of the action
 * @param role role at the time of the action
 */
public record AuditActor(UUID userId, String firstName, String lastName, StaffRole role) {

  /** Validates that every field is present. */
  public AuditActor {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(firstName, "firstName");
    Objects.requireNonNull(lastName, "lastName");
    Objects.requireNonNull(role, "role");
  }
}
