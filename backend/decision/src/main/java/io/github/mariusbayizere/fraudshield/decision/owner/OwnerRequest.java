package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to a transaction's owner (decision-owner contract §5). Each is evaluated under the
 * owner row's lock against {@link OwnerRules}; none writes a state in its own right.
 */
public sealed interface OwnerRequest {

  /**
   * The request's id, unique across institutions (§4.5), or null for a request that writes no
   * request row (CLAIM, RELEASE, ESCALATE).
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @return the id, or null
   */
  UUID requestId(UUID institutionId, UUID transactionId);

  /**
   * The kind stored in {@code transaction_requests.kind}.
   *
   * @return the kind
   */
  String kind();

  /**
   * A hold's timeout, from the poller, the local fallback or the overdue sweep.
   *
   * @param basisEventId the first decision the hold was scheduled for, or null for a member written
   *     before V81 (§4.2 point 5)
   * @param basisDecision its value, or null with the event id
   * @param basisDeadline the deadline the hold was scheduled for
   * @param basisDecidedAt when that decision was made, or null
   * @param basisReasons its reason codes
   * @param policy the hold's policy
   * @param channel the transaction's channel, for the timeout-release metric
   */
  record Timeout(
      UUID basisEventId,
      DecisionValue basisDecision,
      Instant basisDeadline,
      Instant basisDecidedAt,
      List<String> basisReasons,
      MediumTimeoutPolicy policy,
      String channel)
      implements OwnerRequest {
    /** Copies the reasons. */
    public Timeout {
      basisReasons = basisReasons == null ? List.of() : List.copyOf(basisReasons);
      Objects.requireNonNull(policy, "policy");
    }

    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return OwnerIds.name("hold_timeout|" + institutionId + "|" + transactionId);
    }

    @Override
    public String kind() {
      return "TIMEOUT";
    }
  }

  /**
   * An analyst decision claims the transaction (D1).
   *
   * @param decisionId the decision
   * @param expectHold whether the entry is MEDIUM, so the payment must still be held
   */
  record ClaimRequest(UUID decisionId, boolean expectHold) implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return null;
    }

    @Override
    public String kind() {
      return "CLAIM";
    }
  }

  /**
   * An undo gives the claim back (D2).
   *
   * @param decisionId the undone decision
   */
  record Release(UUID decisionId) implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return null;
    }

    @Override
    public String kind() {
      return "RELEASE";
    }
  }

  /**
   * A committed analyst decision's effect (D4).
   *
   * @param decisionId the decision
   * @param fraud true for CONFIRM_FRAUD
   * @param analystId the analyst
   */
  record Effect(UUID decisionId, boolean fraud, UUID analystId) implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return OwnerIds.name("effect|" + decisionId);
    }

    @Override
    public String kind() {
      return "EFFECT";
    }
  }

  /**
   * An escalation (D6).
   *
   * @param window W, the escalation review window
   */
  record Escalate(Duration window) implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return null;
    }

    @Override
    public String kind() {
      return "ESCALATE";
    }
  }

  /**
   * A senior's override (D5).
   *
   * @param decisionId the override's decision
   * @param fraud true for CONFIRM_FRAUD
   * @param seniorId the senior
   * @param supersedesLabelId the original's label when it was recorded, else null
   */
  record SeniorOverride(UUID decisionId, boolean fraud, UUID seniorId, UUID supersedesLabelId)
      implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return OwnerIds.name("override|" + decisionId);
    }

    @Override
    public String kind() {
      return "OVERRIDE";
    }
  }

  /**
   * The customer's answer (§6.5).
   *
   * @param verificationId the verification, which has one response
   * @param wasMe true for "yes, this was me"
   */
  record Customer(UUID verificationId, boolean wasMe) implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return OwnerIds.name("verification|" + verificationId);
    }

    @Override
    public String kind() {
      return wasMe ? "CUSTOMER_YES" : "CUSTOMER_NO";
    }
  }

  /**
   * A risk officer's resolution of a contest, applied by the resolver (§7.2).
   *
   * @param resolutionId the resolution
   * @param chosen APPROVE or DECLINE
   * @param officerId the officer named on it
   * @param contestedSince the contest it resolves
   */
  record Resolve(UUID resolutionId, DecisionValue chosen, UUID officerId, Instant contestedSince)
      implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return OwnerIds.name("resolve|" + resolutionId);
    }

    @Override
    public String kind() {
      return "RESOLVE";
    }
  }

  /**
   * An operator's release of an ANALYST claim whose effect FAILED (§7.2).
   *
   * @param resolutionId the resolution row
   * @param decisionId the decision whose claim is released
   */
  record ReleaseFailed(UUID resolutionId, UUID decisionId) implements OwnerRequest {
    @Override
    public UUID requestId(UUID institutionId, UUID transactionId) {
      return OwnerIds.name("release_failed|" + resolutionId);
    }

    @Override
    public String kind() {
      return "RELEASE_FAILED";
    }
  }
}
