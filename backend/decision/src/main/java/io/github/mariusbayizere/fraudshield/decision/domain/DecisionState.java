package io.github.mariusbayizere.fraudshield.decision.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One decision state of a transaction (OpenAPI {@code FinalDecision}, D-14, ADR 0011 section 9).
 * The same value is returned by {@code GET /decisions/{id}}, published on {@code
 * fs.decisions.final} and sent as the {@code decision.final} webhook body.
 *
 * @param eventId identifies this state across delivery retries
 * @param institutionId owning institution (not serialised in the body)
 * @param transactionId the transaction
 * @param sequence 1 for the ingest decision, then +1 per change
 * @param decision the value
 * @param decidedAt when this state was decided
 * @param decidedBy who decided it
 * @param reasonCodes at most three reasons
 * @param reviewDeadlineAt the hold's deadline; null unless HOLD
 * @param supersedes the previous state's value; null for sequence 1
 */
public record DecisionState(
    UUID eventId,
    UUID institutionId,
    UUID transactionId,
    int sequence,
    DecisionValue decision,
    Instant decidedAt,
    DecidedBy decidedBy,
    List<String> reasonCodes,
    Instant reviewDeadlineAt,
    DecisionValue supersedes) {

  /** Enforces the structural rules of the schema (and of V3 {@code decision_states}). */
  public DecisionState {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(institutionId, "institutionId");
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decidedAt, "decidedAt");
    Objects.requireNonNull(decidedBy, "decidedBy");
    reasonCodes = List.copyOf(reasonCodes);
    if (sequence < 1 || reasonCodes.size() > ReasonCodes.MAX_REASONS) {
      throw new IllegalArgumentException("sequence starts at 1; at most three reasons");
    }
    if ((decision == DecisionValue.HOLD) != (reviewDeadlineAt != null)) {
      throw new IllegalArgumentException("HOLD, and only HOLD, carries a review deadline");
    }
    if ((sequence == 1) != (decidedBy == DecidedBy.MODEL)
        || (sequence == 1) != (supersedes == null)) {
      throw new IllegalArgumentException("sequence 1, and only sequence 1, is the model's");
    }
    if (sequence > 1 && decision == DecisionValue.HOLD) {
      throw new IllegalArgumentException("no state returns to HOLD");
    }
  }

  /**
   * Whether the state is final: every state but HOLD. A final state can still be superseded.
   *
   * @return false only for HOLD
   */
  public boolean isFinal() {
    return decision != DecisionValue.HOLD;
  }

  /**
   * The ingest decision, sequence 1.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @param decision the ingest decision
   * @param decidedAt decision time
   * @param reasonCodes reasons
   * @param reviewDeadlineAt the deadline when HOLD
   * @return the state
   */
  public static DecisionState initial(
      UUID institutionId,
      UUID transactionId,
      Decision decision,
      Instant decidedAt,
      List<String> reasonCodes,
      Instant reviewDeadlineAt) {
    return new DecisionState(
        UUID.randomUUID(),
        institutionId,
        transactionId,
        1,
        DecisionValue.of(decision),
        decidedAt,
        DecidedBy.MODEL,
        reasonCodes,
        reviewDeadlineAt,
        null);
  }

  /**
   * The next state, if the transition is allowed (OpenAPI {@code FinalDecision} description): HOLD
   * → APPROVE or DECLINE by an analyst, TIMEOUT_RELEASE or DECLINE by the timeout policy; DECLINE →
   * APPROVE by customer verification or senior override; APPROVE or TIMEOUT_RELEASE → DECLINE and
   * DECLINE → APPROVE by senior override. Nothing returns to HOLD.
   *
   * @param value the new value
   * @param by who decides
   * @param at when
   * @param reasons reasons
   * @return the new state with sequence + 1
   * @throws IllegalStateException if the transition is not allowed
   */
  public DecisionState next(DecisionValue value, DecidedBy by, Instant at, List<String> reasons) {
    if (!allowed(decision, value, by)) {
      throw new IllegalStateException(
          "transition " + decision + " -> " + value + " by " + by + " is not allowed");
    }
    return new DecisionState(
        UUID.randomUUID(),
        institutionId,
        transactionId,
        sequence + 1,
        value,
        at,
        by,
        reasons,
        null,
        decision);
  }

  static boolean allowed(DecisionValue from, DecisionValue to, DecidedBy by) {
    return switch (from) {
      case HOLD ->
          (by == DecidedBy.ANALYST && (to == DecisionValue.APPROVE || to == DecisionValue.DECLINE))
              || (by == DecidedBy.TIMEOUT_POLICY
                  && (to == DecisionValue.TIMEOUT_RELEASE || to == DecisionValue.DECLINE));
      case DECLINE ->
          to == DecisionValue.APPROVE
              && (by == DecidedBy.CUSTOMER_VERIFICATION || by == DecidedBy.SENIOR_OVERRIDE);
      case APPROVE, TIMEOUT_RELEASE ->
          to == DecisionValue.DECLINE && by == DecidedBy.SENIOR_OVERRIDE;
    };
  }
}
