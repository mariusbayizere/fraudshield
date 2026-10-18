package io.github.mariusbayizere.fraudshield.common.config;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A THRESHOLD_CHANGE audit event (D-32) for a risk-configuration change.
 *
 * @param action what happened
 * @param change the change after the action
 * @param actor the staff member, or null for the system (automatic revert)
 * @param at when it happened
 */
public record ConfigAuditEvent(Action action, ConfigChange change, Actor actor, Instant at) {

  /** Audit event type shared by thresholds, timeout policies and circuit-breaker settings. */
  public static final String EVENT_TYPE = "THRESHOLD_CHANGE";

  /** Audited actions. */
  public enum Action {
    /** A loosening change was proposed. */
    PROPOSED,
    /** A tightening change was proposed and applied at once. */
    APPLIED_PENDING_CONFIRMATION,
    /** A loosening change was approved and applied. */
    APPROVED,
    /** A tightening change was confirmed. */
    CONFIRMED,
    /** A loosening change was rejected. */
    REJECTED,
    /** A tightening change was reverted by rejection or by the confirmation deadline. */
    REVERTED,
    /** An open change was superseded by a later tightening change. */
    SUPERSEDED,
    /** A loosening proposal was withdrawn by its proposer. */
    WITHDRAWN
  }

  /** Validates required components. */
  public ConfigAuditEvent {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(change, "change");
    Objects.requireNonNull(at, "at");
  }

  /**
   * The acting staff member.
   *
   * @return the actor, or empty for a system action
   */
  public Optional<Actor> actingStaff() {
    return Optional.ofNullable(actor);
  }
}
