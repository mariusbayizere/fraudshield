package io.github.mariusbayizere.fraudshield.decision.domain;

/** Alert queues (V4 {@code alert_queue_entries.tier}; D-10). */
public enum AlertTier {
  /** Auto-blocked; topic {@code fs.alerts.high}. */
  HIGH,
  /** Held with a review deadline; topic {@code fs.alerts.medium}. */
  MEDIUM,
  /** Isolation-Forest-only, holds nothing, no timer; topic {@code fs.alerts.anomaly}. */
  ANOMALY
}
