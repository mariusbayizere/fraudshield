package io.github.mariusbayizere.fraudshield.decision.domain;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D-14 and ADR 0011 section 9: the allowed decision transitions. */
@Tag("D-14")
@Tag("FR-03-02")
class DecisionStateTest {

  private static DecisionState hold() {
    return DecisionState.initial(
        INSTITUTION,
        UUID.randomUUID(),
        Decision.HOLD,
        NOW,
        List.of("VELOCITY_SPIKE"),
        NOW.plusSeconds(30));
  }

  @Test
  void everyAllowedTransitionAndNoOther() {
    for (DecisionValue from : DecisionValue.values()) {
      for (DecisionValue to : DecisionValue.values()) {
        for (DecidedBy by : DecidedBy.values()) {
          boolean expected =
              switch (from) {
                case HOLD ->
                    (by == DecidedBy.ANALYST
                            && (to == DecisionValue.APPROVE || to == DecisionValue.DECLINE))
                        || (by == DecidedBy.TIMEOUT_POLICY
                            && (to == DecisionValue.TIMEOUT_RELEASE
                                || to == DecisionValue.DECLINE));
                case DECLINE ->
                    to == DecisionValue.APPROVE
                        && (by == DecidedBy.CUSTOMER_VERIFICATION
                            || by == DecidedBy.SENIOR_OVERRIDE);
                case APPROVE, TIMEOUT_RELEASE ->
                    to == DecisionValue.DECLINE && by == DecidedBy.SENIOR_OVERRIDE;
              };
          assertThat(DecisionState.allowed(from, to, by))
              .as(from + "->" + to + " by " + by)
              .isEqualTo(expected);
        }
      }
    }
  }

  @Test
  void sequencesIncreaseByOneAndNameThePredecessor() {
    DecisionState hold = hold();
    DecisionState declined = hold.next(DecisionValue.DECLINE, DecidedBy.ANALYST, NOW, List.of());
    DecisionState approved =
        declined.next(DecisionValue.APPROVE, DecidedBy.CUSTOMER_VERIFICATION, NOW, List.of());
    DecisionState overridden =
        approved.next(DecisionValue.DECLINE, DecidedBy.SENIOR_OVERRIDE, NOW, List.of());
    assertThat(
            List.of(
                hold.sequence(), declined.sequence(), approved.sequence(), overridden.sequence()))
        .containsExactly(1, 2, 3, 4);
    assertThat(overridden.supersedes()).isEqualTo(DecisionValue.APPROVE);
    assertThat(hold.isFinal()).isFalse();
    assertThat(overridden.isFinal()).isTrue();
    assertThat(overridden.eventId()).isNotEqualTo(approved.eventId());
    assertThatThrownBy(() -> overridden.next(DecisionValue.HOLD, DecidedBy.ANALYST, NOW, List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void timeoutFollowsTheChannelPolicy() {
    DecisionState released =
        HoldTimeout.resolve(hold(), MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL, NOW);
    assertThat(released.decision()).isEqualTo(DecisionValue.TIMEOUT_RELEASE);
    assertThat(released.decidedBy()).isEqualTo(DecidedBy.TIMEOUT_POLICY);
    assertThat(released.reasonCodes()).containsExactly(ReasonCodes.REVIEW_TIMEOUT);
    DecisionState declined =
        HoldTimeout.resolve(hold(), MediumTimeoutPolicy.DECLINE_AND_VERIFY, NOW);
    assertThat(declined.decision()).isEqualTo(DecisionValue.DECLINE);
    assertThatThrownBy(
            () -> HoldTimeout.resolve(released, MediumTimeoutPolicy.DECLINE_AND_VERIFY, NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void structuralRulesAreEnforced() {
    UUID tx = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new DecisionState(
                    UUID.randomUUID(),
                    INSTITUTION,
                    tx,
                    1,
                    DecisionValue.HOLD,
                    NOW,
                    DecidedBy.MODEL,
                    List.of(),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionState(
                    UUID.randomUUID(),
                    INSTITUTION,
                    tx,
                    2,
                    DecisionValue.APPROVE,
                    NOW,
                    DecidedBy.MODEL,
                    List.of(),
                    null,
                    DecisionValue.HOLD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionState(
                    UUID.randomUUID(),
                    INSTITUTION,
                    tx,
                    2,
                    DecisionValue.HOLD,
                    NOW,
                    DecidedBy.ANALYST,
                    List.of(),
                    NOW,
                    DecisionValue.HOLD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionState(
                    UUID.randomUUID(),
                    INSTITUTION,
                    tx,
                    0,
                    DecisionValue.APPROVE,
                    NOW,
                    DecidedBy.MODEL,
                    List.of(),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
