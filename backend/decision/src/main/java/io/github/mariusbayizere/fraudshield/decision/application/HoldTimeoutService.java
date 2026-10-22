package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldOwnerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.OverdueHoldsPort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The MEDIUM deadline scheduler (E.6, FR-03-02, D-14, D-18). The leader claims due holds every 100
 * ms; each becomes a TIMEOUT request to its transaction's owner (decision-owner contract §6.3),
 * which alone decides whether the hold times out. A hold an analyst decided is dropped by the
 * owner, because the analyst's claim stands; a hold whose escalation extended it is rescheduled.
 *
 * <p>A hold already taken from the schedule is never lost: if its request fails for any reason, or
 * finds no first decision yet, it is rescheduled 1 s later and counted, one hold at a time, so one
 * failure never loses the rest of a claimed batch (§2). During a PostgreSQL outage every hold is
 * therefore kept, which is ADR 0111's decision.
 */
public final class HoldTimeoutService {

  /** Most holds processed per tick. */
  public static final int BATCH = 500;

  /** Lateness beyond which a timed-out hold is reported (D-18: ±500 ms). */
  public static final Duration TOLERANCE = Duration.ofMillis(500);

  /** How long after a failed or deferred request its hold is tried again. */
  public static final Duration RETRY = Duration.ofSeconds(1);

  private final HoldSchedulePort holds;
  private final HoldOwnerPort owner;
  private final DecisionMetrics metrics;
  private final Clock clock;
  private final String instanceId;

  /**
   * Creates the service.
   *
   * @param holds deadline schedule
   * @param owner the TIMEOUT request to the transaction's owner
   * @param metrics metrics
   * @param clock clock
   * @param instanceId this instance, for leader election
   */
  public HoldTimeoutService(
      HoldSchedulePort holds,
      HoldOwnerPort owner,
      DecisionMetrics metrics,
      Clock clock,
      String instanceId) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
  }

  /**
   * One scheduler tick: if this instance leads, requests the timeout of every due hold.
   *
   * @return holds timed out
   */
  public int tick() {
    if (!holds.acquireLeadership(instanceId)) {
      return 0;
    }
    int resolved = 0;
    List<HoldSchedulePort.DueHold> due;
    do {
      due = holds.claimDue(clock.instant(), BATCH);
      for (HoldSchedulePort.DueHold hold : due) {
        if (timeOut(hold, true)) {
          resolved++;
        }
      }
    } while (due.size() == BATCH);
    return resolved;
  }

  /**
   * The safety net (E.6, D-14): requests the timeout of holds the overdue sweep lists, whatever
   * happened to their schedule entry (§6.3). Leader only. The owner makes a repeated timeout a
   * no-op: its request id names only the institution and the transaction.
   *
   * @param overdue durable overdue holds
   * @param grace how long after a deadline the sweep waits for the scheduler
   * @return holds timed out by the sweep
   */
  public int reconcile(OverdueHoldsPort overdue, Duration grace) {
    if (!holds.acquireLeadership(instanceId)) {
      return 0;
    }
    int resolved = 0;
    for (HoldSchedulePort.DueHold hold : overdue.overdue(clock.instant().minus(grace), BATCH)) {
      if (timeOut(hold, false)) {
        resolved++;
      }
    }
    return resolved;
  }

  /**
   * Requests one hold's timeout and acts on the answer.
   *
   * @param hold the hold
   * @param scheduled whether it was taken from the schedule, so must be put back unless done
   * @return whether the timeout was applied now
   */
  boolean timeOut(HoldSchedulePort.DueHold hold, boolean scheduled) {
    HoldOwnerPort.Answer answer;
    try {
      answer = owner.timeOut(hold);
    } catch (RuntimeException failed) {
      retry(hold, scheduled);
      return false;
    }
    switch (answer.next()) {
      case APPLIED -> {
        Instant now = clock.instant();
        if (answer.state() != null && answer.state().decision() == DecisionValue.TIMEOUT_RELEASE) {
          metrics.timeoutRelease(hold.channel());
        }
        long late = Duration.between(hold.deadline(), now).toMillis();
        if (late > TOLERANCE.toMillis()) {
          metrics.holdLateness(late);
        }
        return true;
      }
      case RESCHEDULE -> reschedule(hold.dueAgainAt(answer.at()));
      case RETRY -> retry(hold, scheduled);
      default -> {
        // DROP: the owner decided the hold is no longer the timeout's (§6.3).
      }
    }
    return false;
  }

  private void retry(HoldSchedulePort.DueHold hold, boolean scheduled) {
    metrics.holdRequestRetried();
    if (scheduled) {
      reschedule(hold.dueAgainAt(clock.instant().plus(RETRY)));
    }
  }

  private void reschedule(HoldSchedulePort.DueHold hold) {
    try {
      holds.schedule(hold);
    } catch (RuntimeException failed) {
      // The schedule falls back to this process while Redis is down (ResilientPorts); if even that
      // fails, the overdue sweep times the hold out 10 to 15 s after its deadline.
      metrics.stateWriteFailed("hold_schedule");
    }
  }
}
