package io.github.mariusbayizere.fraudshield.decision.testing;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldOwnerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.ReasonCodes;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory stand-in for the transaction's owner, for the scheduler's unit tests: it times a
 * hold out when the transaction is still HOLD, unclaimed and due, as the owner's rule table does
 * (decision-owner contract §5), and records the new state on {@link InMemoryPorts}. The owner's own
 * rules are tested against PostgreSQL in {@code DecisionOwnerTest}.
 */
public final class InMemoryHoldOwner implements HoldOwnerPort {

  private final InMemoryPorts ports;
  private final Clock clock;

  /** Transactions an analyst has claimed: the owner drops their timeout. */
  public final Set<UUID> claimed = new HashSet<>();

  /** Deadlines an escalation extended: the owner asks to reschedule. */
  public final Map<UUID, Instant> extended = new HashMap<>();

  /** When set, every request fails, as with PostgreSQL down. */
  public boolean failing;

  /** When set, no first decision is visible yet. */
  public boolean noFirstDecision;

  /**
   * Creates the stand-in.
   *
   * @param ports the in-memory ports
   * @param clock clock
   */
  public InMemoryHoldOwner(InMemoryPorts ports, Clock clock) {
    this.ports = ports;
    this.clock = clock;
  }

  @Override
  public Answer timeOut(HoldSchedulePort.DueHold hold) {
    if (failing) {
      throw new IllegalStateException("PostgreSQL is unavailable (test double)");
    }
    if (noFirstDecision) {
      return new Answer(Next.RETRY, null, null);
    }
    DecisionState latest = ports.latest.get(hold.transactionId());
    if (latest == null || latest.decision() != DecisionValue.HOLD) {
      return new Answer(Next.DROP, null, null);
    }
    if (claimed.contains(hold.transactionId())) {
      return new Answer(Next.DROP, null, null);
    }
    Instant deadline = extended.getOrDefault(hold.transactionId(), hold.deadline());
    Instant now = clock.instant();
    if (now.isBefore(deadline)) {
      return new Answer(Next.RESCHEDULE, deadline, null);
    }
    boolean decline = hold.policy() == MediumTimeoutPolicy.DECLINE_AND_VERIFY;
    DecisionState next =
        DecisionState.succeeding(
            latest.institutionId(),
            latest.transactionId(),
            latest.sequence(),
            latest.decision(),
            latest.decidedBy(),
            UUID.randomUUID(),
            decline ? DecisionValue.DECLINE : DecisionValue.TIMEOUT_RELEASE,
            DecidedBy.TIMEOUT_POLICY,
            now,
            List.of(decline ? ReasonCodes.REVIEW_TIMEOUT_DECLINED : ReasonCodes.REVIEW_TIMEOUT),
            false);
    ports.record(List.of(new DecisionEvent.DecisionChanged(next, hold.channel(), null)));
    ports.save(next);
    return new Answer(Next.APPLIED, null, next);
  }
}
