package io.github.mariusbayizere.fraudshield.audit;

/**
 * The twelve audit event types (D-32). Each event also carries an {@code action} naming what
 * happened within its type, for example {@code AUTH/LOGIN_FAILED}.
 */
public enum AuditEventType {
  /** Sign-in, sign-out, sessions, lockout, password and email events. */
  AUTH,
  /** Staff account lifecycle, approvals and the office IP allowlist. */
  USER_ADMIN,
  /** Automated decision on an ingested transaction. */
  TRANSACTION_DECISION,
  /** Automatic block, including account freeze. */
  AUTO_BLOCK,
  /** Customer verification of a blocked transaction. */
  CUSTOMER_VERIFICATION,
  /** Analyst decision, including escalation and undo. */
  ANALYST_DECISION,
  /** Risk officer override of a committed decision. */
  SENIOR_OVERRIDE,
  /** Thresholds, circuit breaker and timeout policy. */
  THRESHOLD_CHANGE,
  /** Custom rule versions and state. */
  RULE_CHANGE,
  /** Model promotion, rollback and shadow changes. */
  MODEL_LIFECYCLE,
  /** API key creation, rotation and revocation. */
  API_KEY_LIFECYCLE,
  /** Suspicious activity report drafting and sign-off. */
  REGULATORY_REPORT
}
