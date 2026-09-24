package io.github.mariusbayizere.fraudshield.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.ConfigAuditEvent.Action;
import io.github.mariusbayizere.fraudshield.common.config.DualControlException.Refusal;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Asymmetric dual control for thresholds, timeout policies and circuit breakers (ADR 0014). */
@Tag("FR-05-07")
@Tag("FR-02-06")
@Tag("FR-03-07")
class DualControlWorkflowTest {

  private static final Instant START = Instant.parse("2026-09-17T08:00:00Z");
  private static final Actor OFFICER_A =
      new Actor(UUID.fromString("6c2a1b3d-4e5f-4a71-9b8c-0d1e2f3a4b5c"), StaffRole.RISK_OFFICER);
  private static final Actor OFFICER_B =
      new Actor(UUID.fromString("8e4c3d5f-6a71-4c93-9dae-2f3a4b5c6d7e"), StaffRole.RISK_OFFICER);
  private static final Actor ADMIN =
      new Actor(UUID.fromString("7d3b2c4e-5f60-4b82-8c9d-1e2f3a4b5c6d"), StaffRole.ADMIN);

  private final List<ConfigAuditEvent> audit = new ArrayList<>();
  private MutableClock clock;
  private DualControlWorkflow workflow;

  static ChannelThresholds defaults() {
    Map<Channel, ChannelThreshold> byChannel = new EnumMap<>(Channel.class);
    for (Channel channel : Channel.values()) {
      byChannel.put(
          channel,
          ChannelThreshold.of("0.60", "0.85", MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL));
    }
    return new ChannelThresholds(byChannel);
  }

  static CircuitBreakerSettings breakerDefaults() {
    return new CircuitBreakerSettings(
        new BigDecimal("0.05"), Duration.ofMinutes(15), 100, Duration.ofMinutes(60));
  }

  private ChannelThresholds cardHigh(String high) {
    return defaults()
        .with(
            Channel.CARD,
            ChannelThreshold.of("0.60", high, MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL));
  }

  @BeforeEach
  void setUp() {
    clock = new MutableClock(START);
    workflow = new DualControlWorkflow(clock, audit::add, defaults(), breakerDefaults());
  }

