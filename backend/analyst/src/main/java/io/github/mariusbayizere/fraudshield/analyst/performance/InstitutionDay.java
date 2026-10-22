package io.github.mariusbayizere.fraudshield.analyst.performance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/**
 * One institution day (ADR 0117 point 1): from local 00:00 in the institution's IANA zone,
 * inclusive, to the next local 00:00, exclusive. The definition does not depend on the zone having
 * no daylight saving: a day of a zone that changes its offset lasts 23 or 25 hours.
 *
 * @param zone the institution's zone
 * @param date the local date
 */
public record InstitutionDay(ZoneId zone, LocalDate date) {

  /** Checks the fields. */
  public InstitutionDay {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(date, "date");
  }

  /**
   * The institution day an instant falls in.
   *
   * @param zone the institution's zone
   * @param at the instant
   * @return the day
   */
  public static InstitutionDay of(ZoneId zone, Instant at) {
    return new InstitutionDay(zone, LocalDate.ofInstant(at, zone));
  }

  /**
   * The day's first instant: the first instant of the local date, which is local midnight unless
   * the zone skips it.
   *
   * @return the start, inclusive
   */
  public Instant start() {
    return date.atStartOfDay(zone).toInstant();
  }

  /**
   * The next day's first instant.
   *
   * @return the end, exclusive
   */
  public Instant end() {
    return date.plusDays(1).atStartOfDay(zone).toInstant();
  }

  /**
   * Another day of the same zone.
   *
   * @param days days to add, negative for earlier days
   * @return the day
   */
  public InstitutionDay plusDays(long days) {
    return new InstitutionDay(zone, date.plusDays(days));
  }

  /**
   * The offset in force at the day's start, as the contract's {@code utc_offset}: {@code +02:00},
   * never {@code Z}; seconds of a historical offset are dropped.
   *
   * @return the offset
   */
  public String utcOffset() {
    return offset(zone.getRules().getOffset(start()));
  }

  /**
   * An offset as {@code [+-]HH:MM}.
   *
   * @param offset the offset
   * @return the text
   */
  static String offset(ZoneOffset offset) {
    int minutes = offset.getTotalSeconds() / 60;
    char sign = minutes < 0 ? '-' : '+';
    int magnitude = Math.abs(minutes);
    return String.format(Locale.ROOT, "%c%02d:%02d", sign, magnitude / 60, magnitude % 60);
  }
}
