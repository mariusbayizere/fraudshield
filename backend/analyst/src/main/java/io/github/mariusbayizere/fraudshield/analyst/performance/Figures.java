package io.github.mariusbayizere.fraudshield.analyst.performance;

import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;

/**
 * The rules and constants of ADR 0117 that need no database: the agreement windows and minimums,
 * the team range, and the rates. The constants are ASSUMED starting values (ADR 0117, open question
 * 8).
 */
public final class Figures {

  /** Institution days in the agreement windows and in the daily series. */
  public static final int WINDOW_DAYS = 30;

  /** Least denominator below which a rate is null. */
  public static final long MINIMUM_VERIFIED = 20;

  /** Most institution days in a team range. */
  public static final int MAX_RANGE_DAYS = 31;

  /** Most rows of the team table and its CSV. */
  public static final int MAX_ROWS = 1000;

  /** Days of transactions before today's start that the decline lookup reads (point 4). */
  public static final int DECLINE_LOOKBACK_DAYS = 30;

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  private Figures() {}

  /**
   * A team range, validated (ADR 0117 point 7).
   *
   * @param from the first day, inclusive
   * @param to the last day, inclusive
   */
  public record Range(LocalDate from, LocalDate to) {

    /**
     * Reads the query parameters: {@code to} defaults to today and {@code from} to {@code to}; a
     * {@code to} after today, a {@code from} after {@code to}, or more than 31 days is 422 {@code
     * out_of_range}.
     *
     * @param from the {@code from} parameter, or null
     * @param to the {@code to} parameter, or null
     * @param today the institution's today
     * @return the range
     */
    public static Range of(String from, String to, LocalDate today) {
      final LocalDate last = to == null ? today : date("to", to);
      final LocalDate first = from == null ? last : date("from", from);
      if (last.isAfter(today)) {
        throw ProblemException.validation("to", "out_of_range", "Must not be after today");
      }
      if (first.isAfter(last)) {
        throw ProblemException.validation("from", "out_of_range", "Must not be after to");
      }
      if (ChronoUnit.DAYS.between(first, last) >= MAX_RANGE_DAYS) {
        throw ProblemException.validation(
            "from", "out_of_range", "A range is at most " + MAX_RANGE_DAYS + " days");
      }
      return new Range(first, last);
    }
  }

  /**
   * A rate whose denominator must reach the minimum.
   *
   * @param numerator the numerator
   * @param denominator the denominator
   * @return numerator / denominator, or null while the denominator is below {@link
   *     #MINIMUM_VERIFIED}
   */
  public static Double rate(long numerator, long denominator) {
    if (denominator < MINIMUM_VERIFIED) {
      return null;
    }
    return (double) numerator / denominator;
  }

  /**
   * Reviews per active hour (point 7).
   *
   * @param reviews reviews in the range
   * @param activeHours distinct institution clock-hours with a review
   * @return the ratio, or null when there is no active hour
   */
  public static Double perActiveHour(long reviews, long activeHours) {
    return activeHours == 0 ? null : (double) reviews / activeHours;
  }

  private static LocalDate date(String field, String value) {
    try {
      return LocalDate.parse(value, DATE);
    } catch (DateTimeParseException e) {
      throw ProblemException.validation(field, "invalid_format", "Use a date, YYYY-MM-DD");
    }
  }
}
