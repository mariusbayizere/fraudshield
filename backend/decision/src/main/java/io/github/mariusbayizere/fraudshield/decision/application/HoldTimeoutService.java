package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.HoldTimeout;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The MEDIUM deadline scheduler (E.6, FR-03-02, D-14, D-18): the leader claims due holds every 100
 * ms and applies each channel's timeout policy. A hold an analyst decided first is no longer in the
 * schedule and is never timed out.
 */
public final class HoldTimeoutService {

  /** Most holds processed per tick. */
  public static final int BATCH = 500;

  /** Lateness beyond which a timed-out hold is reported (D-18: ±500 ms). */
  public static final Duration TOLERANCE = Duration.ofMillis(500);

  private final HoldSchedulePort holds;
  private final DecisionStatePort states;
  private final EventRecorder recorder;
  private final DecisionMetrics metrics;
  private final Clock clock;
  private final String instanceId;

  /**
   * Creates the service.
   *
   * @param holds deadline schedule
   * @param states decision states
   * @param recorder durable events
   * @param metrics metrics
   * @param clock clock
   * @param instanceId this instance, for leader election
   */
  public HoldTimeoutService(
      HoldSchedulePort holds,
      DecisionStatePort states,
      EventRecorder recorder,
      DecisionMetrics metrics,
      Clock clock,
      String instanceId) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.states = Objects.requireNonNull(states, "states");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
  }

  /**
   * One scheduler tick: if this instance leads, times out every due hold.
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
        if (timeOut(hold).isPresent()) {
          resolved++;
        }
      }
    } while (due.size() == BATCH);
    return resolved;
  }

  /**
   * Times out one claimed hold.
   *
   * @param hold a hold this caller claimed
   * @return the new state, or empty if the transaction is no longer held
   */
  Optional<DecisionState> timeOut(HoldSchedulePort.DueHold hold) {
    Optional<DecisionState> latest = states.latest(hold.institutionId(), hold.transactionId());
    if (latest.isEmpty() || latest.get().decision() != DecisionValue.HOLD) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    DecisionState next = HoldTimeout.resolve(latest.get(), hold.policy(), now);
    recorder.record(List.of(new DecisionEvent.DecisionChanged(next, hold.channel(), null)));
    states.save(next);
    if (next.decision() == DecisionValue.TIMEOUT_RELEASE) {
      metrics.timeoutRelease(hold.channel());
    }
    long late = Duration.between(hold.deadline(), now).toMillis();
    if (late > TOLERANCE.toMillis()) {
      metrics.holdLateness(late);
    }
    return Optional.of(next);
  }
}
