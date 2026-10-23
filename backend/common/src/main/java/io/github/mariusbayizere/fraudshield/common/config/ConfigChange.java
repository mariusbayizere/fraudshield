package io.github.mariusbayizere.fraudshield.common.config;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One proposed change to risk configuration and its current state. Instances are immutable; each
 * transition produces a new instance.
 *
 * @param changeId identifier
 * @param direction tightening or loosening
 * @param status current state
 * @param proposedBy the proposing risk officer
 * @param proposedAt when it was proposed
 * @param reason the proposer's reason
 * @param baseVersion configuration version the proposal was made against
 * @param previous settings in effect when proposed (restored on revert)
 * @param proposed proposed settings
 * @param confirmBy deadline for confirming a tightening change; null for loosening
 * @param reviewedBy the second risk officer who approved, confirmed or rejected; null until then
 * @param reviewedAt when it was reviewed; null until then
 * @param reviewReason the reviewer's reason for a rejection; null otherwise
 * @param effectiveAt when the proposed settings took effect; null if never applied
 * @param revertedAt when a tightening change was reverted; null otherwise
 * @param revertCause why it was reverted; null otherwise
 */
public record ConfigChange(
    UUID changeId,
    ChangeDirection direction,
    ChangeStatus status,
    Actor proposedBy,
    Instant proposedAt,
    String reason,
    long baseVersion,
    ConfigSettings previous,
    ConfigSettings proposed,
    Instant confirmBy,
    Actor reviewedBy,
    Instant reviewedAt,
    String reviewReason,
    Instant effectiveAt,
    Instant revertedAt,
    RevertCause revertCause) {

  /** Why a tightening change was reverted. */
  public enum RevertCause {
    /** A second risk officer rejected it. */
    REJECTED,
    /** Nobody confirmed it within the confirmation window. */
    NOT_CONFIRMED_IN_TIME
  }

  /** Validates required components. */
  public ConfigChange {
    Objects.requireNonNull(changeId, "changeId");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(proposedBy, "proposedBy");
    Objects.requireNonNull(proposedAt, "proposedAt");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(proposed, "proposed");
  }

  /**
   * The configuration kind.
   *
   * @return the kind of the proposed settings
   */
  public ConfigKind kind() {
    return proposed.kind();
  }

  /**
   * Whether the change still needs a second risk officer.
   *
   * @return true while pending approval or confirmation
   */
  public boolean isOpen() {
    return status == ChangeStatus.PENDING_APPROVAL
        || status == ChangeStatus.APPLIED_PENDING_CONFIRMATION;
  }

  /**
   * The reviewer, if any.
   *
   * @return the second risk officer
   */
  public Optional<Actor> reviewer() {
    return Optional.ofNullable(reviewedBy);
  }

  ConfigChange withBaseline(ConfigSettings baseline) {
    return new ConfigChange(
        changeId,
        direction,
        status,
        proposedBy,
        proposedAt,
        reason,
        baseVersion,
        baseline,
        proposed,
        confirmBy,
        reviewedBy,
        reviewedAt,
        reviewReason,
        effectiveAt,
        revertedAt,
        revertCause);
  }

  ConfigChange withStatus(ChangeStatus newStatus) {
    return new ConfigChange(
        changeId,
        direction,
        newStatus,
        proposedBy,
        proposedAt,
        reason,
        baseVersion,
        previous,
        proposed,
        confirmBy,
        reviewedBy,
        reviewedAt,
        reviewReason,
        effectiveAt,
        revertedAt,
        revertCause);
  }

  ConfigChange reviewed(ChangeStatus newStatus, Actor reviewer, Instant at, String why) {
    return new ConfigChange(
        changeId,
        direction,
        newStatus,
        proposedBy,
        proposedAt,
        reason,
        baseVersion,
        previous,
        proposed,
        confirmBy,
        reviewer,
        at,
        why,
        effectiveAt,
        revertedAt,
        revertCause);
  }

  ConfigChange applied(Instant at) {
    return new ConfigChange(
        changeId,
        direction,
        status,
        proposedBy,
        proposedAt,
        reason,
        baseVersion,
        previous,
        proposed,
        confirmBy,
        reviewedBy,
        reviewedAt,
        reviewReason,
        at,
        revertedAt,
        revertCause);
  }

  ConfigChange reverted(Instant at, RevertCause cause) {
    return new ConfigChange(
        changeId,
        direction,
        ChangeStatus.REVERTED,
        proposedBy,
        proposedAt,
        reason,
        baseVersion,
        previous,
        proposed,
        confirmBy,
        reviewedBy,
        reviewedAt,
        reviewReason,
        effectiveAt,
        at,
        cause);
  }
}
