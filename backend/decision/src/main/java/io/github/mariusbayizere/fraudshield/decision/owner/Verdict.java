package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What the rule table decides for one request (decision-owner contract §5). {@link DecisionOwner}
 * writes it; the rule table itself writes nothing.
 *
 * @param outcome the outcome
 * @param refusal why a request was refused or deferred, or null
 * @param recorded whether a REFUSED outcome is written to {@code transaction_requests}
 * @param owner the owner row afterwards, or null when it does not change
 * @param transition the new state, for APPLIED
 * @param label the label, for APPLIED and LABEL_ONLY when there is one
 * @param closeEntries which entries the request closes
 * @param unblock the unblock row to write, or null
 * @param contest the second first decision to record, or null
 * @param reschedule the deadline to reschedule a hold at, for a TIMEOUT refused as early
 */
public record Verdict(
    Outcome outcome,
    Refusal refusal,
    boolean recorded,
    OwnerState owner,
    Transition transition,
    Label label,
    Closing closeEntries,
    Unblock unblock,
    Contest contest,
    Instant reschedule) {

  /** Checks the parts agree. */
  public Verdict {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(closeEntries, "closeEntries");
    if ((outcome == Outcome.APPLIED) != (transition != null)) {
      throw new IllegalArgumentException("APPLIED, and only APPLIED, makes a state");
    }
    if (outcome == Outcome.LABEL_ONLY && label == null) {
      throw new IllegalArgumentException("LABEL_ONLY carries a label");
    }
  }

  /** A request's outcome (§4.3). */
  public enum Outcome {
    /** A new state, with a request row. */
    APPLIED,
    /** A label and no state, with a request row. */
    LABEL_ONLY,
    /** Refused; written only when {@link Verdict#recorded()}. */
    REFUSED,
    /** A RELEASE_FAILED that released its claim, with a request row and no event. */
    RELEASED,
    /** Nothing written; the caller retries later. */
    DEFERRED,
    /** The owner row changed and nothing else: CLAIM, RELEASE, ESCALATE. */
    CLAIMED
  }

  /** Why a request was refused or deferred; the API maps each to its answer. */
  public enum Refusal {
    /** Contested: the transaction has two different first decisions. */
    CONTESTED,
    /** No sequence-1 state is visible yet. */
    NO_FIRST_DECISION,
    /** A hold's deadline has not come: reschedule. */
    EARLY,
    /** The hold's deadline has passed, or it is no longer held. */
    DEADLINE_PASSED,
    /** The hold is not held any more, or an analyst owns it: the timeout drops it. */
    DROP,
    /** The timeout's basis differs from the owner's first decision. */
    CONFLICT,
    /** Another analyst decision holds the claim, or the entry's state moved on. */
    STALE,
    /** The original's effect is still pending. */
    NOT_COMMITTED,
    /** The claim's effect FAILED; an operator releases it first. */
    EFFECT_FAILED,
    /** A human declined the payment; the customer's "yes" cannot lift it. */
    HUMAN_DECLINED,
    /** A human is reviewing the payment; the customer's answer waits. */
    CLAIM_PENDING,
    /** A RESOLVE whose contest has ended or changed. */
    CONTEST_CHANGED,
    /** A RELEASE_FAILED whose claim has no FAILED effect. */
    NOT_FAILED,
    /** A customer's "yes" for a payment that is held, which the customer path never reaches. */
    HELD,
    /** The claim was cleared by a resolution. */
    CLAIM_LOST,
    /** A RELEASE whose claim is not this decision's: nothing to do. */
    NOTHING_TO_RELEASE;

    /**
     * The reason stored in {@code transaction_requests.reason}.
     *
     * @return kebab case
     */
    public String reason() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  /** Which entries a request closes (§5). */
  public enum Closing {
    /** None. */
    NONE,
    /** A timeout's: open entries and FAILED-and-released ones, as AUTO_*. */
    TIMEOUT,
    /** A resolution's: open entries, as OVERRIDDEN. */
    RESOLVE
  }

  /**
   * A new state's parts.
   *
   * @param value the value
   * @param by who decides
   * @param reasons reason codes
   * @param resolution whether a resolution makes it
   */
  public record Transition(
      DecisionValue value, DecidedBy by, List<String> reasons, boolean resolution) {
    /** Copies the reasons. */
    public Transition {
      reasons = List.copyOf(reasons);
    }
  }

  /**
   * A label.
   *
   * @param fraud FRAUD or LEGITIMATE
   * @param source ANALYST, SENIOR_OVERRIDE or CUSTOMER
   * @param supersedesLabelId the label it supersedes, or null
   */
  public record Label(boolean fraud, String source, java.util.UUID supersedesLabelId) {}

  /**
   * An unblock row.
   *
   * @param cause CUSTOMER_VERIFICATION or SENIOR_OVERRIDE
   * @param actorUserId the senior or officer, or null for the customer
   */
  public record Unblock(String cause, java.util.UUID actorUserId) {}

  /**
   * A second first decision to record in {@code decision_conflicts}.
   *
   * @param eventId its event id
   * @param decision its value
   * @param deadline its review deadline, or null
   * @param detectedBy SINK or DUE_HOLD
   */
  public record Contest(
      java.util.UUID eventId, DecisionValue decision, Instant deadline, String detectedBy) {}

  static Verdict refused(Refusal refusal, boolean recorded) {
    return new Verdict(
        Outcome.REFUSED, refusal, recorded, null, null, null, Closing.NONE, null, null, null);
  }

  static Verdict deferred(Refusal refusal) {
    return new Verdict(
        Outcome.DEFERRED, refusal, false, null, null, null, Closing.NONE, null, null, null);
  }

  static Verdict claimed(OwnerState owner) {
    return new Verdict(
        Outcome.CLAIMED, null, false, owner, null, null, Closing.NONE, null, null, null);
  }
}
