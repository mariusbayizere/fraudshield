package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import java.time.Instant;

/**
 * A hold's TIMEOUT request to its transaction's owner (decision-owner contract §5, §6.3). The
 * adapter runs the request in its own transaction and records an applied state after the commit.
 */
public interface HoldOwnerPort {

  /** What the timeout path does with the hold next. */
  enum Next {
    /** The timeout was applied; the hold is done. */
    APPLIED,
    /** The deadline has not come: reschedule at {@link Answer#at()}. */
    RESCHEDULE,
    /** No first decision is visible yet: retry 1 s later. */
    RETRY,
    /** The payment is not held any more, an analyst owns it, or it is contested: drop. */
    DROP
  }

  /**
   * The owner's answer.
   *
   * @param next what to do with the hold
   * @param at for RESCHEDULE, when
   * @param state for APPLIED, the new state
   */
  record Answer(Next next, Instant at, DecisionState state) {}

  /**
   * Requests the hold's timeout.
   *
   * @param hold the claimed hold
   * @return the answer
   * @throws RuntimeException when the request's transaction fails; the caller reschedules
   */
  Answer timeOut(HoldSchedulePort.DueHold hold);
}
