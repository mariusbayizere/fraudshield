package io.github.mariusbayizere.fraudshield.auth.testing;

/** Reading a raw API key {@code fsk_<env>_<keyId>_<secret>} in tests. */
public final class ApiKeys {

  /** Length of every secret: 32 bytes in unpadded base64url (D-19). */
  static final int SECRET_LENGTH = 43;

  private ApiKeys() {}

  /**
   * The secret of a raw key, taken by its fixed length. The base64url alphabet contains "_", so
   * splitting on the last "_" returns only a tail of any secret that contains one. When the
   * secret's fifth-from-last character was "_", that tail was the last four the key list shows, and
   * a test asserting the list hid the secret failed on main on 2026-10-21.
   *
   * @param raw a raw API key
   * @return its 43-character secret
   */
  public static String secretOf(String raw) {
    return raw.substring(raw.length() - SECRET_LENGTH);
  }
}
