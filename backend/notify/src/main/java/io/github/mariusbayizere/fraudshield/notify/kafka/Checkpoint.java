package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A partition's checkpoint, carried as a commit's metadata (docs/architecture/decision-fact-
 * ordering.md, W-e and W-e2): the wait budget's spent time, and the head record's retry state and
 * kept provider result. Written as {@code v3}; {@code v2} is read with no head state.
 *
 * @param offset the committed offset it is for
 * @param spentMillis the wait budget's spent time
 * @param atEpochMillis when it was written, on the wall clock
 * @param f4Count the head record's F4 failures (12.3)
 * @param f4Millis the head record's F4 time (12.3)
 * @param result the head record's kept provider result, or null
 */
record Checkpoint(
    long offset,
    long spentMillis,
    long atEpochMillis,
    int f4Count,
    long f4Millis,
    ProviderResult result) {

  private static final String NUMBER = "(0|[1-9][0-9]{0,18})";
  private static final Pattern V2 =
      Pattern.compile(
          "^fs-wait:v2 offset=" + NUMBER + " spent_ms=" + NUMBER + " at=" + NUMBER + "$");
  private static final Pattern V3 =
      Pattern.compile(
          "^fs-wait:v3 offset="
              + NUMBER
              + " spent_ms="
              + NUMBER
              + " at="
              + NUMBER
              + " f4_count="
              + NUMBER
              + " f4_ms="
              + NUMBER
              + " result=(\\S+)$");

  /** Validates the values. */
  Checkpoint {
    if (offset < 0 || spentMillis < 0 || atEpochMillis < 0 || f4Count < 0 || f4Millis < 0) {
      throw new IllegalArgumentException("checkpoint values are not negative");
    }
  }

  /**
   * Whether this checkpoint carries anything a new owner needs: spent time, retry state or a kept
   * result. A commit without any carries no metadata (W-e).
   *
   * @return true when it must be written
   */
  boolean carriesState() {
    return spentMillis > 0 || f4Count > 0 || f4Millis > 0 || result != null;
  }

  /**
   * W-e2's {@code v3} line, or an empty string when nothing needs carrying.
   *
   * @return the metadata
   */
  String text() {
    if (!carriesState()) {
      return "";
    }
    return "fs-wait:v3 offset="
        + offset
        + " spent_ms="
        + spentMillis
        + " at="
        + atEpochMillis
        + " f4_count="
        + f4Count
        + " f4_ms="
        + f4Millis
        + " result="
        + (result == null ? "none" : result.text());
  }

  /**
   * Reads committed metadata: {@code v3}, or {@code v2} with no head state. Anything else, a line
   * that does not parse included, is empty; the caller logs it (W-e2).
   *
   * @param metadata the committed metadata, possibly null or empty
   * @return the checkpoint, or empty
   */
  static Optional<Checkpoint> parse(String metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Optional.empty();
    }
    try {
      Matcher v3 = V3.matcher(metadata);
      if (v3.matches()) {
        ProviderResult result = null;
        if (!"none".equals(v3.group(6))) {
          Optional<ProviderResult> parsed = ProviderResult.parse(v3.group(6));
          if (parsed.isEmpty()) {
            return Optional.empty();
          }
          result = parsed.get();
        }
        long f4Count = Long.parseLong(v3.group(4));
        if (f4Count > Integer.MAX_VALUE) {
          return Optional.empty();
        }
        return Optional.of(
            new Checkpoint(
                Long.parseLong(v3.group(1)),
                Long.parseLong(v3.group(2)),
                Long.parseLong(v3.group(3)),
                (int) f4Count,
                Long.parseLong(v3.group(5)),
                result));
      }
      Matcher v2 = V2.matcher(metadata);
      if (v2.matches()) {
        return Optional.of(
            new Checkpoint(
                Long.parseLong(v2.group(1)),
                Long.parseLong(v2.group(2)),
                Long.parseLong(v2.group(3)),
                0,
                0,
                null));
      }
    } catch (NumberFormatException tooLarge) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
