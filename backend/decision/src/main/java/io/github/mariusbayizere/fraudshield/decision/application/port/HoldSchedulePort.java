package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Review deadlines of MEDIUM holds (E.6): a sorted set by deadline, drained by one leader-elected
 * poller every 100 ms.
 */
public interface HoldSchedulePort {

  /**
   * A hold that is due. It carries the HOLD's own sequence-1 state (decision-owner contract §4.2),
   * so that a timeout can create the transaction's owner without waiting for the sink, and detect a
   * second first decision from the hold itself (§7.1). A member scheduled before V81 carries none.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @param channel the transaction's channel
   * @param policy the channel's timeout policy when the hold was decided
   * @param deadline the review deadline, from which lateness is measured
   * @param eventId the HOLD's event id, or null for a member written before V81
   * @param decidedAt when the HOLD was decided, or null with the event id
   * @param reasonCodes the HOLD's reason codes
   * @param dueAt when the hold is next due: the deadline, or a later retry or extended deadline
   */
  record DueHold(
      UUID institutionId,
      UUID transactionId,
      String channel,
      MediumTimeoutPolicy policy,
      Instant deadline,
      UUID eventId,
      Instant decidedAt,
      List<String> reasonCodes,
      Instant dueAt) {

    /** Copies the reasons; a hold is due at its deadline unless told otherwise. */
    public DueHold {
      reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
      dueAt = dueAt == null ? deadline : dueAt;
    }

    /**
     * A hold with no basis, as written before V81.
     *
     * @param institutionId institution
     * @param transactionId transaction
     * @param channel channel
     * @param policy policy
     * @param deadline deadline
     */
    public DueHold(
        UUID institutionId,
        UUID transactionId,
        String channel,
        MediumTimeoutPolicy policy,
        Instant deadline) {
      this(institutionId, transactionId, channel, policy, deadline, null, null, List.of(), null);
    }

    /**
     * The same hold, due at another time (a retry, or an extended deadline).
     *
     * @param at when it is next due
     * @return the hold
     */
    public DueHold dueAgainAt(Instant at) {
      return new DueHold(
          institutionId, transactionId, channel, policy, deadline, eventId, decidedAt, reasonCodes,
          at);
    }
  }

  /**
   * Schedules a deadline.
   *
   * @param hold the hold
   */
  void schedule(DueHold hold);

  /**
   * Removes a hold before its deadline, for an analyst decision. Exactly one of the analyst and the
   * timeout poller removes a given hold.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @return true if this call removed it
   */
  boolean cancel(UUID institutionId, UUID transactionId);

  /**
   * Atomically removes and returns holds whose deadline is at or before {@code now}.
   *
   * @param now current time
   * @param max most holds to claim
   * @return claimed holds, each returned to exactly one caller
   */
  List<DueHold> claimDue(Instant now, int max);

  /**
   * Whether this instance currently holds the poller lease.
   *
   * @param instanceId this instance
   * @return true when this instance is the leader
   */
  boolean acquireLeadership(String instanceId);
}
