package io.github.mariusbayizere.fraudshield.decision.application.event;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.domain.AlertTier;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Facts the decision path records. Adapters render each into Kafka events (C.3) and PostgreSQL rows
 * (V3–V6) from one durable spool record, so the two can never disagree about what happened.
 */
public sealed interface DecisionEvent {

  /**
   * The owning institution.
   *
   * @return institution id
   */
  UUID institutionId();

  /**
   * A transaction was accepted and decided at ingest.
   *
   * @param transaction the transaction
   * @param scoring the full scoring result, for {@code fraud_scores} and staff APIs (D-12)
   * @param outcome the decision
   * @param state the sequence-1 decision state
   * @param thresholdsVersion thresholds version in force
   * @param requestFingerprint SHA-256 of the canonical request (FR-01-03)
   * @param decisionLatencyMs server processing time
   * @param firstSeenForAccount whether this is the account's first transaction in FraudShield
   *     (PB-37's durable first-seen)
   */
  record TransactionDecided(
      Transaction transaction,
      ScoringRecord scoring,
      DecisionOutcome outcome,
      DecisionState state,
      long thresholdsVersion,
      byte[] requestFingerprint,
      long decisionLatencyMs,
      boolean firstSeenForAccount)
      implements DecisionEvent {

    /** Requires every component and copies the fingerprint. */
    public TransactionDecided {
      Objects.requireNonNull(transaction, "transaction");
      Objects.requireNonNull(scoring, "scoring");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(state, "state");
      requestFingerprint = requestFingerprint.clone();
      if (requestFingerprint.length != 32) {
        throw new IllegalArgumentException("the fingerprint is a SHA-256 digest");
      }
    }

    @Override
    public UUID institutionId() {
      return transaction.institutionId();
    }

    @Override
    public byte[] requestFingerprint() {
      return requestFingerprint.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof TransactionDecided that
          && state.eventId().equals(that.state.eventId());
    }

    @Override
    public int hashCode() {
      return state.eventId().hashCode();
    }

    @Override
    public String toString() {
      return "TransactionDecided[" + transaction.transactionId() + ", " + outcome.decision() + "]";
    }
  }

  /**
   * An alert for an analyst queue (FR-04, D-10).
   *
   * @param alertId alert id
   * @param institutionId institution
   * @param transaction the transaction
   * @param tier the queue
   * @param fraudProbability ensemble score, or the fallback's nominal score
   * @param anomalyScore anomaly percentile, null for a fallback decision
   * @param scoringResultId the scoring result
   * @param reviewDeadlineAt the hold's deadline; null unless MEDIUM
   * @param reasonCodes reasons
   * @param createdAt creation time
   */
  record AlertRaised(
      UUID alertId,
      UUID institutionId,
      Transaction transaction,
      AlertTier tier,
      double fraudProbability,
      Double anomalyScore,
      UUID scoringResultId,
      Instant reviewDeadlineAt,
      List<String> reasonCodes,
      Instant createdAt)
      implements DecisionEvent {

    /** Copies the reasons and checks the hold rule of the alert schema. */
    public AlertRaised {
      reasonCodes = List.copyOf(reasonCodes);
      if ((tier == AlertTier.MEDIUM) != (reviewDeadlineAt != null)) {
        throw new IllegalArgumentException("MEDIUM alerts, and only they, carry a deadline");
      }
    }
  }

  /**
   * A HIGH decision blocked the transaction (FR-03-01, D-30: facts known at block time only).
   *
   * @param autoBlockEventId event id
   * @param institutionId institution
   * @param transactionId transaction
   * @param scoringResultId scoring result
   * @param accountToken account
   * @param blockedAt block time
   * @param reason block reason (reason codes joined)
   */
  record AutoBlocked(
      UUID autoBlockEventId,
      UUID institutionId,
      UUID transactionId,
      UUID scoringResultId,
      String accountToken,
      Instant blockedAt,
      String reason)
      implements DecisionEvent {}

  /**
   * The account was frozen by the third HIGH decision within 60 minutes (FR-03-06).
   *
   * @param institutionId institution
   * @param accountToken account
   * @param autoBlockEventId the block that triggered the freeze
   * @param highDecisions HIGH decisions in the window
   * @param frozenAt freeze time
   */
  record AccountFrozen(
      UUID institutionId,
      String accountToken,
      UUID autoBlockEventId,
      int highDecisions,
      Instant frozenAt)
      implements DecisionEvent {}

  /**
   * An SMS to the customer was requested (FR-03-04, D-25). An intent: the notification service
   * resolves the contact and mints the link at send time.
   *
   * @param notificationId notification id
   * @param institutionId institution
   * @param accountToken account
   * @param autoBlockEventId the block the SMS is about
   * @param templateKey for example {@code sms.auto_block}
   * @param locale customer locale
   * @param verificationLinkAllowed false when self-service is disabled (D-25)
   * @param maskedAccount masked account reference
   * @param amount amount in the transaction currency
   * @param localTime local time with zone abbreviation, for example {@code 10:15 CAT}
   * @param referenceCode short reference code
   * @param requestedAt request time
   */
  record CustomerNotificationRequested(
      UUID notificationId,
      UUID institutionId,
      String accountToken,
      UUID autoBlockEventId,
      String templateKey,
      String locale,
      boolean verificationLinkAllowed,
      String maskedAccount,
      Money amount,
      String localTime,
      String referenceCode,
      Instant requestedAt)
      implements DecisionEvent {}