  @Test
  void tighteningTakesEffectImmediatelyAndAwaitsConfirmation() {
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");

    assertThat(change.direction()).isEqualTo(ChangeDirection.TIGHTENING);
    assertThat(change.status()).isEqualTo(ChangeStatus.APPLIED_PENDING_CONFIRMATION);
    assertThat(change.effectiveAt()).isEqualTo(START);
    assertThat(change.confirmBy()).isEqualTo(START.plus(Duration.ofHours(24)));
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(cardHigh("0.80"));
    assertThat(workflow.version(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(2);
    assertThat(audit)
        .extracting(ConfigAuditEvent::action)
        .containsExactly(Action.APPLIED_PENDING_CONFIRMATION);
  }

  @Test
  void secondRiskOfficerConfirmsTighteningChange() {
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");
    clock.advance(Duration.ofHours(3));

    ConfigChange confirmed = workflow.approve(OFFICER_B, change.changeId(), null);

    assertThat(confirmed.status()).isEqualTo(ChangeStatus.CONFIRMED);
    assertThat(confirmed.reviewer()).contains(OFFICER_B);
    clock.advance(Duration.ofDays(2));
    assertThat(workflow.revertOverdue()).isEmpty();
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(cardHigh("0.80"));
  }

  @Test
  void unconfirmedTighteningChangeRevertsAfter24Hours() {
    final ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");

    clock.advance(Duration.ofHours(24).minusNanos(1));
    assertThat(workflow.revertOverdue()).isEmpty();
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(cardHigh("0.80"));

    clock.advance(Duration.ofNanos(1));
    List<ConfigChange> reverted = workflow.revertOverdue();

    assertThat(reverted)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.changeId()).isEqualTo(change.changeId());
              assertThat(r.status()).isEqualTo(ChangeStatus.REVERTED);
              assertThat(r.revertCause()).isEqualTo(ConfigChange.RevertCause.NOT_CONFIRMED_IN_TIME);
              assertThat(r.revertedAt()).isEqualTo(START.plus(Duration.ofHours(24)));
            });
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
    assertThat(workflow.version(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(3);
    ConfigAuditEvent revert = audit.get(audit.size() - 1);
    assertThat(revert.action()).isEqualTo(Action.REVERTED);
    assertThat(revert.actingStaff()).isEmpty();
    assertThat(ConfigAuditEvent.EVENT_TYPE).isEqualTo("THRESHOLD_CHANGE");
    assertThatThrownBy(() -> workflow.approve(OFFICER_B, change.changeId(), null))
        .isInstanceOf(DualControlException.class)
        .extracting(e -> ((DualControlException) e).refusal())
        .isEqualTo(Refusal.CHANGE_NOT_OPEN);
  }

  @Test
  void lateConfirmationIsRefusedBecauseTheDeadlineRevertsFirst() {
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");
    clock.advance(Duration.ofHours(25));

    assertRefused(
        () -> workflow.approve(OFFICER_B, change.changeId(), null), Refusal.CHANGE_NOT_OPEN);
    assertThat(workflow.find(change.changeId()))
        .get()
        .extracting(ConfigChange::status)
        .isEqualTo(ChangeStatus.REVERTED);
  }

  @Test
  void rejectingTighteningChangeRevertsItImmediately() {
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");

    ConfigChange rejected = workflow.reject(OFFICER_B, change.changeId(), "blocks salary runs");

    assertThat(rejected.status()).isEqualTo(ChangeStatus.REVERTED);
    assertThat(rejected.revertCause()).isEqualTo(ConfigChange.RevertCause.REJECTED);
    assertThat(rejected.reviewReason()).isEqualTo("blocks salary runs");
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
  }

  @Test
  void looseningStaysPendingUntilSecondRiskOfficerApproves() {
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.90"), 1, "too many false alarms");

    assertThat(change.direction()).isEqualTo(ChangeDirection.LOOSENING);
    assertThat(change.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
    assertThat(change.effectiveAt()).isNull();
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
    assertThat(workflow.open()).containsExactly(change);

    clock.advance(Duration.ofDays(3));
    assertThat(workflow.revertOverdue()).isEmpty();
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());

    ConfigChange approved = workflow.approve(OFFICER_B, change.changeId(), null);

    assertThat(approved.status()).isEqualTo(ChangeStatus.APPROVED);
    assertThat(approved.effectiveAt()).isEqualTo(START.plus(Duration.ofDays(3)));
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(cardHigh("0.90"));
    assertThat(audit)
        .extracting(ConfigAuditEvent::action)
        .containsExactly(Action.PROPOSED, Action.APPROVED);
  }

  @Test
  void rejectedLooseningIsNeverApplied() {
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.90"), 1, "too many false alarms");

    ConfigChange rejected = workflow.reject(OFFICER_B, change.changeId(), "fraud still rising");

    assertThat(rejected.status()).isEqualTo(ChangeStatus.REJECTED);
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
    assertThat(workflow.version(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(1);
  }

  @Test
  void selfApprovalAndSelfRejectionAreForbidden() {
    ConfigChange loosening =
        workflow.propose(OFFICER_A, cardHigh("0.90"), 1, "too many false alarms");
    assertRefused(
        () -> workflow.approve(OFFICER_A, loosening.changeId(), null), Refusal.SELF_REVIEW);
    workflow.reject(OFFICER_B, loosening.changeId(), "not now");

    ConfigChange tightening = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");
    assertRefused(
        () -> workflow.approve(OFFICER_A, tightening.changeId(), null), Refusal.SELF_REVIEW);
    assertRefused(
        () -> workflow.reject(OFFICER_A, tightening.changeId(), "withdraw"), Refusal.SELF_REVIEW);
    assertThat(Refusal.SELF_REVIEW.status()).isEqualTo(403);
  }

  @ParameterizedTest
  @EnumSource(
      value = StaffRole.class,
      names = {"ADMIN", "ANALYST", "SENIOR_ANALYST"})
  void onlyRiskOfficersProposeApproveOrReject(StaffRole role) {
    Actor other = new Actor(ADMIN.userId(), role);
    assertRefused(
        () -> workflow.propose(other, cardHigh("0.80"), 1, "try"), Refusal.ROLE_NOT_PERMITTED);
    ConfigChange change = workflow.propose(OFFICER_A, cardHigh("0.90"), 1, "false alarms");
    assertRefused(
        () -> workflow.approve(other, change.changeId(), null), Refusal.ROLE_NOT_PERMITTED);
    assertRefused(
        () -> workflow.reject(other, change.changeId(), "no"), Refusal.ROLE_NOT_PERMITTED);
    assertThat(Refusal.ROLE_NOT_PERMITTED.status()).isEqualTo(403);
  }

  @Test
  void switchingTheTimeoutPolicyToReleaseIsLoosening() {
    ChannelThresholds declines =
        defaults()
            .with(
                Channel.USSD,
                ChannelThreshold.of("0.60", "0.85", MediumTimeoutPolicy.DECLINE_AND_VERIFY));
    assertThat(declines.directionFrom(defaults())).contains(ChangeDirection.TIGHTENING);
    assertThat(defaults().directionFrom(declines)).contains(ChangeDirection.LOOSENING);
  }

  @Test
  void changeThatBothTightensAndLoosensIsLoosening() {
    ChannelThresholds mixed =
        cardHigh("0.80")
            .with(
                Channel.USSD,
                ChannelThreshold.of(
                    "0.65", "0.85", MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL));
    assertThat(mixed.directionFrom(defaults())).contains(ChangeDirection.LOOSENING);
    assertThat(defaults().directionFrom(defaults())).isEmpty();
  }

  @Test
  void circuitBreakerSettingsFollowTheSameRule() {
    CircuitBreakerSettings base = breakerDefaults();
    CircuitBreakerSettings lowerRate =
        new CircuitBreakerSettings(
            new BigDecimal("0.04"), Duration.ofMinutes(15), 100, Duration.ofMinutes(60));
    CircuitBreakerSettings higherVolume =
        new CircuitBreakerSettings(
            new BigDecimal("0.05"), Duration.ofMinutes(15), 200, Duration.ofMinutes(60));
    CircuitBreakerSettings longerReset =
        new CircuitBreakerSettings(
            new BigDecimal("0.05"), Duration.ofMinutes(15), 100, Duration.ofMinutes(90));
    CircuitBreakerSettings otherWindow =
        new CircuitBreakerSettings(
            new BigDecimal("0.05"), Duration.ofMinutes(30), 100, Duration.ofMinutes(60));
    assertThat(lowerRate.directionFrom(base)).contains(ChangeDirection.TIGHTENING);
    assertThat(longerReset.directionFrom(base)).contains(ChangeDirection.TIGHTENING);
    assertThat(higherVolume.directionFrom(base)).contains(ChangeDirection.LOOSENING);
    assertThat(base.directionFrom(lowerRate)).contains(ChangeDirection.LOOSENING);
    assertThat(otherWindow.directionFrom(base)).contains(ChangeDirection.LOOSENING);

    ConfigChange loosening = workflow.propose(OFFICER_A, higherVolume, 1, "noisy MCC 5732");
    assertThat(loosening.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
    assertThat(workflow.current(ConfigKind.MCC_CIRCUIT_BREAKER)).isEqualTo(base);
    workflow.reject(OFFICER_B, loosening.changeId(), "keep sensitivity");
    ConfigChange tightening = workflow.propose(OFFICER_B, lowerRate, 1, "campaign on MCC 4829");
    assertThat(tightening.status()).isEqualTo(ChangeStatus.APPLIED_PENDING_CONFIRMATION);
    assertThat(workflow.current(ConfigKind.MCC_CIRCUIT_BREAKER)).isEqualTo(lowerRate);
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
  }

  @Test
  void proposalsMustUseTheCurrentVersionAndChangeSomething() {
    workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");
    assertRefused(
        () -> workflow.propose(OFFICER_B, cardHigh("0.75"), 1, "stale"),
        Refusal.STALE_BASE_VERSION);
    assertRefused(
        () -> workflow.propose(OFFICER_B, breakerDefaults(), 1, "same"), Refusal.NO_CHANGE);
    assertThat(Refusal.NO_CHANGE.status()).isEqualTo(422);
  }

  @Test
  void looseningIsRefusedWhileAnotherChangeOfItsKindIsOpen() {
    workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "card fraud wave");
    assertRefused(
        () -> workflow.propose(OFFICER_B, cardHigh("0.90"), 2, "false alarms"),
        Refusal.OPEN_CHANGE_EXISTS);
  }

  @Test
  void tighteningSupersedesPendingLooseningProposal() {
    ConfigChange loosening = workflow.propose(OFFICER_A, cardHigh("0.90"), 1, "false alarms");

    ConfigChange tightening = workflow.propose(OFFICER_B, cardHigh("0.80"), 1, "emergency");

    assertThat(tightening.status()).isEqualTo(ChangeStatus.APPLIED_PENDING_CONFIRMATION);
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(cardHigh("0.80"));
    assertThat(workflow.find(loosening.changeId()))
        .get()
        .extracting(ConfigChange::status)
        .isEqualTo(ChangeStatus.SUPERSEDED);
    assertThat(workflow.open()).containsExactly(tightening);
    assertRefused(
        () -> workflow.approve(OFFICER_B, loosening.changeId(), null), Refusal.CHANGE_NOT_OPEN);
    assertThat(audit)
        .extracting(ConfigAuditEvent::action)
        .containsExactly(Action.PROPOSED, Action.SUPERSEDED, Action.APPLIED_PENDING_CONFIRMATION);
  }

  @Test
  void secondTighteningFoldsInTheFirstSoOneRevertRestoresTheBaseline() {
    ChannelThresholds cardTighter = cardHigh("0.80");
    ChannelThresholds bothTighter =
        cardTighter.with(
            Channel.USSD,
            ChannelThreshold.of("0.55", "0.85", MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL));
    ConfigChange first = workflow.propose(OFFICER_A, cardTighter, 1, "card fraud wave");
    clock.advance(Duration.ofHours(20));

    ConfigChange second = workflow.propose(OFFICER_A, bothTighter, 2, "USSD wave as well");

    assertThat(workflow.find(first.changeId()))
        .get()
        .extracting(ConfigChange::status)
        .isEqualTo(ChangeStatus.SUPERSEDED);
    assertThat(second.previous()).isEqualTo(defaults());
    assertThat(second.confirmBy()).isEqualTo(START.plus(Duration.ofHours(44)));
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(bothTighter);

    clock.advance(Duration.ofHours(24));
    assertThat(workflow.revertOverdue())
        .extracting(ConfigChange::changeId)
        .containsExactly(second.changeId());
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
  }

  @Test
  void proposerCanWithdrawLooseningProposalButNotTighteningChange() {
    ConfigChange loosening = workflow.propose(OFFICER_A, cardHigh("0.90"), 1, "false alarms");
    assertRefused(() -> workflow.withdraw(OFFICER_B, loosening.changeId()), Refusal.NOT_PROPOSER);

    ConfigChange withdrawn = workflow.withdraw(OFFICER_A, loosening.changeId());

    assertThat(withdrawn.status()).isEqualTo(ChangeStatus.WITHDRAWN);
    assertThat(workflow.open()).isEmpty();
    assertThat(workflow.current(ConfigKind.CHANNEL_THRESHOLDS)).isEqualTo(defaults());
    ConfigChange tightening = workflow.propose(OFFICER_A, cardHigh("0.80"), 1, "fraud wave");
    assertRefused(
        () -> workflow.withdraw(OFFICER_A, tightening.changeId()), Refusal.CHANGE_NOT_OPEN);
    assertRefused(
        () -> workflow.withdraw(ADMIN, tightening.changeId()), Refusal.ROLE_NOT_PERMITTED);
    assertThat(audit).extracting(ConfigAuditEvent::action).contains(Action.WITHDRAWN);
  }

  @Test
  void unknownChangesAreNotFound() {
    UUID unknown = UUID.fromString("00000000-0000-4000-8000-000000000000");
    assertRefused(() -> workflow.approve(OFFICER_B, unknown, null), Refusal.CHANGE_NOT_FOUND);
    assertRefused(() -> workflow.reject(OFFICER_B, unknown, "no"), Refusal.CHANGE_NOT_FOUND);
    assertRefused(() -> workflow.withdraw(OFFICER_B, unknown), Refusal.CHANGE_NOT_FOUND);
    assertThat(Refusal.CHANGE_NOT_FOUND.status()).isEqualTo(404);
  }

  @ParameterizedTest
  @CsvSource({
    "0.55, 0.85, RELEASE_WITH_TIMEOUT_LABEL, TIGHTENING",
    "0.65, 0.85, RELEASE_WITH_TIMEOUT_LABEL, LOOSENING",
    "0.60, 0.80, RELEASE_WITH_TIMEOUT_LABEL, TIGHTENING",
    "0.60, 0.90, RELEASE_WITH_TIMEOUT_LABEL, LOOSENING",
    "0.60, 0.85, DECLINE_AND_VERIFY, TIGHTENING"
  })
  void eachThresholdElementIsClassifiedAndAppliedByDirection(
      String medium, String high, MediumTimeoutPolicy policy, ChangeDirection expected) {
    ChannelThresholds changed =
        defaults().with(Channel.ONLINE, ChannelThreshold.of(medium, high, policy));
    assertThat(changed.directionFrom(defaults())).contains(expected);
    ConfigChange change = workflow.propose(OFFICER_A, changed, 1, "per-element check");
    assertThat(change.status())
        .isEqualTo(
            expected == ChangeDirection.TIGHTENING
                ? ChangeStatus.APPLIED_PENDING_CONFIRMATION
                : ChangeStatus.PENDING_APPROVAL);
  }

  @ParameterizedTest
  @CsvSource({
    "0.04, 15, 100, 60, TIGHTENING",
    "0.06, 15, 100, 60, LOOSENING",
    "0.05, 15, 50, 60, TIGHTENING",
    "0.05, 15, 150, 60, LOOSENING",
    "0.05, 15, 100, 90, TIGHTENING",
    "0.05, 15, 100, 30, LOOSENING",
    "0.05, 10, 100, 60, LOOSENING",
    "0.05, 30, 100, 60, LOOSENING"
  })
  void eachCircuitBreakerElementIsClassifiedAndAppliedByDirection(
      String rate, long windowMinutes, int volume, long resetMinutes, ChangeDirection expected) {
    CircuitBreakerSettings changed =
        new CircuitBreakerSettings(
            new BigDecimal(rate),
            Duration.ofMinutes(windowMinutes),
            volume,
            Duration.ofMinutes(resetMinutes));
    assertThat(changed.directionFrom(breakerDefaults())).contains(expected);
    ConfigChange change = workflow.propose(OFFICER_A, changed, 1, "per-element check");
    assertThat(change.status())
        .isEqualTo(
            expected == ChangeDirection.TIGHTENING
                ? ChangeStatus.APPLIED_PENDING_CONFIRMATION
                : ChangeStatus.PENDING_APPROVAL);
    assertThat(workflow.current(ConfigKind.MCC_CIRCUIT_BREAKER))
        .isEqualTo(expected == ChangeDirection.TIGHTENING ? changed : breakerDefaults());
  }

  @Test
  void settingsValidateTheirRanges() {
    assertThatThrownBy(
            () -> ChannelThreshold.of("0.85", "0.85", MediumTimeoutPolicy.DECLINE_AND_VERIFY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChannelThresholds(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CircuitBreakerSettings(
                    BigDecimal.ONE, Duration.ofMinutes(1), 1, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults().directionFrom(breakerDefaults()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertRefused(Runnable action, Refusal refusal) {
    assertThatThrownBy(action::run)
        .isInstanceOf(DualControlException.class)
        .extracting(e -> ((DualControlException) e).refusal())
        .isEqualTo(refusal);
  }
}
