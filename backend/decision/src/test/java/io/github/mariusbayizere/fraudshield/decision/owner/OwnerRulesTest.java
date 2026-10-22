package io.github.mariusbayizere.fraudshield.decision.owner;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Outcome;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Refusal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The rule table (decision-owner contract §5), one request against one owner row at a time. */
@Tag("FR-03-02")
@Tag("FR-03-05")
@Tag("FR-04-10")
@Tag("D-44")
class OwnerRulesTest {

  private static final UUID TX = UUID.randomUUID();
  private static final UUID FIRST = UUID.randomUUID();
  private static final Instant DEADLINE = NOW.plusSeconds(30);
  private static final OwnerRules.Facts NONE = OwnerRules.Facts.NONE;
  private static final OwnerRules.Facts BLOCKED = new OwnerRules.Facts(false, true, false);

  private static OwnerState held() {
    return new OwnerState(
        INSTITUTION,
        TX,
        FIRST,
        DecisionValue.HOLD,
        DEADLINE,
        MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL,
        1,
        DecisionValue.HOLD,
        DecidedBy.MODEL,
        Claim.NONE,
        null,
        DEADLINE,
        null,
        null,
        null,
        null);
  }

  private static OwnerState state(int sequence, DecisionValue value, DecidedBy by) {
    return new OwnerState(
        INSTITUTION,
        TX,
        FIRST,
        DecisionValue.DECLINE,
        null,
        null,
        sequence,
        value,
        by,
        Claim.NONE,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static OwnerRequest.Timeout timeout(DecisionValue basis) {
    return new OwnerRequest.Timeout(
        UUID.randomUUID(),
        basis,
        DEADLINE,
        NOW,
        List.of(),
        MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL,
        "CARD");
  }

  private static Verdict evaluate(OwnerState o, OwnerRequest r, Instant at) {
    return OwnerRules.evaluate(o, r, at, NONE);
  }

  // ------------------------------------------------------------------------------ TIMEOUT

  @Test
  void timeoutAppliesOnlyAtTheDeadlineAndReleasesUnderItsPolicy() {
    Verdict early = evaluate(held(), timeout(DecisionValue.HOLD), DEADLINE.minusMillis(1));
    assertThat(early.outcome()).isEqualTo(Outcome.REFUSED);
    assertThat(early.reschedule()).isEqualTo(DEADLINE);

    Verdict due = evaluate(held(), timeout(DecisionValue.HOLD), DEADLINE);
    assertThat(due.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(due.transition().value()).isEqualTo(DecisionValue.TIMEOUT_RELEASE);
    assertThat(due.transition().by()).isEqualTo(DecidedBy.TIMEOUT_POLICY);
    assertThat(due.closeEntries()).isEqualTo(Verdict.Closing.TIMEOUT);
    assertThat(due.owner().sequence()).isEqualTo(2);
    assertThat(due.owner().claim()).isEqualTo(Claim.NONE);
  }

  @Test
  void timeoutUnderDeclineAndVerifyDeclines() {
    OwnerState o =
        new OwnerState(
            INSTITUTION, TX, FIRST, DecisionValue.HOLD, DEADLINE,
            MediumTimeoutPolicy.DECLINE_AND_VERIFY, 1, DecisionValue.HOLD, DecidedBy.MODEL,
            Claim.NONE, null, DEADLINE, null, null, null, null);
    assertThat(evaluate(o, timeout(DecisionValue.HOLD), DEADLINE).transition().value())
        .isEqualTo(DecisionValue.DECLINE);
  }

  @Test
  void timeoutFindingAnalystClaimOrMovedStateIsDropped() {
    OwnerState claimed =
        held().withClaim(Claim.ANALYST, UUID.randomUUID(), DEADLINE, Claim.NONE, DEADLINE);
    assertThat(evaluate(claimed, timeout(DecisionValue.HOLD), DEADLINE).refusal())
        .isEqualTo(Refusal.DROP);
    assertThat(
            evaluate(
                    state(2, DecisionValue.APPROVE, DecidedBy.ANALYST),
                    timeout(DecisionValue.HOLD),
                    DEADLINE)
                .outcome())
        .isEqualTo(Outcome.REFUSED);
  }

  @Test
  void timeoutWhoseBasisDiffersRecordsTheContestBeforeAnythingElse() {
    // Review 8, MAJOR 1: the owner stands on DECLINE; the hold's own record is a HOLD. The
    // contest is checked before HOLD and the deadline (review 1, finding 5).
    Verdict v =
        evaluate(
            state(1, DecisionValue.DECLINE, DecidedBy.MODEL), timeout(DecisionValue.HOLD), DEADLINE);
    assertThat(v.refusal()).isEqualTo(Refusal.CONFLICT);
    assertThat(v.contest()).isNotNull();
    assertThat(v.contest().detectedBy()).isEqualTo("DUE_HOLD");
    assertThat(v.transition()).isNull();
  }

  @Test
  void escalatedHoldTimesOutAtTheEscalationDeadline() {
    Instant extended = NOW.plus(Duration.ofMinutes(15));
    OwnerState escalated = held().withClaim(Claim.ESCALATED, null, extended, null, null);
    assertThat(evaluate(escalated, timeout(DecisionValue.HOLD), DEADLINE).reschedule())
        .isEqualTo(extended);
    assertThat(evaluate(escalated, timeout(DecisionValue.HOLD), extended).outcome())
        .isEqualTo(Outcome.APPLIED);
  }

  // ----------------------------------------------------------------------- CLAIM and RELEASE

  @Test
  void claimTakesTheHoldBeforeItsDeadlineAndUndoGivesItBack() {
    UUID decision = UUID.randomUUID();
    Verdict claim =
        evaluate(held(), new OwnerRequest.ClaimRequest(decision, true), DEADLINE.minusMillis(1));
    assertThat(claim.outcome()).isEqualTo(Outcome.CLAIMED);
    assertThat(claim.owner().claim()).isEqualTo(Claim.ANALYST);
    assertThat(evaluate(claim.owner(), timeout(DecisionValue.HOLD), DEADLINE).refusal())
        .as("P4: exactly one of ANALYST and TIMEOUT")
        .isEqualTo(Refusal.DROP);
    Verdict undo = evaluate(claim.owner(), new OwnerRequest.Release(decision), DEADLINE);
    assertThat(undo.owner().claim()).isEqualTo(Claim.NONE);
    assertThat(undo.owner().deadline()).isEqualTo(DEADLINE);
    assertThat(evaluate(undo.owner(), timeout(DecisionValue.HOLD), DEADLINE).outcome())
        .isEqualTo(Outcome.APPLIED);
  }

  @Test
  void claimAtOrAfterTheDeadlineIsRefused() {
    assertThat(
            evaluate(held(), new OwnerRequest.ClaimRequest(UUID.randomUUID(), true), DEADLINE)
                .refusal())
        .isEqualTo(Refusal.DEADLINE_PASSED);
  }

  // ------------------------------------------------------------------------------- EFFECT

  @Test
  void effectDecidesTheHoldAndHumanFraudDecisionOnDeclineIsState() {
    UUID decision = UUID.randomUUID();
    OwnerState claimed =
        held().withClaim(Claim.ANALYST, decision, DEADLINE, Claim.NONE, DEADLINE);
    Verdict onHold =
        evaluate(claimed, new OwnerRequest.Effect(decision, true, UUID.randomUUID()), NOW);
    assertThat(onHold.transition().value()).isEqualTo(DecisionValue.DECLINE);
    assertThat(onHold.transition().by()).isEqualTo(DecidedBy.ANALYST);

    OwnerState declined =
        state(1, DecisionValue.DECLINE, DecidedBy.MODEL)
            .withClaim(Claim.ANALYST, decision, null, Claim.NONE, null);
    Verdict confirm =
        evaluate(declined, new OwnerRequest.Effect(decision, true, UUID.randomUUID()), NOW);
    assertThat(confirm.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(confirm.transition().value()).isEqualTo(DecisionValue.DECLINE);
    Verdict legit =
        evaluate(declined, new OwnerRequest.Effect(decision, false, UUID.randomUUID()), NOW);
    assertThat(legit.outcome()).as("Q1: a label only").isEqualTo(Outcome.LABEL_ONLY);
    assertThat(legit.owner().claim()).isEqualTo(Claim.NONE);
  }

  @Test
  void analystFraudDecisionOverrulesCustomerYesButNoOtherApproval() {
    UUID decision = UUID.randomUUID();
    OwnerState lifted =
        state(2, DecisionValue.APPROVE, DecidedBy.CUSTOMER_VERIFICATION)
            .withClaim(Claim.ANALYST, decision, null, Claim.NONE, null);
    assertThat(
            evaluate(lifted, new OwnerRequest.Effect(decision, true, UUID.randomUUID()), NOW)
                .outcome())
        .isEqualTo(Outcome.APPLIED);
    OwnerState released =
        state(2, DecisionValue.TIMEOUT_RELEASE, DecidedBy.TIMEOUT_POLICY)
            .withClaim(Claim.ANALYST, decision, null, Claim.NONE, null);
    assertThat(
            evaluate(released, new OwnerRequest.Effect(decision, true, UUID.randomUUID()), NOW)
                .outcome())
        .isEqualTo(Outcome.LABEL_ONLY);
  }

  @Test
  void effectWhoseClaimWasClearedByResolutionKeepsOnlyItsLabel() {
    Verdict v =
        evaluate(
            state(3, DecisionValue.DECLINE, DecidedBy.SENIOR_OVERRIDE),
            new OwnerRequest.Effect(UUID.randomUUID(), true, UUID.randomUUID()),
            NOW);
    assertThat(v.outcome()).isEqualTo(Outcome.LABEL_ONLY);
    assertThat(v.refusal()).isEqualTo(Refusal.CLAIM_LOST);
    assertThat(v.owner()).isNull();
  }

  // ------------------------------------------------------------------------------ OVERRIDE

  @Test
  void seniorFraudDecisionOnDeclineIsStateAndBlocksCustomerYes() {
    // Review 8, MAJOR 3, in its own order: an analyst marks legitimate (a label only), a senior
    // confirms fraud, and the customer then answers "yes, this was me".
    OwnerState declined = state(1, DecisionValue.DECLINE, DecidedBy.MODEL);
    Verdict senior =
        OwnerRules.evaluate(
            declined,
            new OwnerRequest.SeniorOverride(UUID.randomUUID(), true, UUID.randomUUID(), null),
            NOW,
            BLOCKED);
    assertThat(senior.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(senior.transition().value()).isEqualTo(DecisionValue.DECLINE);
    assertThat(senior.transition().by()).isEqualTo(DecidedBy.SENIOR_OVERRIDE);
    Verdict yes =
        OwnerRules.evaluate(
            senior.owner(), new OwnerRequest.Customer(UUID.randomUUID(), true), NOW, BLOCKED);
    assertThat(yes.outcome()).isEqualTo(Outcome.REFUSED);
    assertThat(yes.refusal()).isEqualTo(Refusal.HUMAN_DECLINED);
    assertThat(yes.recorded()).isTrue();
    assertThat(yes.label()).as("no LEGITIMATE label").isNull();
    assertThat(yes.unblock()).isNull();
  }

  @Test
  void overrideToLegitimateLiftsBlockOnceAndWaitsForPendingEffect() {
    Verdict lift =
        OwnerRules.evaluate(
            state(1, DecisionValue.DECLINE, DecidedBy.MODEL),
            new OwnerRequest.SeniorOverride(UUID.randomUUID(), false, UUID.randomUUID(), null),
            NOW,
            BLOCKED);
    assertThat(lift.transition().value()).isEqualTo(DecisionValue.APPROVE);
    assertThat(lift.unblock()).isNotNull();

    OwnerState pending =
        state(1, DecisionValue.DECLINE, DecidedBy.MODEL)
            .withClaim(Claim.ANALYST, UUID.randomUUID(), null, Claim.NONE, null);
    assertThat(
            evaluate(
                    pending,
                    new OwnerRequest.SeniorOverride(
                        UUID.randomUUID(), false, UUID.randomUUID(), null),
                    NOW)
                .refusal())
        .isEqualTo(Refusal.NOT_COMMITTED);
    assertThat(
            OwnerRules.evaluate(
                    pending,
                    new OwnerRequest.SeniorOverride(
                        UUID.randomUUID(), false, UUID.randomUUID(), null),
                    NOW,
                    new OwnerRules.Facts(true, false, false))
                .refusal())
        .isEqualTo(Refusal.EFFECT_FAILED);
  }

  // -------------------------------------------------------------------------- CUSTOMER

  @Test
  void customerYesLiftsModelDeclineAndWaitsWhileHumanReviews() {
    Verdict yes =
        OwnerRules.evaluate(
            state(1, DecisionValue.DECLINE, DecidedBy.MODEL),
            new OwnerRequest.Customer(UUID.randomUUID(), true),
            NOW,
            BLOCKED);
    assertThat(yes.transition().value()).isEqualTo(DecisionValue.APPROVE);
    assertThat(yes.transition().by()).isEqualTo(DecidedBy.CUSTOMER_VERIFICATION);
    assertThat(yes.unblock().cause()).isEqualTo("CUSTOMER_VERIFICATION");

    OwnerState reviewed =
        state(1, DecisionValue.DECLINE, DecidedBy.MODEL)
            .withClaim(Claim.ANALYST, UUID.randomUUID(), null, Claim.NONE, null);
    assertThat(
            evaluate(reviewed, new OwnerRequest.Customer(UUID.randomUUID(), true), NOW).outcome())
        .isEqualTo(Outcome.DEFERRED);
    OwnerState escalated =
        state(1, DecisionValue.DECLINE, DecidedBy.MODEL)
            .withClaim(Claim.ESCALATED, null, NOW.plusSeconds(900), null, null);
    assertThat(
            evaluate(escalated, new OwnerRequest.Customer(UUID.randomUUID(), true), NOW).outcome())
        .isEqualTo(Outcome.DEFERRED);
    assertThat(
            evaluate(
                    escalated,
                    new OwnerRequest.Customer(UUID.randomUUID(), true),
                    NOW.plusSeconds(900))
                .outcome())
        .as("the escalation window has ended")
        .isEqualTo(Outcome.APPLIED);
  }

  @Test
  void customerNoIsLabelWhateverTheStateEvenContested() {
    OwnerState contested = state(1, DecisionValue.DECLINE, DecidedBy.MODEL).contested(NOW);
    Verdict no = evaluate(contested, new OwnerRequest.Customer(UUID.randomUUID(), false), NOW);
    assertThat(no.outcome()).isEqualTo(Outcome.LABEL_ONLY);
    assertThat(no.label().fraud()).isTrue();
  }

  // ------------------------------------------------------------------------- CONTESTED

  @Test
  void whileContestedEveryRequestButTheExitsIsDeferredOrRefused() {
    OwnerState contested = held().contested(NOW);
    assertThat(evaluate(contested, timeout(DecisionValue.HOLD), DEADLINE).refusal())
        .isEqualTo(Refusal.DROP);
    assertThat(
            evaluate(contested, new OwnerRequest.ClaimRequest(UUID.randomUUID(), true), NOW)
                .refusal())
        .isEqualTo(Refusal.CONTESTED);
    assertThat(evaluate(contested, new OwnerRequest.Escalate(Duration.ofMinutes(15)), NOW).refusal())
        .isEqualTo(Refusal.CONTESTED);
    assertThat(
            evaluate(contested, new OwnerRequest.Customer(UUID.randomUUID(), true), NOW).outcome())
        .isEqualTo(Outcome.DEFERRED);
    OwnerState claimed =
        contested.withClaim(Claim.ANALYST, UUID.randomUUID(), DEADLINE, Claim.NONE, DEADLINE);
    assertThat(
            evaluate(
                    claimed,
                    new OwnerRequest.Effect(claimed.claimDecisionId(), true, UUID.randomUUID()),
                    NOW)
                .outcome())
        .as("deferred, not terminally skipped (review 8, MAJOR 2)")
        .isEqualTo(Outcome.DEFERRED);
  }

  @Test
  void resolutionEndsTheContestWithNewStateAndClearsPendingClaim() {
    OwnerState contested =
        held()
            .withClaim(Claim.ANALYST, UUID.randomUUID(), DEADLINE, Claim.NONE, DEADLINE)
            .contested(NOW);
    UUID resolution = UUID.randomUUID();
    Verdict resolve =
        evaluate(
            contested,
            new OwnerRequest.Resolve(resolution, DecisionValue.DECLINE, UUID.randomUUID(), NOW),
            NOW);
    assertThat(resolve.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(resolve.transition().resolution()).isTrue();
    assertThat(resolve.transition().reasons()).containsExactly("CONTEST_RESOLVED");
    assertThat(resolve.owner().contestedSince()).isNull();
    assertThat(resolve.owner().claim()).isEqualTo(Claim.NONE);
    assertThat(resolve.owner().resolutionId()).isEqualTo(resolution);
    assertThat(resolve.closeEntries()).isEqualTo(Verdict.Closing.RESOLVE);

    assertThat(
            evaluate(
                    resolve.owner(),
                    new OwnerRequest.SeniorOverride(
                        UUID.randomUUID(), false, UUID.randomUUID(), null),
                    NOW)
                .refusal())
        .as("a resolution is final through the API")
        .isEqualTo(Refusal.STALE);
  }

  @Test
  void resolutionMadeBeforeTheContestChangedIsRefused() {
    OwnerState contested = held().contested(NOW.plusSeconds(5));
    assertThat(
            evaluate(
                    contested,
                    new OwnerRequest.Resolve(
                        UUID.randomUUID(), DecisionValue.APPROVE, UUID.randomUUID(), NOW),
                    NOW)
                .refusal())
        .isEqualTo(Refusal.CONTEST_CHANGED);
  }

  @Test
  void releasingFailedClaimRestoresWhatItReplaced() {
    UUID decision = UUID.randomUUID();
    OwnerState claimed =
        held().withClaim(Claim.ANALYST, decision, DEADLINE, Claim.NONE, DEADLINE);
    Verdict released =
        OwnerRules.evaluate(
            claimed,
            new OwnerRequest.ReleaseFailed(UUID.randomUUID(), decision),
            NOW,
            new OwnerRules.Facts(true, false, false));
    assertThat(released.outcome()).isEqualTo(Outcome.RELEASED);
    assertThat(released.owner().claim()).isEqualTo(Claim.NONE);
    assertThat(
            evaluate(claimed, new OwnerRequest.ReleaseFailed(UUID.randomUUID(), decision), NOW)
                .refusal())
        .as("an effect that has not failed")
        .isEqualTo(Refusal.NOT_FAILED);
  }

  @Test
  void secondEscalationAfterTheWindowLapsedOpensNewOne() {
    OwnerState lapsed =
        state(1, DecisionValue.DECLINE, DecidedBy.MODEL)
            .withClaim(Claim.ESCALATED, null, NOW, null, null);
    Verdict again =
        evaluate(lapsed, new OwnerRequest.Escalate(Duration.ofMinutes(15)), NOW.plusSeconds(1));
    assertThat(again.owner().deadline()).isEqualTo(NOW.plusSeconds(1).plus(Duration.ofMinutes(15)));
  }
}
