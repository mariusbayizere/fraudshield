package io.github.mariusbayizere.fraudshield.admin.support;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque keyset-pagination cursors: base64url of {@code <epoch micros>|<id>}. A cursor only moves
 * the start of a page inside the caller's own institution, so it need not be signed.
 *
 * @param at position time
 * @param id position ID
 */
public record Cursor(Instant at, UUID id) {

  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;

  /**
   * Encodes the cursor.
   *
   * @return the opaque cursor
   */
  public String encode() {
    long micros = at.getEpochSecond() * MICROS_PER_SECOND + at.getNano() / NANOS_PER_MICRO;
    return Crypto.base64url((micros + "|" + id).getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Decodes a cursor.
   *
   * @param cursor the opaque cursor, or null
   * @return the cursor, or null for the first page
   * @throws ProblemException 422 {@code invalid_format} if it is not a cursor this API issued
   */
  public static Cursor decode(String cursor) {
    if (cursor == null || cursor.isEmpty()) {
      return null;
    }
    try {
      String text = new String(Crypto.fromBase64url(cursor), StandardCharsets.US_ASCII);
      int bar = text.indexOf('|');
      long micros = Long.parseLong(text.substring(0, bar));
      return new Cursor(
          Instant.ofEpochSecond(
              Math.floorDiv(micros, MICROS_PER_SECOND),
              Math.floorMod(micros, MICROS_PER_SECOND) * NANOS_PER_MICRO),
          UUID.fromString(text.substring(bar + 1)));
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      throw ProblemException.validation("cursor", "invalid_format", "Not a valid cursor");
    }
  }
}
