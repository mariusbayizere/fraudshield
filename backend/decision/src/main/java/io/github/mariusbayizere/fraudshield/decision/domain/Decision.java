package io.github.mariusbayizere.fraudshield.decision.domain;

/** Synchronous ingest decision (OpenAPI {@code Decision}). */
public enum Decision {
  /** LOW: approved. */
  APPROVE,
  /** HIGH, or a frozen account: declined. */
  DECLINE,
  /** MEDIUM: held for review until a deadline (D-14). */
  HOLD
}
