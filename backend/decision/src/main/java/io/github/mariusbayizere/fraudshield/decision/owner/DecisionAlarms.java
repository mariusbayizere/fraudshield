package io.github.mariusbayizere.fraudshield.decision.owner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The decision-owner contract's alarms (§7.4), read from PostgreSQL every 30 s by every instance,
 * without a leader lease: counts and ages only, across institutions, through V81's {@code
 * decision_alarm_figures}. M9's rules alert on each; the contract states each alarm's condition.
 * While PostgreSQL is down the figures keep their last values and the timeout paths' retry count is
 * the live signal (ADR 0111).
 */
public final class DecisionAlarms {

  /** The alarms, in the names {@code decision_alarm_figures} returns. */
  public static final List<String> ALARMS =
      List.of(
          "contested",
          "holds_overdue",
          "answers_deferred",
          "effects_pending",
          "claims_failed",
          "commits_overdue",
          "requests_unrecorded");

  private static final Logger LOG = LoggerFactory.getLogger(DecisionAlarms.class);

  private final DataSource dataSource;
  private final Map<String, double[]> latest = new ConcurrentHashMap<>();

  /**
   * Creates the reader.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public DecisionAlarms(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    for (String alarm : ALARMS) {
      latest.put(alarm, new double[] {0, 0});
    }
  }

  /** Reads the figures once. A failure keeps the last values and is logged. */
  public void refresh() {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT alarm, total, oldest_seconds FROM decision_alarm_figures()");
        ResultSet rows = s.executeQuery()) {
      while (rows.next()) {
        latest.put(rows.getString(1), new double[] {rows.getLong(2), rows.getDouble(3)});
      }
    } catch (SQLException e) {
      LOG.warn("the decision alarms' figures are unavailable; the last values stand", e);
    }
  }

  /**
   * How many the alarm counts.
   *
   * @param alarm the alarm
   * @return the count
   */
  public double count(String alarm) {
    return latest.getOrDefault(alarm, new double[] {0, 0})[0];
  }

  /**
   * The oldest's age.
   *
   * @param alarm the alarm
   * @return seconds
   */
  public double oldestSeconds(String alarm) {
    return latest.getOrDefault(alarm, new double[] {0, 0})[1];
  }
}
