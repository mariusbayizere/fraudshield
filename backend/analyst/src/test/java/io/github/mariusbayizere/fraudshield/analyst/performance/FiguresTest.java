package io.github.mariusbayizere.fraudshield.analyst.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The team range and the rates of ADR 0117 (points 5 and 7). */
@Tag("FR-04-11")
class FiguresTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

  @Test
  void toDefaultsToTodayAndFromToTo() {
    assertThat(Figures.Range.of(null, null, TODAY)).isEqualTo(new Figures.Range(TODAY, TODAY));
    assertThat(Figures.Range.of(null, "2026-10-21", TODAY))
        .isEqualTo(new Figures.Range(TODAY.minusDays(1), TODAY.minusDays(1)));
  }

  @Test
  void rangeIsAtMostThirtyOneDaysAndEndsNoLaterThanToday() {
    assertThat(Figures.Range.of("2026-09-22", "2026-10-22", TODAY).from())
        .isEqualTo(LocalDate.of(2026, 9, 22));
    assertRefused("2026-09-21", "2026-10-22", "from", "out_of_range");
    assertRefused(null, "2026-10-23", "to", "out_of_range");
    assertRefused("2026-10-22", "2026-10-21", "from", "out_of_range");
    assertRefused("22/10/2026", null, "from", "invalid_format");
    assertRefused(null, "2026-02-30", "to", "invalid_format");
  }

  @Test
  void rateIsNullBelowTwentyAndShareFromTwenty() {
    assertThat(Figures.rate(19, 19)).isNull();
    assertThat(Figures.rate(0, 20)).isZero();
    assertThat(Figures.rate(15, 20)).isEqualTo(0.75);
  }

  @Test
  void reviewsPerActiveHourIsNullWithoutAnActiveHour() {
    assertThat(Figures.perActiveHour(0, 0)).isNull();
    assertThat(Figures.perActiveHour(12, 3)).isEqualTo(4.0);
    assertThat(Figures.perActiveHour(1, 1)).isGreaterThanOrEqualTo(1.0);
  }

  private static void assertRefused(String from, String to, String field, String code) {
    assertThatThrownBy(() -> Figures.Range.of(from, to, TODAY))
        .isInstanceOfSatisfying(
            ProblemException.class,
            problem -> {
              assertThat(problem.status()).isEqualTo(422);
              assertThat(problem.errors())
                  .singleElement()
                  .satisfies(
                      error -> {
                        assertThat(error.field()).isEqualTo(field);
                        assertThat(error.code()).isEqualTo(code);
                      });
            });
  }
}
