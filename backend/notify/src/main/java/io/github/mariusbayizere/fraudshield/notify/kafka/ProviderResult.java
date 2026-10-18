package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the SMS provider answered for one record, kept so that a re-read writes the outcome and
 * never calls the provider again (docs/architecture/decision-fact-ordering.md, 12.4). Its text form
 * is W-e2's {@code result}, carried by the checkpoint metadata and by the {@code fs-dlq-result} and
 * {@code fs-kept-result} headers.
 *
 * @param sent true for SENT (the reply was read and accepted), false for FAILED (not known to be
 *     accepted)
 * @param locale the BCP 47 tag the SMS was rendered in
 * @param link whether the SMS carried a verification link
 * @param reference the provider's message id for SENT, at most {@value #MAX_REFERENCE} code points;
 *     null when there is none
 * @param mayBeDelivered for FAILED, whether the call may have reached the provider without a
 *     readable acceptance; never written to the text form
 */
public record ProviderResult(
    boolean sent, String locale, boolean link, String reference, boolean mayBeDelivered) {

  /** The longest reference kept, the limit of {@code customer_notifications.provider_reference}. */
  public static final int MAX_REFERENCE = 128;

  private static final Pattern LOCALE = Pattern.compile("[A-Za-z0-9-]{1,35}");
  private static final Pattern TEXT =
      Pattern.compile(
          "^(?:FAILED:([A-Za-z0-9-]{1,35}):([01])|SENT:([A-Za-z0-9-]{1,35}):([01]):"
              + "([A-Za-z0-9_-]*))$");

  /** Validates the result and normalises its reference (12.4, W-e2). */
  public ProviderResult {
    Objects.requireNonNull(locale, "locale");
    if (!LOCALE.matcher(locale).matches()) {
      throw new IllegalArgumentException("a locale is a BCP 47 tag: letters, digits and hyphens");
    }
    if (!sent) {
      reference = null;
    } else {
      reference = normalise(reference);
    }
    if (sent) {
      mayBeDelivered = false;
    }
  }

  /**
   * A SENT result.
   *
   * @param locale the locale the SMS was rendered in
   * @param link whether it carried a link
   * @param reference the provider's message id, possibly null or empty
   * @return the result
   */
  public static ProviderResult sent(String locale, boolean link, String reference) {
    return new ProviderResult(true, locale, link, reference, false);
  }

  /**
   * A FAILED result.
   *
   * @param locale the locale the SMS was rendered in
   * @param link whether it carried a link
   * @param mayBeDelivered whether the call may have reached the provider without a readable answer
   * @return the result
   */
  public static ProviderResult failed(String locale, boolean link, boolean mayBeDelivered) {
    return new ProviderResult(false, locale, link, null, mayBeDelivered);
  }

  /**
   * W-e2's {@code result} form: {@code FAILED:<locale>:<link>} or {@code
   * SENT:<locale>:<link>:<reference>}, the reference UTF-8 and base64url without padding.
   *
   * @return the text form
   */
  public String text() {
    String head = (sent ? "SENT:" : "FAILED:") + locale + ":" + (link ? "1" : "0");
    if (!sent) {
      return head;
    }
    String ref = reference == null ? "" : reference;
    return head
        + ":"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ref.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Parses W-e2's {@code result} form; {@code none} and anything that does not parse are empty.
   *
   * @param text the text form
   * @return the result, or empty
   */
  public static Optional<ProviderResult> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = TEXT.matcher(text);
    if (!m.matches()) {
      return Optional.empty();
    }
    if (m.group(1) != null) {
      return Optional.of(failed(m.group(1), "1".equals(m.group(2)), false));
    }
    String reference;
    try {
      reference = new String(Base64.getUrlDecoder().decode(m.group(5)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return Optional.empty();
    }
    if (reference.codePointCount(0, reference.length()) > MAX_REFERENCE) {
      return Optional.empty();
    }
    return Optional.of(sent(m.group(3), "1".equals(m.group(4)), reference));
  }

  /**
   * A reference as kept: U+0000 replaced by U+FFFD, longer than {@value #MAX_REFERENCE} code points
   * cut to 127 followed by {@code ~}, and empty as null (12.4, W-e2).
   */
  private static String normalise(String reference) {
    if (reference == null || reference.isEmpty()) {
      return null;
    }
    String clean = reference.replace('\u0000', '�');
    if (clean.codePointCount(0, clean.length()) <= MAX_REFERENCE) {
      return clean;
    }
    return clean.substring(0, clean.offsetByCodePoints(0, MAX_REFERENCE - 1)) + "~";
  }
}
