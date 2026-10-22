package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A transaction's {@code decision_owners} row as locked (decision-owner contract §4.1).
 *
 * @param institutionId institution
 * @param transactionId transaction
 * @param firstEventId the sequence-1 decision the owner stands on
 * @param firstDecision its value
 * @param firstDeadline its review deadline, HOLD only
 * @param timeoutPolicy the hold's policy, HOLD only
 * @param sequence the current state's sequence
 * @param decision the current value
 * @param decidedBy who made the current value
 * @param claim who is reviewing the transaction now
 * @param claimDecisionId the analyst decision holding an ANALYST claim
 * @param deadline the time a hold runs to, or the end of an escalation window
 * @param priorClaim what an ANALYST claim replaced
 * @param priorDeadline the deadline an ANALYST claim replaced
 * @param contestedSince when two different first decisions were found, or null
 * @param resolutionId the last resolution applied, or null
 */
public record OwnerState(
    UUID institutionId,
    UUID transactionId,
    UUID firstEventId,
    DecisionValue firstDecision,
    Instant firstDeadline,
    MediumTimeoutPolicy timeoutPolicy,
    int sequence,
    DecisionValue decision,
    DecidedBy decidedBy,
    Claim claim,
    UUID claimDecisionId,
    Instant deadline,
    Claim priorClaim,
    Instant priorDeadline,
    Instant contestedSince,
    UUID resolutionId) {

  /** The V81 CHECKs, so that the rule table can never produce a row the database refuses. */
  public OwnerState {
    Objects.requireNonNull(institutionId, "institutionId");
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(firstEventId, "firstEventId");
    Objects.requireNonNull(firstDecision, "firstDecision");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decidedBy, "decidedBy");
    Objects.requireNonNull(claim, "claim");
    if ((claimDecisionId != null) != (claim == Claim.ANALYST)) {
      throw new IllegalArgumentException("an ANALYST claim, and only one, names its decision");
    }
    if (decision == DecisionValue.HOLD && sequence != 1) {
      throw new IllegalArgumentException("only sequence 1 is HOLD");
    }
    if ((decision == DecisionValue.HOLD || claim == Claim.ESCALATED) && deadline == null) {
      throw new IllegalArgumentException("a hold or an escalation has a deadline");
    }
  }

  /**
   * Whether the payment is still held.
   *
   * @return true while HOLD
   */
  public boolean held() {
    return decision == DecisionValue.HOLD;
  }

  /**
   * The same row with another claim.
   *
   * @param newClaim the claim
   * @param newClaimDecisionId its decision, for ANALYST
   * @param newDeadline the deadline
   * @param newPriorClaim the replaced claim
   * @param newPriorDeadline the replaced deadline
   * @return the row
   */
  OwnerState withClaim(
      Claim newClaim,
      UUID newClaimDecisionId,
      Instant newDeadline,
      Claim newPriorClaim,
      Instant newPriorDeadline) {
    return new OwnerState(
        institutionId,
        transactionId,
        firstEventId,
        firstDecision,
        firstDeadline,
        timeoutPolicy,
        sequence,
        decision,
        decidedBy,
        newClaim,
        newClaimDecisionId,
        newDeadline,
        newPriorClaim,
        newPriorDeadline,
        contestedSince,
        resolutionId);
  }

  /**
   * The row after a new state: the claim ends, and a hold's deadline with it.
   *
   * @param newSequence the new sequence
   * @param value the new value
   * @param by who made it
   * @param newContestedSince the contest after it
   * @param newResolutionId the resolution after it
   * @return the row
   */
  OwnerState withState(
      int newSequence,
      DecisionValue value,
      DecidedBy by,
      Instant newContestedSince,
      UUID newResolutionId) {
    return new OwnerState(
        institutionId,
        transactionId,
        firstEventId,
        firstDecision,
        firstDeadline,
        timeoutPolicy,
        newSequence,
        value,
        by,
        Claim.NONE,
        null,
        null,
        null,
        null,
        newContestedSince,
        newResolutionId);
  }

  /**
   * The row standing on another first decision of the same value (§6.1, case 4).
   *
   * @param eventId the first record that reached PostgreSQL
   * @return the row
   */
  OwnerState standingOn(UUID eventId) {
    return new OwnerState(
        institutionId,
        transactionId,
        eventId,
        firstDecision,
        firstDeadline,
        timeoutPolicy,
        sequence,
        decision,
        decidedBy,
        claim,
        claimDecisionId,
        deadline,
        priorClaim,
        priorDeadline,
        contestedSince,
        resolutionId);
  }

  /**
   * The row contested from now on (§7.1).
   *
   * @param at DB time of detection
   * @return the row
   */
  OwnerState contested(Instant at) {
    return new OwnerState(
        institutionId,
        transactionId,
        firstEventId,
        firstDecision,
        firstDeadline,
        timeoutPolicy,
        sequence,
        decision,
        decidedBy,
        claim,
        claimDecisionId,
        deadline,
        priorClaim,
        priorDeadline,
        at,
        resolutionId);
  }
}
