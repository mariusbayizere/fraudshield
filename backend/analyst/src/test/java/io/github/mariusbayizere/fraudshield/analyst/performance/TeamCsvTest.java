package io.github.mariusbayizere.fraudshield.analyst.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The team table's CSV (ADR 0117 point 8). */
@Tag("FR-04-11")
class TeamCsvTest {

  @Test
  void theFileIsUtf8WithByteOrderMarkCrlfAndOneHeaderRow() {
    byte[] csv = TeamCsv.write(team(List.of(row("Aline", "Uwase", 0.75, null))));

    assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
    String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
    String[] lines = text.split("\r\n", -1);
    assertThat(lines).hasSize(3);
    assertThat(lines[2]).isEmpty();
    assertThat(lines[0]).isEqualTo(String.join(",", TeamCsv.COLUMNS));
    assertThat(lines[0])
        .doesNotContain("escalations")
        .doesNotContain("overturned_decisions")
        .doesNotContain("overrides_made");
    assertThat(lines[1])
        .isEqualTo(
            "0b6f7c1e-0000-4000-8000-000000000001,Aline,Uwase,ANALYST,2026-10-20,2026-10-22,"
                + "'+02:00,Africa/Kigali,96,18,5.3333,412.5,,24,0.75,7,");
  }

  @Test
  void cellsAreQuotedAsRfc4180RequiresAndFormulasAreNeutralised() {
    assertThat(TeamCsv.cell("Uwase, Aline")).isEqualTo("\"Uwase, Aline\"");
    assertThat(TeamCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(TeamCsv.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
    assertThat(TeamCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    for (String formula : List.of("+1", "-1", "@SUM(A1)", "\tx", "\rx")) {
      assertThat(TeamCsv.neutralise(formula)).startsWith("'");
    }
    assertThat(TeamCsv.cell("Nyiramana")).isEqualTo("Nyiramana");
    assertThat(TeamCsv.cell("Ngabo-Mugisha")).isEqualTo("Ngabo-Mugisha");
  }

  @Test
  void numbersUsePointAtMostFourDecimalsAndNoSeparatorAndNullIsEmpty() {
    assertThat(TeamCsv.cell(null)).isEmpty();
    assertThat(TeamCsv.cell(1234567L)).isEqualTo("1234567");
    assertThat(TeamCsv.cell(2.0 / 3)).isEqualTo("0.6667");
    assertThat(TeamCsv.cell(1_234_567.5)).isEqualTo("1234567.5");
    assertThat(TeamCsv.cell(0.0)).isEqualTo("0");
    assertThat(TeamCsv.cell(1e-9)).isEqualTo("0");
  }

  @Test
  void theWorstCaseThousandRowsStayUnderOneMebibyte() {
    // 100 code points of 4 bytes each per name, a 64-character zone, and large figures.
    String name = "𐐀".repeat(100);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < Figures.MAX_ROWS; i++) {
      Map<String, Object> row = row(name, name, 1.0 / 3, 2_678_399.9999);
      row.put("role", "SENIOR_ANALYST");
      row.put("reviews", 9_999_999L);
      row.put("active_hours", 744L);
      row.put("reviews_per_active_hour", 13_440.8589);
      row.put("verified_decisions", 9_999_999L);
      row.put("verified_legitimate_decisions", 9_999_999L);
      row.put("false_positive_rate", 1.0 / 3);
      rows.add(row);
    }
    Map<String, Object> team = team(rows);
    team.put("time_zone", "A" + "b".repeat(63));
    team.put("utc_offset", "-12:00");

    assertThat(TeamCsv.write(team).length).isLessThanOrEqualTo(TeamCsv.MAX_BYTES);
  }

  @Test
  void csvIsChosenOnlyWhenPreferredOverJson() {
    assertThat(PerformanceController.wantsCsv("text/csv")).isTrue();
    assertThat(PerformanceController.wantsCsv("text/csv, application/json;q=0.5")).isTrue();
    assertThat(PerformanceController.wantsCsv("application/json, text/csv;q=0.5")).isFalse();
    assertThat(PerformanceController.wantsCsv("*/*")).isFalse();
    assertThat(PerformanceController.wantsCsv(null)).isFalse();
    assertThat(PerformanceController.wantsCsv("text/csv;q=0, application/json")).isFalse();
  }

  private static Map<String, Object> team(List<Map<String, Object>> rows) {
    Map<String, Object> team = new LinkedHashMap<>();
    team.put("from", "2026-10-20");
    team.put("to", "2026-10-22");
    team.put("day_starts_at", "2026-10-19T22:00:00Z");
    team.put("utc_offset", "+02:00");
    team.put("time_zone", "Africa/Kigali");
    team.put("analysts", rows);
    return team;
  }

  private static Map<String, Object> row(
      String first, String last, Double agreement, Double average) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("user_id", UUID.fromString("0b6f7c1e-0000-4000-8000-000000000001"));
    row.put("first_name", first);
    row.put("last_name", last);
    row.put("role", "ANALYST");
    row.put("reviews", 96L);
    row.put("active_hours", 18L);
    row.put("reviews_per_active_hour", 96.0 / 18);
    row.put("average_seconds_to_decision", average == null ? 412.5 : average);
    row.put("average_seconds_from_assignment", null);
    row.put("escalations", 4L);
    row.put("overturned_decisions", 1L);
    row.put("overrides_made", null);
    row.put("verified_decisions", 24L);
    row.put("customer_agreement_rate", agreement);
    row.put("verified_legitimate_decisions", 7L);
    row.put("false_positive_rate", null);
    return row;
  }
}
