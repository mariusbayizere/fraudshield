package io.github.mariusbayizere.fraudshield.common.config;

/** Whether a risk-configuration change blocks more or less (ADR 0014, owner decision). */
public enum ChangeDirection {
  /** Blocks more: applied at once by one risk officer, confirmed by another within 24 hours. */
  TIGHTENING,
  /** Blocks less, or mixes both: applied only after a second risk officer approves. */
  LOOSENING
}
