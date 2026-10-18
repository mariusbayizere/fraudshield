package io.github.mariusbayizere.fraudshield.decision.application.port;

import java.time.Instant;
import java.util.List;

/**
 * Durable view of holds whose deadline passed without a later decision: the safety net for a hold
 * whose schedule entry was lost (Redis failed over empty, or the process died between recording the
 * decision and scheduling it).
 */
public interface OverdueHoldsPort {

  /**
   * Holds with a persisted HOLD state, a deadline before {@code before}, and no later state.
   *
   * @param before deadline cut-off
   * @param max most holds to return
   * @return overdue holds, as schedule entries
   */
  List<HoldSchedulePort.DueHold> overdue(Instant before, int max);
}