  /**
   * A decision state after sequence 1 (hold timeout, analyst, customer or override; D-14).
   *
   * @param state the new state
   * @param channel the transaction's channel, for the timeout-release metric
   * @param actorUserId the staff member, or null for the timeout policy and the customer
   */
  record DecisionChanged(DecisionState state, String channel, UUID actorUserId)
      implements DecisionEvent {
    @Override
    public UUID institutionId() {
      return state.institutionId();
    }
  }

  /**
   * A fraud or legitimate label with its availability time (E.2 leakage rule, FR-03-05).
   *
   * @param labelId label id
   * @param institutionId institution
   * @param transactionId transaction
   * @param fraud true for FRAUD, false for LEGITIMATE
   * @param source ANALYST, SENIOR_OVERRIDE, CUSTOMER or CHARGEBACK
   * @param transactionTimestamp the transaction's time
   * @param availableAt when the label became known
   */
  record LabelRecorded(
      UUID labelId,
      UUID institutionId,
      UUID transactionId,
      boolean fraud,
      String source,
      Instant transactionTimestamp,
      Instant availableAt)
      implements DecisionEvent {}

  /**
   * An MCC circuit breaker opened or closed (FR-03-07).
   *
   * @param institutionId institution
   * @param merchantCategoryCode MCC
   * @param change opened or closed
   * @param counts window counts at the change
   * @param settingsVersion settings version in force
   * @param at change time
   */
  record CircuitBreakerChanged(
      UUID institutionId,
      String merchantCategoryCode,
      MccCircuitBreaker.Change change,
      MccCircuitBreaker.WindowCounts counts,
      long settingsVersion,
      Instant at)
      implements DecisionEvent {}

  /**
   * A transaction id was resubmitted within 24 hours with a different request fingerprint; nothing
   * was scored (FR-01-03). Audited as {@code AUTH/IDEMPOTENCY_CONFLICT}; no rows are written.
   *
   * @param eventId event id
   * @param institutionId institution
   * @param transactionId the transaction id
   * @param apiKeyId the key that submitted it
   * @param at when
   */
  record IdempotencyConflict(
      UUID eventId, UUID institutionId, UUID transactionId, UUID apiKeyId, Instant at)
      implements DecisionEvent {}

  /**
   * The full scoring result as persisted (FR-02-01, D-12); features and SHAP are for staff APIs
   * only and never reach the ingest response.
   *
   * @param scoringResultId id
   * @param ensembleScore calibrated score, or the fallback's nominal score
   * @param xgboostScore per-model calibrated score
   * @param lightgbmScore per-model calibrated score
   * @param anomalyScore anomaly percentile
   * @param anomalyRaw raw Isolation Forest score
   * @param tier tier after rules and the circuit breaker
   * @param shapTop5 top contributions as JSON-ready maps, or null
   * @param shapAll all 44 contributions, or null below 0.60
   * @param featureVector the 44 features, NaN as null
   * @param modelVersion model or fallback version
   * @param featureRegistryVersion registry version
   * @param scoringDurationMs scorer-reported duration
   * @param fallback whether the fallback decided
   * @param traceId W3C trace id, or null
   * @param requiresAnalystReview whether an analyst queue receives it
   */
  record ScoringRecord(
      UUID scoringResultId,
      double ensembleScore,
      double xgboostScore,
      double lightgbmScore,
      double anomalyScore,
      double anomalyRaw,
      RiskTier tier,
      List<Map<String, Object>> shapTop5,
      List<Map<String, Object>> shapAll,
      Map<String, Object> featureVector,
      String modelVersion,
      String featureRegistryVersion,
      int scoringDurationMs,
      boolean fallback,
      String traceId,
      boolean requiresAnalystReview) {

    /** Copies the collections; feature values may be null (structurally missing, D-04). */
    public ScoringRecord {
      Objects.requireNonNull(scoringResultId, "scoringResultId");
      Objects.requireNonNull(tier, "tier");
      Objects.requireNonNull(modelVersion, "modelVersion");
      shapTop5 = shapTop5 == null ? null : List.copyOf(copy(shapTop5));
      shapAll = shapAll == null ? null : List.copyOf(copy(shapAll));
      featureVector =
          Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(featureVector)));
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> rows) {
      List<Map<String, Object>> copy = new ArrayList<>(rows.size());
      for (Map<String, Object> row : rows) {
        copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
      }
      return copy;
    }

    /**
     * The same record with the final tier after rules and the circuit breaker.
     *
     * @param finalTier the tier the engine decided
     * @param review whether an analyst queue receives the transaction
     * @return a copy
     */
    public ScoringRecord withTier(RiskTier finalTier, boolean review) {
      return new ScoringRecord(
          scoringResultId,
          ensembleScore,
          xgboostScore,
          lightgbmScore,
          anomalyScore,
          anomalyRaw,
          finalTier,
          shapTop5,
          shapAll,
          featureVector,
          modelVersion,
          featureRegistryVersion,
          scoringDurationMs,
          fallback,
          traceId,
          review);
    }
  }
}
