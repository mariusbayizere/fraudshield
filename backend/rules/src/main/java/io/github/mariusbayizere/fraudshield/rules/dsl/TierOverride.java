package io.github.mariusbayizere.fraudshield.rules.dsl;

/** The tier a matching rule raises a transaction to. Rules never lower a tier (E.6 step 5). */
public enum TierOverride {
  /** At least MEDIUM. */
  MEDIUM,
  /** HIGH. */
  HIGH
}
