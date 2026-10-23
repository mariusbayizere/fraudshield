package io.github.mariusbayizere.fraudshield.auth.domain;

/** Staff account status (D-31); only ACTIVE accounts can sign in or hold a session. */
public enum AccountStatus {
  /**
   * Registered or created by Google sign-in; no access until an administrator approves (D-23,
   * D-24).
   */
  PENDING_APPROVAL,
  /** May sign in. */
  ACTIVE,
  /** Locked after failed sign-ins (auto-unlock after 30 minutes, D-26) or by an administrator. */
  LOCKED,
  /** Blocked from every sign-in method (FR-06-02). */
  DEACTIVATED
}
