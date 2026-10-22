package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.ReasonCodes;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Closing;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Contest;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Label;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Outcome;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Refusal;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Transition;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Unblock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The rule table (decision-owner contract §5): the only place a transaction's next state is
 * decided. Pure: it reads the owner row as locked, the request, DB time and the facts {@link
 * DecisionOwner} gathered, and returns a {@link Verdict}. It writes nothing.
 */
public final class OwnerRules {

  private static final String CUSTOMER = "CUSTOMER";
  private static final String ANALYST = "ANALYST";
  private static final String SENIOR = "SENIOR_OVERRIDE";

  private OwnerRules() {}

  /**
   * What the owner gathered beside its row, under the same lock.
   *
   * @param claimEffectFailed whether the ANALYST claim's decision has a FAILED effects row
   * @param autoBlocked whether the transaction has an auto-block
   * @param unblocked whether that auto-block already has an unblock row
   */
  public record Facts(boolean claimEffectFailed, boolean autoBlocked, boolean unblocked) {
    /** No auto-block and no failed claim. */
    public static final Facts NONE = new Facts(false, false, false);
  }

  /**
   * Evaluates one request.
   *
   * @param o the owner row as locked
   * @param r the request
   * @param now DB time
   * @param facts the facts gathered under the lock
   * @return the verdict
   */
  public static Verdict evaluate(OwnerState o, OwnerRequest r, Instant now, Facts facts) {
    if (o.contestedSince() != null) {
      Verdict whileContested = whileContested(r);
      if (whileContested != null) {
        return whileContested;
      }
    }
    return switch (r) {
      case OwnerRequest.Timeout t -> timeout(o, t, now);
      case OwnerRequest.ClaimRequest c -> claim(o, c, now);
      case OwnerRequest.Release rel -> release(o, rel.decisionId());
      case OwnerRequest.Effect e -> effect(o, e);
      case OwnerRequest.Escalate e -> escalate(o, e, now);
      case OwnerRequest.SeniorOverride v -> override(o, v, facts);
      case OwnerRequest.Customer c -> customer(o, c, now, facts);
      case OwnerRequest.Resolve res -> resolve(o, res, facts);
      case OwnerRequest.ReleaseFailed rf -> releaseFailed(o, rf, facts);
    };
  }

  /** §7.3: what each request does while contested, or null to proceed. */
  private static Verdict whileContested(OwnerRequest r) {
    return switch (r) {
      case OwnerRequest.Timeout t -> Verdict.refused(Refusal.DROP, false);
      case OwnerRequest.ClaimRequest c -> Verdict.refused(Refusal.CONTESTED, false);
      case OwnerRequest.Escalate e -> Verdict.refused(Refusal.CONTESTED, false);
      case OwnerRequest.SeniorOverride v -> Verdict.refused(Refusal.CONTESTED, false);
      case OwnerRequest.Effect e -> Verdict.deferred(Refusal.CONTESTED);
      case OwnerRequest.Customer c -> c.wasMe() ? Verdict.deferred(Refusal.CONTESTED) : null;
      case OwnerRequest.Release rel -> null;
      case OwnerRequest.Resolve res -> null;
      case OwnerRequest.ReleaseFailed rf -> null;
    };
  }

  /** TIMEOUT, checked in order: basis, HOLD, claim, deadline (review 1, finding 5). */
  private static Verdict timeout(OwnerState o, OwnerRequest.Timeout t, Instant now) {
    if (t.basisDecision() != null && t.basisDecision() != o.firstDecision()) {
      return new Verdict(
          Outcome.REFUSED,
          Refusal.CONFLICT,
          false,
          null,
          null,
          null,
          Closing.NONE,
          null,
          new Contest(t.basisEventId(), t.basisDecision(), t.basisDeadline(), "DUE_HOLD"),
          null);
    }
    if (!o.held() || o.claim() == Claim.ANALYST) {
      return Verdict.refused(Refusal.DROP, false);
    }
    if (now.isBefore(o.deadline())) {
      return new Verdict(
          Outcome.REFUSED,
          Refusal.EARLY,
          false,
          null,
          null,
          null,
          Closing.NONE,
          null,
          null,
          o.deadline());
    }
    MediumTimeoutPolicy policy = o.timeoutPolicy() == null ? t.policy() : o.timeoutPolicy();
    Transition transition =
        policy == MediumTimeoutPolicy.DECLINE_AND_VERIFY
            ? new Transition(
                DecisionValue.DECLINE,
                DecidedBy.TIMEOUT_POLICY,
                List.of(ReasonCodes.REVIEW_TIMEOUT_DECLINED),
                false)
            : new Transition(
                DecisionValue.TIMEOUT_RELEASE,
                DecidedBy.TIMEOUT_POLICY,
                List.of(ReasonCodes.REVIEW_TIMEOUT),
                false);
    return applied(o, transition, null, Closing.TIMEOUT, null, null);
  }

