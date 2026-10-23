package io.github.mariusbayizere.fraudshield.notify.sms;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Short reference codes the customer can quote to the institution (D-25): eight Crockford base-32
 * characters (no I, L, O or U) derived from the auto-block event id, so staff can find the block
 * from the code without a lookup table and the code reveals nothing else.
 */
public final class ReferenceCodes {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

  private ReferenceCodes() {}

  /**
   * The code of a block.
   *
   * @param autoBlockEventId the block
   * @return eight characters from {@code [0-9A-Z]}
   */
  public static String of(UUID autoBlockEventId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(autoBlockEventId.toString().getBytes(StandardCharsets.US_ASCII));
      long bits = ByteBuffer.wrap(digest).getLong() >>> 24;
      char[] code = new char[8];
      for (int i = 7; i >= 0; i--) {
        code[i] = ALPHABET[(int) (bits & 31)];
        bits >>>= 5;
      }
      return new String(code);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }
}
