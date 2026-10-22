package io.github.mariusbayizere.fraudshield.analyst.performance;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The team table as CSV (ADR 0117 point 8): RFC 4180, UTF-8 with a byte-order mark, CRLF line ends
 * and one header row. Only the columns a source asks for, plus what is needed to read the file;
 * escalations, overturned decisions and overrides stay in the JSON table. Numbers use {@code .}
 * with at most 4 decimals and no thousands separator, a null is an empty cell, and a text cell that
 * a spreadsheet could read as a formula is prefixed with {@code '}.
 */
public final class TeamCsv {

  /** The response's media type (RFC 4180 section 3). */
  public static final String MEDIA_TYPE = "text/csv; charset=utf-8; header=present";

  /** The byte bound of the file (1 MiB). */
  public static final int MAX_BYTES = 1_048_576;

  /** The columns, in order. */
  public static final List<String> COLUMNS =
      List.of(
          "user_id",
          "first_name",
          "last_name",
          "role",
          "from",
          "to",
          "utc_offset",
          "time_zone",
          "reviews",
          "active_hours",
          "reviews_per_active_hour",
          "average_seconds_to_decision",
          "average_seconds_from_assignment",
          "verified_decisions",
          "customer_agreement_rate",
          "verified_legitimate_decisions",
          "false_positive_rate");

  /** The columns read from the table's header rather than from the row. */
  private static final List<String> HEADER_COLUMNS =
      List.of("from", "to", "utc_offset", "time_zone");

  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final String CRLF = "\r\n";

  private TeamCsv() {}

  /**
   * Writes the file.
   *
   * @param team the {@code TeamPerformance} body
   * @return the file's bytes
   */
  public static byte[] write(Map<String, Object> team) {
    StringBuilder text = new StringBuilder();
    line(text, COLUMNS.stream().map(TeamCsv::cell).toList());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) team.get("analysts");
    for (Map<String, Object> row : rows) {
      line(
          text,
          COLUMNS.stream()
              .map(
                  column ->
                      cell(HEADER_COLUMNS.contains(column) ? team.get(column) : row.get(column)))
              .toList());
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(BOM);
    out.writeBytes(text.toString().getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static void line(StringBuilder text, List<String> cells) {
    text.append(String.join(",", cells)).append(CRLF);
  }

  /**
   * One cell.
   *
   * @param value a string, a number, another value written as its text, or null
   * @return the cell, quoted where RFC 4180 requires it
   */
  static String cell(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
      return number(value);
    }
    if (value instanceof Number) {
      return value.toString();
    }
    return quote(neutralise(value.toString()));
  }

  private static String number(Object value) {
    BigDecimal decimal =
        value instanceof BigDecimal big ? big : BigDecimal.valueOf(((Number) value).doubleValue());
    BigDecimal rounded = decimal.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    return rounded.signum() == 0 ? "0" : rounded.toPlainString();
  }

  /** Formula injection: a leading =, +, -, @, tab or carriage return is prefixed with '. */
  static String neutralise(String text) {
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      return "'" + text;
    }
    return text;
  }

  private static String quote(String text) {
    boolean needs =
        text.indexOf(',') >= 0
            || text.indexOf('"') >= 0
            || text.indexOf('\r') >= 0
            || text.indexOf('\n') >= 0;
    return needs ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
  }
}
