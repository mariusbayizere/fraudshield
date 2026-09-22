package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** What the decision engine knows about a transaction's risk: a model score or a fallback. */
public sealed interface Scoring {

  /**
   * Identifier of the scoring result (D-12).
   *
   * @return the id
   */
  UUID scoringResultId();

  /**
   * The model or fallback rule-set version that produced it (FR-02-10).
   *
   * @return the version
   */
  String modelVersion();

  /**
   * The 44 features by name; empty for a fallback decision.
   *
   * @return feature values
   */
  Map<String, FieldValue> features();

  /**
   * The calibrated ensemble score from the ML scorer.
   *
   * @param scoringResultId result id
   * @param modelVersion production model version
   * @param ensembleScore calibrated probability (D-05)
   * @param anomalyScore Isolation Forest percentile in [0, 1] (D-06)
   * @param topContributions at most five contributions, empty below 0.60
   * @param features the 44 feature values
   */
  record Model(
      UUID scoringResultId,
      String modelVersion,
      double ensembleScore,
      double anomalyScore,
      List<FeatureContribution> topContributions,
      Map<String, FieldValue> features)
      implements Scoring {

    /** Validates probabilities and copies collections. */
    public Model {
      Objects.requireNonNull(scoringResultId, "scoringResultId");
      Objects.requireNonNull(modelVersion, "modelVersion");
      if (!(ensembleScore >= 0 && ensembleScore <= 1)
          || !(anomalyScore >= 0 && anomalyScore <= 1)) {
        throw new IllegalArgumentException("scores are probabilities in [0, 1]");
      }
      topContributions = List.copyOf(topContributions);
      features = Map.copyOf(features);
    }
  }

  /**
   * The rule-based fallback decided because the scorer was unavailable (C.4, NFR-REL-01).
   *
   * @param scoringResultId result id
   * @param modelVersion fallback rule-set version, for example {@code fallback-rules-1}
   * @param tier the fallback's tier
   * @param reasonCodes why, most important first
   */
  record Fallback(
      UUID scoringResultId, String modelVersion, RiskTier tier, List<String> reasonCodes)
      implements Scoring {

    /** Requires every component and copies the reasons. */
    public Fallback {
      Objects.requireNonNull(scoringResultId, "scoringResultId");
      Objects.requireNonNull(modelVersion, "modelVersion");
      Objects.requireNonNull(tier, "tier");
      reasonCodes = List.copyOf(reasonCodes);
    }

    @Override
    public Map<String, FieldValue> features() {
      return Map.of();
    }
  }
}
