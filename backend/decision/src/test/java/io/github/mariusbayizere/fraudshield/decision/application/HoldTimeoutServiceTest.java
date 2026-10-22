package io.github.mariusbayizere.fraudshield.decision.application;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryHoldOwner;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FR-03-02")
@Tag("D-14")
@Tag("D-18")
@Tag("TEST-03")
class HoldTimeoutServiceTest {

  private InMemoryPorts ports;
  private MutableClock clock;
  private DecisionService decisions;
  private HoldTimeoutService timeouts;
  private InMemoryHoldOwner owner;
  private final List<String> released = new ArrayList<>();
  private final List<Long> late = new ArrayList<>();
  private final List<String> retried = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ports = new InMemoryPorts();
    ports.scores = t -> Optional.of(0.7);
    clock = new MutableClock(NOW);
    DecisionMetrics metrics =
        new DecisionMetrics() {
          @Override
          public void decided(
              Transaction t,
              io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome o,
              long n) {}

          @Override
          public void timeoutRelease(String channel) {
            released.add(channel);
          }

          @Override
          public void holdLateness(long millis) {
            late.add(millis);
          }

          @Override
          public void holdRequestRetried() {
            retried.add("retried");
          }
        };
    decisions =
        new DecisionService(
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            metrics,
            DecisionSettings.DEFAULTS,
            clock);
    owner = new InMemoryHoldOwner(ports, clock);
    timeouts = new HoldTimeoutService(ports, owner, metrics, clock, "api-0");
  }

  private UUID hold() {
    Transaction transaction = transaction("15000");
    decisions.decide(transaction, new byte[32], System.nanoTime());
    return transaction.transactionId();
  }

  @Test
  void holdsAreReleasedWithTheTimeoutLabelAtThirtySecondsNotBefore() {
    final UUID tx = hold();
    clock.advance(Duration.ofMillis(29_999));
    assertThat(timeouts.tick()).isZero();
    clock.advance(Duration.ofMillis(1));
    assertThat(timeouts.tick()).isEqualTo(1);
    DecisionEvent.DecisionChanged changed =
        ports.eventsOf(DecisionEvent.DecisionChanged.class).getFirst();
    assertThat(changed.state().decision()).isEqualTo(DecisionValue.TIMEOUT_RELEASE);
    assertThat(changed.state().decidedBy()).isEqualTo(DecidedBy.TIMEOUT_POLICY);
    assertThat(changed.state().decidedAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(ports.latest.get(tx).sequence()).isEqualTo(2);
    assertThat(released).containsExactly("MOBILE_MONEY");
    assertThat(timeouts.tick()).isZero();
  }

  @Test
  void declineAndVerifyDeclinesAtTheDeadline() {
    UUID tx = UUID.randomUUID();
    ports.latest.put(
        tx,
        io.github.mariusbayizere.fraudshield.decision.domain.DecisionState.initial(
            Fixtures.INSTITUTION,
            tx,
            io.github.mariusbayizere.fraudshield.decision.domain.Decision.HOLD,
            NOW,
            List.of(),
            NOW.plusSeconds(30)));
    ports.schedule(
        new HoldSchedulePort.DueHold(
            Fixtures.INSTITUTION,
            tx,
            "USSD",
            MediumTimeoutPolicy.DECLINE_AND_VERIFY,
            NOW.plusSeconds(30)));
    clock.advance(Duration.ofSeconds(31));
    assertThat(timeouts.tick()).isEqualTo(1);
    assertThat(ports.latest.get(tx).decision()).isEqualTo(DecisionValue.DECLINE);
    assertThat(released).isEmpty();
    assertThat(late).containsExactly(1000L);
  }

  @Test
  void holdTheOwnerGaveToAnalystIsDroppedNotTimedOut() {
    UUID tx = hold();
    owner.claimed.add(tx);
    clock.advance(Duration.ofSeconds(30));
    assertThat(timeouts.tick()).isZero();
    assertThat(ports.latest.get(tx).decision()).isEqualTo(DecisionValue.HOLD);
    assertThat(ports.scheduled(tx)).isEmpty();
  }

  @Test
  void holdWhoseEscalationExtendedItIsRescheduledAtTheNewDeadline() {
    UUID tx = hold();
    owner.extended.put(tx, NOW.plusSeconds(900));
    clock.advance(Duration.ofSeconds(30));
    assertThat(timeouts.tick()).isZero();
    assertThat(ports.scheduled(tx))
        .get()
        .satisfies(
            h -> {
              assertThat(h.dueAt()).isEqualTo(NOW.plusSeconds(900));
              assertThat(h.deadline()).isEqualTo(NOW.plusSeconds(30));
            });
  }

  @Test
  void failedRequestKeepsTheHoldAndRetriesItOneSecondLater() {
    final UUID tx = hold();
    owner.failing = true;
    clock.advance(Duration.ofSeconds(30));
    assertThat(timeouts.tick()).isZero();
    assertThat(retried).hasSize(1);
    assertThat(ports.scheduled(tx)).get().extracting(h -> h.dueAt()).isEqualTo(NOW.plusSeconds(31));
    owner.failing = false;
    clock.advance(Duration.ofSeconds(1));
    assertThat(timeouts.tick()).isEqualTo(1);
    assertThat(late).containsExactly(1000L);
  }

  @Test
  void oneFailureNeverLosesTheRestOfTheClaimedBatch() {
    final List<UUID> held = List.of(hold(), hold(), hold());
    owner.failing = true;
    clock.advance(Duration.ofSeconds(30));
    timeouts.tick();
    for (UUID tx : held) {
      assertThat(ports.scheduled(tx)).as("hold %s kept", tx).isPresent();
    }
  }

  @Test
  void holdWithNoFirstDecisionYetIsRetried() {
    final UUID tx = hold();
    owner.noFirstDecision = true;
    clock.advance(Duration.ofSeconds(30));
    assertThat(timeouts.tick()).isZero();
    assertThat(ports.scheduled(tx)).isPresent();
  }

  @Test
  void onlyTheLeaderProcessesHolds() {
    hold();
    ports.leader = false;
    clock.advance(Duration.ofSeconds(31));
    assertThat(timeouts.tick()).isZero();
    ports.leader = true;
    assertThat(timeouts.tick()).isEqualTo(1);
  }

  @Test
  void claimedHoldsWhoseStateMovedOnAreSkipped() {
    UUID tx = UUID.randomUUID();
    assertThat(
            timeouts.timeOut(
                new HoldSchedulePort.DueHold(
                    Fixtures.INSTITUTION,
                    tx,
                    "CARD",
                    MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL,
                    NOW),
                true))
        .isFalse();
  }
}
