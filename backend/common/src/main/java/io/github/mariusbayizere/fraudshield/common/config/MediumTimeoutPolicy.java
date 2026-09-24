package io.github.mariusbayizere.fraudshield.common.config;

/** What happens to a HOLD whose review deadline passes (D-10). */
public enum MediumTimeoutPolicy {
  /** Approve with a timeout label (SRS default); less blocking. */
  RELEASE_WITH_TIMEOUT_LABEL,
  /** Decline and ask the customer to verify; more blocking. */
  DECLINE_AND_VERIFY
}
