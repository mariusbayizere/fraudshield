package io.github.mariusbayizere.fraudshield.common.config;

/** Life cycle of a risk-configuration change. */
public enum ChangeStatus {
  /** A loosening change waiting for a second risk officer; nothing is applied yet. */
  PENDING_APPROVAL,
  /** A tightening change already in effect, waiting for confirmation within 24 hours. */
  APPLIED_PENDING_CONFIRMATION,
  /** A loosening change approved by a second risk officer and applied. */
  APPROVED,
  /** A tightening change confirmed by a second risk officer. */
  CONFIRMED,
  /** A loosening change rejected by a second risk officer; never applied. */
  REJECTED,
  /** A tightening change undone, by rejection or because nobody confirmed it within 24 hours. */
  REVERTED,
  /**
   * Replaced by a later tightening change of the same kind before review: a loosening proposal is
   * dropped, and an unconfirmed tightening is folded into the new change, which keeps its baseline.
   */
  SUPERSEDED,
  /** A loosening proposal withdrawn by its proposer before review. */
  WITHDRAWN
}
