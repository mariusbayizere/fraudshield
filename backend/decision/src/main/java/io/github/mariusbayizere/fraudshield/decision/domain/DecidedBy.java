package io.github.mariusbayizere.fraudshield.decision.domain;

/** Who decided a state (OpenAPI {@code FinalDecision.decided_by}). */
public enum DecidedBy {
  /** The ingest decision, sequence 1. */
  MODEL,
  /** An analyst's review of a hold. */
  ANALYST,
  /** The MEDIUM timeout policy at the review deadline. */
  TIMEOUT_POLICY,
  /** The customer answered the verification page. */
  CUSTOMER_VERIFICATION,
  /** A risk officer's override. */
  SENIOR_OVERRIDE
}
