package io.github.mariusbayizere.fraudshield.notify.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The {@code decision.final} webhook signature ({@code contracts/webhooks/decision-final.md}):
 * {@code v1 = hex(HMAC-SHA256(secret, t + "." + body))}, one {@code v1} per active secret during
 * rotation. Verification is the integrators' side, implemented here to the same shared vectors as
 * the Python reference so both sides provably agree.
 */
public final class WebhookSignatures {

  /** Replay window in seconds; exactly 300 is accepted. */
  public static final long REPLAY_WINDOW_SECONDS = 300;

  private static final Pattern TIMESTAMP = Pattern.compile("0|[1-9][0-9]{0,11}");
  private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");

  /** Verification outcome. */
  public enum Verdict {
    /** A signature verifies with a held secret, inside the window. */
    VALID,
    /** No signature verifies. */
    INVALID_SIGNATURE,
    /** Outside the replay window. */
    EXPIRED,
    /** The header does not parse. */
    MALFORMED
  }

  private WebhookSignatures() {}

  /**
   * The {@code v1} value for one secret.
   *
   * @param secret signing secret ({@code whsec_...})
   * @param timestamp unix seconds of this attempt
   * @param body exact request body
   * @return lower-case hex HMAC-SHA256
   */
  public static String sign(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }

  /**
   * The {@code X-FraudShield-Signature} header for an attempt.
   *
   * @param secrets every active secret (two during rotation)
   * @param timestamp unix seconds of this attempt
   * @param body exact request body
   * @return {@code t=<ts>,v1=<hex>[,v1=<hex>]}
   */
  public static String header(List<String> secrets, long timestamp, byte[] body) {
    if (secrets.isEmpty()) {
      throw new IllegalArgumentException("at least one signing secret is required");
    }
    StringBuilder header = new StringBuilder("t=").append(timestamp);
    for (String secret : secrets) {
      header.append(",v1=").append(sign(secret, timestamp, body));
    }
    return header.toString();
  }

  /**
   * Verifies a header as a receiver must.
   *
   * @param header the header value
   * @param body exact bytes received
   * @param secrets secrets the receiver holds
   * @param now receiver's unix seconds
   * @return the verdict
   */
  public static Verdict verify(String header, byte[] body, List<String> secrets, long now) {
    List<Long> timestamps = new ArrayList<>();
    List<String> signatures = new ArrayList<>();
    for (String part : header.split(",", -1)) {
      String trimmed = part.strip();
      int equals = trimmed.indexOf('=');
      String key = equals < 0 ? trimmed : trimmed.substring(0, equals);
      String value = equals < 0 ? "" : trimmed.substring(equals + 1);
      if (key.equals("t")) {
        if (!TIMESTAMP.matcher(value).matches()) {
          return Verdict.MALFORMED;
        }
        timestamps.add(Long.parseLong(value));
      } else if (key.equals("v1")) {
        if (!SIGNATURE.matcher(value).matches()) {
          return Verdict.MALFORMED;
        }
        signatures.add(value);
      }
    }
    if (timestamps.size() != 1 || signatures.isEmpty()) {
      return Verdict.MALFORMED;
    }
    long timestamp = timestamps.getFirst();
    if (Math.abs(now - timestamp) > REPLAY_WINDOW_SECONDS) {
      return Verdict.EXPIRED;
    }
    for (String secret : secrets) {
      byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
      for (String signature : signatures) {
        if (MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
          return Verdict.VALID;
        }
      }
    }
    return Verdict.INVALID_SIGNATURE;
  }

  /**
   * The receiver's ordering rule (ADR 0011 section 9): apply a state only if its sequence is
   * greater than the last applied; acknowledge and ignore anything else.
   *
   * @param lastApplied last applied sequence, or null
   * @param sequence the delivery's sequence
   * @return whether to apply it
   */
  public static boolean shouldApply(Integer lastApplied, int sequence) {
    return lastApplied == null || sequence > lastApplied;
  }
}
