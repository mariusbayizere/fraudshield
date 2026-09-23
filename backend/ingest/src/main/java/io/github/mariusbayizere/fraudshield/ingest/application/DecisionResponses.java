package io.github.mariusbayizere.fraudshield.ingest.application;

import io.github.mariusbayizere.fraudshield.decision.application.IngestDecision;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the OpenAPI {@code DecisionResponse} (D-12): exactly the seven D-12 fields plus the
 * review deadline and the fallback flag, in a fixed order, so the same decision always renders to
 * the same bytes and idempotent replays are byte-identical (FR-01-03).
 */
public final class DecisionResponses {

  private static final ObjectMapper JSON = new ObjectMapper();

  private DecisionResponses() {}

  /**
   * The response body.
   *
   * @param d the decision
   * @return compact UTF-8 JSON
   */
  public static byte[] render(IngestDecision d) {
    ObjectNode node = JSON.createObjectNode();
    node.put("transaction_id", d.transactionId().toString());
    node.put("decision", d.decision().name());
    node.put("risk_tier", d.riskTier().name());
    node.set("reason_codes", JSON.valueToTree(d.reasonCodes()));
    node.put("scoring_result_id", d.scoringResultId().toString());
    node.put("model_version", d.modelVersion());
    node.put("decision_latency_ms", d.decisionLatencyMs());
    node.put(
        "review_deadline_at",
        d.reviewDeadlineAt() == null ? null : d.reviewDeadlineAt().toString());
    node.put("ml_unavailable_fallback", d.mlUnavailableFallback());
    return JSON.writeValueAsBytes(node);
  }
}
