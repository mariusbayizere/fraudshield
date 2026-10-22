package io.github.mariusbayizere.fraudshield.analyst.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The institution's day (ADR 0117 point 1): local midnight to local midnight in its own zone. */
@Tag("FR-04-11")
@Tag("UX-DASH-01")
class InstitutionDayTest {

  private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

  @Test
  void kigaliDayStartsAtLocalMidnightNotAtUtcMidnight() {
    InstitutionDay day = InstitutionDay.of(KIGALI, Instant.parse("2026-10-22T21:59:59Z"));

    assertThat(day.date()).isEqualTo(LocalDate.of(2026, 10, 22));
    assertThat(day.start()).isEqualTo(Instant.parse("2026-10-21T22:00:00Z"));
    assertThat(day.end()).isEqualTo(Instant.parse("2026-10-22T22:00:00Z"));
    assertThat(day.utcOffset()).isEqualTo("+02:00");
  }

  @Test
  void localMidnightBeginsTheNextDay() {
    assertThat(InstitutionDay.of(KIGALI, Instant.parse("2026-10-22T22:00:00Z")).date())
        .isEqualTo(LocalDate.of(2026, 10, 23));
  }

  @Test
  void kinshasaAndLubumbashiDaysStartAnHourApart() {
    Instant at = Instant.parse("2026-10-22T12:00:00Z");
    InstitutionDay kinshasa = InstitutionDay.of(ZoneId.of("Africa/Kinshasa"), at);
    InstitutionDay lubumbashi = InstitutionDay.of(ZoneId.of("Africa/Lubumbashi"), at);

    assertThat(kinshasa.start()).isEqualTo(Instant.parse("2026-10-21T23:00:00Z"));
    assertThat(kinshasa.utcOffset()).isEqualTo("+01:00");
    assertThat(lubumbashi.start()).isEqualTo(Instant.parse("2026-10-21T22:00:00Z"));
    assertThat(lubumbashi.utcOffset()).isEqualTo("+02:00");
  }

  @Test
  void daylightSavingDayLastsTwentyThreeHoursAndUtcIsWrittenAsOffset() {
    InstitutionDay spring =
        new InstitutionDay(ZoneId.of("Europe/Paris"), LocalDate.of(2026, 3, 29));

    assertThat(spring.end().getEpochSecond() - spring.start().getEpochSecond())
        .isEqualTo(23 * 3600);
    assertThat(new InstitutionDay(ZoneId.of("UTC"), LocalDate.of(2026, 1, 1)).utcOffset())
        .isEqualTo("+00:00");
    assertThat(InstitutionDay.offset(ZoneOffset.ofHoursMinutes(-3, -30))).isEqualTo("-03:30");
  }

  @Test
  void plusDaysStaysInTheZone() {
    InstitutionDay day = new InstitutionDay(KIGALI, LocalDate.of(2026, 10, 22));

    assertThat(day.plusDays(-29).date()).isEqualTo(LocalDate.of(2026, 9, 23));
    assertThat(day.plusDays(-29).zone()).isEqualTo(KIGALI);
  }
}
