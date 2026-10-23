package io.github.mariusbayizere.fraudshield.common.config;

/** Staff roles (E.8, ADR 0014); every account holds exactly one. */
public enum StaffRole {
  /** Reviews alerts. */
  ANALYST,
  /** Reviews alerts and escalations. */
  SENIOR_ANALYST,
  /** Owns risk configuration and overrides. */
  RISK_OFFICER,
  /** Manages users, keys and models; never risk configuration. */
  ADMIN
}
