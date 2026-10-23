package io.github.mariusbayizere.fraudshield.decision.domain;

import java.util.Objects;

/**
 * One weighted SHAP contribution in margin space (D-05), as the scorer returned it.
 *
 * @param feature registered feature name
 * @param shap weighted contribution
 * @param increasesRisk whether it pushes the score up
 */
public record FeatureContribution(String feature, double shap, boolean increasesRisk) {

  /** Requires a feature name. */
  public FeatureContribution {
    Objects.requireNonNull(feature, "feature");
  }
}
