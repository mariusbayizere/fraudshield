package io.github.mariusbayizere.fraudshield.auth.domain;

/** Institutional department, separate from the role (D-24). */
public enum Department {
  /** Fraud operations. */
  FRAUD_OPERATIONS,
  /** Risk. */
  RISK,
  /** Compliance. */
  COMPLIANCE,
  /** Information technology. */
  IT,
  /** Any other department. */
  OTHER
}
