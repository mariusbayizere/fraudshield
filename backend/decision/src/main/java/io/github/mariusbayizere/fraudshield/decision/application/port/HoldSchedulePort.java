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
   * A hold that is due.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @param channel the transaction's channel
   * @param policy the channel's timeout policy when the hold was decided
   * @param deadline the review deadline
   */
  record DueHold(
      UUID institutionId,
      UUID transactionId,
      String channel,
      MediumTimeoutPolicy policy,
      Instant deadline) {}

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
