package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The machine-to-machine decision (OpenAPI {@code DecisionResponse}, D-12): the seven D-12 fields
 * plus the review deadline and the fallback flag (ADR 0011 section 9). Nothing else about the model
 * leaves through the ingest API.
 *
 * @param transactionId transaction
 * @param decision APPROVE, DECLINE or HOLD
 * @param riskTier tier
 * @param reasonCodes at most three reasons
 * @param scoringResultId the persisted scoring result
 * @param modelVersion model or fallback version
 * @param decisionLatencyMs server processing time in milliseconds
 * @param reviewDeadlineAt the hold's deadline, null unless HOLD
 * @param mlUnavailableFallback whether the fallback decided
 */
public record IngestDecision(
    UUID transactionId,
    Decision decision,
    RiskTier riskTier,
    List<String> reasonCodes,
    UUID scoringResultId,
    String modelVersion,
    long decisionLatencyMs,
    Instant reviewDeadlineAt,
    boolean mlUnavailableFallback) {

  /** Requires every mandatory component and copies the reasons. */
  public IngestDecision {
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(riskTier, "riskTier");
    Objects.requireNonNull(scoringResultId, "scoringResultId");
    Objects.requireNonNull(modelVersion, "modelVersion");
    reasonCodes = List.copyOf(reasonCodes);
  }
}
