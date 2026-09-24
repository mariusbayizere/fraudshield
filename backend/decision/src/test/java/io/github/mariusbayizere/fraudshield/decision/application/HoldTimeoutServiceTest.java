package io.github.mariusbayizere.fraudshield.decision.application;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
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
  private DecisionTransitionService transitions;
  private final List<String> released = new ArrayList<>();
  private final List<Long> late = new ArrayList<>();

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
    timeouts = new HoldTimeoutService(ports, ports, ports, metrics, clock, "api-0");
    transitions = new DecisionTransitionService(ports, ports, ports, clock);
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
  void analystDecisionsBeforeTheDeadlineWinAndAreNeverTimedOut() {
    UUID tx = hold();
    clock.advance(Duration.ofSeconds(10));
    transitions.analystDecidesHold(
        Fixtures.INSTITUTION, tx, false, UUID.randomUUID(), "MOBILE_MONEY", NOW);
    clock.advance(Duration.ofSeconds(30));
    assertThat(timeouts.tick()).isZero();
    assertThat(ports.latest.get(tx).decision()).isEqualTo(DecisionValue.DECLINE);
    assertThat(ports.latest.get(tx).decidedBy()).isEqualTo(DecidedBy.ANALYST);
    assertThat(ports.eventsOf(DecisionEvent.LabelRecorded.class))
        .singleElement()
        .extracting(DecisionEvent.LabelRecorded::fraud)
        .isEqualTo(true);
  }

  @Test
  void analystsAfterTheTimeoutAreRefused() {
    UUID tx = hold();
    clock.advance(Duration.ofSeconds(30));
    timeouts.tick();
    assertThatThrownBy(
            () ->
                transitions.analystDecidesHold(
                    Fixtures.INSTITUTION, tx, true, UUID.randomUUID(), "MOBILE_MONEY", NOW))
        .isInstanceOf(DecisionTransitionService.TransitionRefusedException.class)
        .extracting(e -> ((DecisionTransitionService.TransitionRefusedException) e).refusal())
        .isEqualTo(DecisionTransitionService.Refusal.REVIEW_DEADLINE_PASSED);
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
                    NOW)))
        .isEmpty();
  }
}
