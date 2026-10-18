package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.auth.domain.PasswordPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Hashes staff passwords with bcrypt, cost 12 (FR-07-07, D-19). Comparing against an account that
 * has no password, or none at all, still runs one bcrypt, so the response time does not reveal
 * whether the account exists.
 */
public final class PasswordHasher {

  /** The bcrypt cost factor (FR-07-07). */
  public static final int COST = 12;

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(COST);
  private final String dummyHash = encoder.encode("FraudShield-dummy-Password-1!");

  /**
   * Hashes a password that passed the policy.
   *
   * @param password the password
   * @return the bcrypt hash
   */
  public String hash(String password) {
    return encoder.encode(password);
  }

  /**
   * Compares a submitted password with a stored hash in roughly constant time.
   *
   * @param submitted the submitted password (at most 72 UTF-8 bytes; longer is refused before)
   * @param storedHash the stored hash, or null to compare against a dummy
   * @return whether it matches (always false for a null hash)
   */
  public boolean matches(String submitted, String storedHash) {
    if (!PasswordPolicy.fitsBcrypt(submitted)) {
      burn(); // same time as a real comparison, so the length does not reveal the account (finding
      // 6)
      return false;
    }
    boolean matches = encoder.matches(submitted, storedHash == null ? dummyHash : storedHash);
    return storedHash != null && matches;
  }

  /** Spends one bcrypt comparison without a result, to equalise timing. */
  public void burn() {
    encoder.matches("FraudShield-timing", dummyHash);
  }
}