  private static Verdict claim(OwnerState o, OwnerRequest.ClaimRequest c, Instant now) {
    if (o.claim() == Claim.ANALYST) {
      return Verdict.refused(Refusal.STALE, false);
    }
    if (c.expectHold() && (!o.held() || !now.isBefore(o.deadline()))) {
      return Verdict.refused(Refusal.DEADLINE_PASSED, false);
    }
    return Verdict.claimed(
        o.withClaim(Claim.ANALYST, c.decisionId(), o.deadline(), o.claim(), o.deadline()));
  }

  private static Verdict release(OwnerState o, UUID decisionId) {
    if (o.claim() != Claim.ANALYST || !decisionId.equals(o.claimDecisionId())) {
      return Verdict.refused(Refusal.NOTHING_TO_RELEASE, false);
    }
    return Verdict.claimed(restored(o));
  }

  private static OwnerState restored(OwnerState o) {
    Claim prior = o.priorClaim() == null ? Claim.NONE : o.priorClaim();
    return o.withClaim(prior, null, o.priorDeadline(), null, null);
  }

  /** EFFECT, by the decision and the current state (§5). */
  private static Verdict effect(OwnerState o, OwnerRequest.Effect e) {
    Label label = new Label(e.fraud(), ANALYST, null);
    if (o.claim() != Claim.ANALYST || !e.decisionId().equals(o.claimDecisionId())) {
      return labelOnly(null, label, Refusal.CLAIM_LOST);
    }
    OwnerState cleared = o.withClaim(Claim.NONE, null, o.held() ? o.deadline() : null, null, null);
    DecisionValue value = o.decision();
    if (value == DecisionValue.HOLD) {
      return applied(
          o,
          new Transition(
              e.fraud() ? DecisionValue.DECLINE : DecisionValue.APPROVE,
              DecidedBy.ANALYST,
              List.of(),
              false),
          label,
          Closing.NONE,
          null,
          null);
    }
    boolean overrulesCustomer =
        value == DecisionValue.APPROVE && o.decidedBy() == DecidedBy.CUSTOMER_VERIFICATION;
    if (e.fraud() && (value == DecisionValue.DECLINE || overrulesCustomer)) {
      return applied(
          o,
          new Transition(DecisionValue.DECLINE, DecidedBy.ANALYST, List.of(), false),
          label,
          Closing.NONE,
          null,
          null);
    }
    return labelOnly(cleared, label, null);
  }

  private static Verdict escalate(OwnerState o, OwnerRequest.Escalate e, Instant now) {
    if (o.claim() == Claim.ANALYST || (o.held() && !now.isBefore(o.deadline()))) {
      return Verdict.refused(Refusal.STALE, false);
    }
    Instant window = now.plus(e.window());
    if (o.claim() == Claim.ESCALATED && (o.held() || now.isBefore(o.deadline()))) {
      return Verdict.claimed(o);
    }
    Instant deadline = o.held() && o.deadline().isAfter(window) ? o.deadline() : window;
    return Verdict.claimed(o.withClaim(Claim.ESCALATED, null, deadline, null, null));
  }

