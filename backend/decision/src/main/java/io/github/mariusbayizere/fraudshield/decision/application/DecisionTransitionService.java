package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decision changes after ingest (D-14): an analyst deciding a hold before its deadline, and the
 * customer answering the verification page (FR-03-05). Staff endpoints (M7, M8) call the first; the
 * verification page calls the second.
 */
public final class DecisionTransitionService {

  private final HoldSchedulePort holds;
  private final DecisionStatePort states;
  private final EventRecorder recorder;
  private final Clock clock;

  /** Why a transition was refused. */
  public enum Refusal {
    /** The transaction is unknown to this institution. */
    NOT_FOUND,
    /** The hold's deadline passed and the timeout policy already decided (D-29). */
    REVIEW_DEADLINE_PASSED,
    /** The current state does not allow this transition. */
    NOT_ALLOWED
  }

  /** A transition that could not be applied. */
  public static final class TransitionRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Refusal refusal;

    /**
     * Creates the exception.
     *
     * @param refusal why
     */
    public TransitionRefusedException(Refusal refusal) {
      super(refusal.name());
      this.refusal = refusal;
    }

    /**
     * Why the transition was refused.
     *
     * @return the refusal
     */
    public Refusal refusal() {
      return refusal;
    }
  }

  /**
   * Creates the service.
   *
   * @param holds deadline schedule
   * @param states decision states
   * @param recorder durable events
   * @param clock clock
   */
  public DecisionTransitionService(
      HoldSchedulePort holds, DecisionStatePort states, EventRecorder recorder, Clock clock) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.states = Objects.requireNonNull(states, "states");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * An analyst decides a held transaction before its deadline. Removing the hold from the schedule
   * is the lock: exactly one of the analyst and the timeout poller removes it.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @param approve true to approve, false to decline
   * @param analystId the analyst
   * @param channel the transaction's channel
   * @param transactionTimestamp the transaction's time, for the label
   * @return the new state
   * @throws TransitionRefusedException when the hold is gone or already decided
   */
  public DecisionState analystDecidesHold(
      UUID institutionId,
      UUID transactionId,
      boolean approve,
      UUID analystId,
      String channel,
      Instant transactionTimestamp) {
    DecisionState current = current(institutionId, transactionId);
    if (current.decision() != DecisionValue.HOLD) {
      throw new TransitionRefusedException(Refusal.REVIEW_DEADLINE_PASSED);
    }
    if (!holds.cancel(institutionId, transactionId)) {
      throw new TransitionRefusedException(Refusal.REVIEW_DEADLINE_PASSED);
    }
    Instant now = clock.instant();
    DecisionState next =
        current.next(
            approve ? DecisionValue.APPROVE : DecisionValue.DECLINE,
            DecidedBy.ANALYST,
            now,
            List.of());
    List<DecisionEvent> events = new ArrayList<>();
    events.add(new DecisionEvent.DecisionChanged(next, channel, analystId));
    events.add(
        new DecisionEvent.LabelRecorded(
            UUID.randomUUID(),
            institutionId,
            transactionId,
            !approve,
            "ANALYST",
            transactionTimestamp,
            now));
    recorder.record(events);
    states.save(next);
    return next;
  }

  /**
   * The customer answered the verification page (FR-03-05, E.7). "Yes, this was me" lifts the block
   * (DECLINE → APPROVE by CUSTOMER_VERIFICATION) and records a LEGITIMATE label for retraining;
   * "No" records a FRAUD label and leaves the decision declined. Self-service eligibility (D-25) is
   * the caller's to check before calling with {@code wasMe = true}.
   *
   * @param institutionId institution
   * @param transactionId the blocked transaction
   * @param wasMe the customer's answer
   * @param transactionTimestamp the transaction's time, for the label
   * @return the latest state after the answer
   * @throws TransitionRefusedException when the transaction is unknown or no longer declined
   */
  public DecisionState customerAnswered(
      UUID institutionId, UUID transactionId, boolean wasMe, Instant transactionTimestamp) {
    DecisionState current = current(institutionId, transactionId);
    Instant now = clock.instant();
    DecisionEvent.LabelRecorded label =
        new DecisionEvent.LabelRecorded(
            UUID.randomUUID(),
            institutionId,
            transactionId,
            !wasMe,
            "CUSTOMER",
            transactionTimestamp,
            now);
    if (!wasMe) {
      recorder.record(List.of(label));
      return current;
    }
    if (current.decision() != DecisionValue.DECLINE) {
      throw new TransitionRefusedException(Refusal.NOT_ALLOWED);
    }
    DecisionState next =
        current.next(DecisionValue.APPROVE, DecidedBy.CUSTOMER_VERIFICATION, now, List.of());
    recorder.record(List.of(new DecisionEvent.DecisionChanged(next, null, null), label));
    states.save(next);
    return next;
  }

  private DecisionState current(UUID institutionId, UUID transactionId) {
    return states
        .latest(institutionId, transactionId)
        .orElseThrow(() -> new TransitionRefusedException(Refusal.NOT_FOUND));
  }
}
