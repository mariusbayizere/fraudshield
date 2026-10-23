package io.github.mariusbayizere.fraudshield.auth.account;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The account state that each single-use token is bound to (ADR 0027). Using the token changes the
 * state, so the token cannot be used again. Times are at the database's microsecond precision.
 */
final class AccountTokens {

  private AccountTokens() {}

  /** Unlock tokens are bound to the lock being lifted. */
  static String unlockState(UUID userId, Instant lockedUntil) {
    return "unlock|" + userId + "|" + lockedUntil.truncatedTo(ChronoUnit.MICROS);
  }

  /** Reset tokens are bound to the password and session generation, and to the verified code. */
  static String resetState(UUID userId, long tokenVersion, String passwordHash, UUID codeId) {
    return "reset|" + userId + "|" + tokenVersion + "|" + passwordHash + "|" + codeId;
  }
}
