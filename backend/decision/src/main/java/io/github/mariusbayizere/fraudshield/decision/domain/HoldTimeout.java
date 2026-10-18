package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import java.time.Instant;
import java.util.List;

/** What happens to a HOLD whose review deadline passes without an analyst decision (D-10). */
public final class HoldTimeout {

  private HoldTimeout() {}

  /**
   * The timed-out state: TIMEOUT_RELEASE under RELEASE_WITH_TIMEOUT_LABEL (the SRS default), or
   * DECLINE under DECLINE_AND_VERIFY.
   *
   * @param hold the HOLD state
   * @param policy the channel's timeout policy
   * @param at when the deadline was processed
   * @return the next state, decided by TIMEOUT_POLICY
   */
  public static DecisionState resolve(DecisionState hold, MediumTimeoutPolicy policy, Instant at) {
    if (hold.decision() != DecisionValue.HOLD) {
      throw new IllegalStateException("only a HOLD times out");
    }
    return switch (policy) {
      case RELEASE_WITH_TIMEOUT_LABEL ->
          hold.next(
              DecisionValue.TIMEOUT_RELEASE,
              DecidedBy.TIMEOUT_POLICY,
              at,
              List.of(ReasonCodes.REVIEW_TIMEOUT));
      case DECLINE_AND_VERIFY ->
          hold.next(
              DecisionValue.DECLINE,
              DecidedBy.TIMEOUT_POLICY,
              at,
              List.of(ReasonCodes.REVIEW_TIMEOUT_DECLINED));
    };
  }
}
