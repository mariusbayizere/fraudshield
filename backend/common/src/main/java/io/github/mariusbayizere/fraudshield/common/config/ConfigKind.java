package io.github.mariusbayizere.fraudshield.common.config;

/** Risk configuration under dual control (ADR 0014). */
public enum ConfigKind {
  /** Per-channel MEDIUM and HIGH thresholds and the MEDIUM timeout policy (FR-05-07, D-10). */
  CHANNEL_THRESHOLDS,
  /** MCC circuit-breaker trip and reset settings (FR-03-07, D-18). */
  MCC_CIRCUIT_BREAKER
}
