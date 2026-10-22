package io.github.mariusbayizere.fraudshield.decision.owner;

/** Who is reviewing a transaction now (decision-owner contract §4.1). */
public enum Claim {
  /** Nobody. */
  NONE,
  /** An analyst decision, until its effect runs or it is undone. */
  ANALYST,
  /** An escalation, until a senior decides or its window ends. */
  ESCALATED
}
