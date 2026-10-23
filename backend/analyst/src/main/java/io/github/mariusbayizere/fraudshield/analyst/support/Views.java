package io.github.mariusbayizere.fraudshield.analyst.support;

import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small conversions every analyst response shares: decimals, times, JSON columns and role ranks.
 */
public final class Views {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private Views() {}

  /**
   * A decimal in the contract's {@code DecimalAmount} form: no exponent, no trailing zeros.
   *
   * @param value the value
   * @return the text, or null for null
   */
  public static String decimal(BigDecimal value) {
    if (value == null) {
      return null;
    }
    if (value.signum() == 0) {
      return "0";
    }
    return value.stripTrailingZeros().toPlainString();
  }

  /**
   * An RFC 3339 UTC timestamp, as the contract's {@code Timestamp}.
   *
   * @param at the instant
   * @return the text, or null for null
   */
  public static String timestamp(Instant at) {
    return at == null ? null : at.toString();
  }

  /**
   * A nullable timestamp column.
   *
   * @param row the row
   * @param column the column
   * @return the instant, or null
   * @throws SQLException on a read failure
   */
  public static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  /**
   * A {@code jsonb} array of objects, read from its text.
   *
   * @param text the column's text
   * @return the rows, empty for null
   */
  public static List<Map<String, Object>> rows(String text) {
    return text == null ? List.of() : JSON.readValue(text, ROWS);
  }

  /**
   * A {@code jsonb} object, read from its text.
   *
   * @param text the column's text
   * @return the object, empty for null
   */
  public static Map<String, Object> object(String text) {
    return text == null ? Map.of() : JSON.readValue(text, OBJECT);
  }

  /**
   * The order E.8 gives staff roles for escalation: ANALYST, then SENIOR_ANALYST, then
   * RISK_OFFICER. ADMIN takes no part in alerts and ranks below them all.
   *
   * @param role the role
   * @return its rank
   */
  public static int rank(StaffRole role) {
    return switch (role) {
      case ANALYST -> 1;
      case SENIOR_ANALYST -> 2;
      case RISK_OFFICER -> 3;
      case ADMIN -> 0;
    };
  }
}
