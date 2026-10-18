package io.github.mariusbayizere.fraudshield.common.config;

import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated staff member performing an action.
 *
 * @param userId staff user identifier
 * @param role the account's single role
 */
public record Actor(UUID userId, StaffRole role) {

  /** Validates the components. */
  public Actor {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(role, "role");
  }
}
