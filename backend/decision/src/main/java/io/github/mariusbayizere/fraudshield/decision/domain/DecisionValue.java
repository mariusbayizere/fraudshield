package io.github.mariusbayizere.fraudshield.decision.domain;

/** A decision state's value (OpenAPI {@code FinalDecisionValue}). */
public enum DecisionValue {
  /** Approved. */
  APPROVE,
  /** Declined. */
  DECLINE,
  /** Held for review; never final. */
  HOLD,
  /** A hold released at its deadline under RELEASE_WITH_TIMEOUT_LABEL (D-10). */
  TIMEOUT_RELEASE;

  /**
   * The state value of an ingest decision.
   *
   * @param decision the ingest decision
   * @return the same value
   */
  public static DecisionValue of(Decision decision) {
    return valueOf(decision.name());
  }
}
