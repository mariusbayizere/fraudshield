package io.github.mariusbayizere.fraudshield.decision.application.port;

import java.util.UUID;

/**
 * Hands a changed alert queue entry to the real-time channel's publisher, after the writing
 * transaction commits (ADR 0115 point 6.2). It only offers ids to a bounded queue and never blocks,
 * so a Kafka problem can never stall a decision, a timeout or the sink. Best effort: a lost offer
 * is repaired by the console's next re-read.
 */
public interface AlertChangePublisher {

  /** What changed (ADR 0115 point 6.2). */
  enum Change {
    /** D1: an analyst decision. */
    DECIDED,
    /** D2: an undo. */
    UNDONE,
    /** D6: the original entry, escalated. */
    ESCALATED,
    /** D6: the new, escalated entry. */
    ESCALATION_CREATED,
    /** D5: an override. */
    OVERRIDDEN,
    /** A TIMEOUT closed the entry, or the sink closed a late entry after one. */
    TIMED_OUT,
    /** A RESOLVE closed the entry, or the sink closed a late entry after one. */
    RESOLVED,
    /** ADR 0116: an assignment. */
    ASSIGNED
  }

  /**
   * Offers a change for publication. Never blocks; drops and counts when the queue is full.
   *
   * @param institutionId the changed row's institution, read in the writing tenant transaction
   * @param alertId the entry
   * @param version the entry's version after the change
   * @param change what changed
   * @param actorUserId the person who made it, or null
   */
  void offer(UUID institutionId, UUID alertId, long version, Change change, UUID actorUserId);

  /** Publishes nothing: the application has no real-time channel. */
  AlertChangePublisher NONE = (institutionId, alertId, version, change, actorUserId) -> {};
}