  /** OVERRIDE, by the senior's decision and the current state (§5). */
  private static Verdict override(OwnerState o, OwnerRequest.SeniorOverride v, Facts facts) {
    // D5(d)'s order: the original's claim, then a resolution, then a hold (code review 1, minor 2).
    if (o.claim() == Claim.ANALYST) {
      return Verdict.refused(
          facts.claimEffectFailed() ? Refusal.EFFECT_FAILED : Refusal.NOT_COMMITTED, false);
    }
    if (o.resolutionId() != null || o.held()) {
      return Verdict.refused(Refusal.STALE, false);
    }
    Label label = new Label(v.fraud(), SENIOR, v.supersedesLabelId());
    if (v.fraud()) {
      return applied(
          o,
          new Transition(DecisionValue.DECLINE, DecidedBy.SENIOR_OVERRIDE, List.of(), false),
          label,
          Closing.NONE,
          null,
          null);
    }
    if (o.decision() == DecisionValue.DECLINE) {
      Unblock unblock =
          facts.autoBlocked() && !facts.unblocked() ? new Unblock(SENIOR, v.seniorId()) : null;
      return applied(
          o,
          new Transition(DecisionValue.APPROVE, DecidedBy.SENIOR_OVERRIDE, List.of(), false),
          label,
          Closing.NONE,
          unblock,
          null);
    }
    return labelOnly(null, label, null);
  }

  /** CUSTOMER_YES in order, and CUSTOMER_NO (§5). */
  private static Verdict customer(OwnerState o, OwnerRequest.Customer c, Instant now, Facts facts) {
    if (!c.wasMe()) {
      return labelOnly(null, new Label(true, CUSTOMER, null), null);
    }
    if (o.claim() == Claim.ANALYST
        || (o.claim() == Claim.ESCALATED && now.isBefore(o.deadline()))) {
      return Verdict.deferred(Refusal.CLAIM_PENDING);
    }
    DecisionValue value = o.decision();
    if (value == DecisionValue.HOLD) {
      // The customer path follows an auto-block, so it never meets a hold; if it did, the answer
      // waits rather than being refused for good (code review 1, minor 13).
      return Verdict.deferred(Refusal.HELD);
    }
    if (value == DecisionValue.DECLINE) {
      if (o.decidedBy() == DecidedBy.ANALYST || o.decidedBy() == DecidedBy.SENIOR_OVERRIDE) {
        return Verdict.refused(Refusal.HUMAN_DECLINED, true);
      }
      Unblock unblock = facts.autoBlocked() ? new Unblock("CUSTOMER_VERIFICATION", null) : null;
      return applied(
          o,
          new Transition(DecisionValue.APPROVE, DecidedBy.CUSTOMER_VERIFICATION, List.of(), false),
          new Label(false, CUSTOMER, null),
          Closing.NONE,
          unblock,
          null);
    }
    return labelOnly(null, new Label(false, CUSTOMER, null), null);
  }

  private static Verdict resolve(OwnerState o, OwnerRequest.Resolve r, Facts facts) {
    if (o.contestedSince() == null || !o.contestedSince().equals(r.contestedSince())) {
      return Verdict.refused(Refusal.CONTEST_CHANGED, true);
    }
    Unblock unblock =
        r.chosen() == DecisionValue.APPROVE && facts.autoBlocked() && !facts.unblocked()
            ? new Unblock(SENIOR, r.officerId())
            : null;
    return applied(
        o,
        new Transition(
            r.chosen(), DecidedBy.SENIOR_OVERRIDE, List.of(ReasonCodes.CONTEST_RESOLVED), true),
        new Label(r.chosen() == DecisionValue.DECLINE, SENIOR, null),
        Closing.RESOLVE,
        unblock,
        r.resolutionId());
  }

  private static Verdict releaseFailed(OwnerState o, OwnerRequest.ReleaseFailed r, Facts facts) {
    if (o.claim() != Claim.ANALYST
        || !r.decisionId().equals(o.claimDecisionId())
        || !facts.claimEffectFailed()) {
      return Verdict.refused(Refusal.NOT_FAILED, true);
    }
    return new Verdict(
        Outcome.RELEASED, null, true, restored(o), null, null, Closing.NONE, null, null, null);
  }

  private static Verdict applied(
      OwnerState o,
      Transition transition,
      Label label,
      Closing closing,
      Unblock unblock,
      UUID resolvedBy) {
    boolean resolution = resolvedBy != null;
    OwnerState after =
        o.withState(
            o.sequence() + 1,
            transition.value(),
            transition.by(),
            resolution ? null : o.contestedSince(),
            resolution ? resolvedBy : o.resolutionId());
    return new Verdict(
        Outcome.APPLIED, null, true, after, transition, label, closing, unblock, null, null);
  }

  private static Verdict labelOnly(OwnerState after, Label label, Refusal reason) {
    return new Verdict(
        Outcome.LABEL_ONLY, reason, true, after, null, label, Closing.NONE, null, null, null);
  }
}
