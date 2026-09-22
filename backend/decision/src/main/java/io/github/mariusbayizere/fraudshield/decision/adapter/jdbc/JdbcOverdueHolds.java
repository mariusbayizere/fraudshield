package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.OverdueHoldsPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Overdue holds from {@code decision_states} across institutions. Runs as {@code fs_app}: V62's
 * {@code institutions_with_overdue_holds} names the institutions (ids only), then one query per
 * institution, because row-level security scopes every read to one tenant.
 */
public final class JdbcOverdueHolds implements OverdueHoldsPort {

  private static final String SELECT_INSTITUTIONS =
      "SELECT institutions_with_overdue_holds FROM institutions_with_overdue_holds(?)";

  private static final String SELECT_OVERDUE =
      """
      SELECT h.transaction_id, h.review_deadline_at, t.channel,
      coalesce((SELECT r.medium_timeout_policy FROM risk_thresholds r
        WHERE r.channel = t.channel AND r.version = (SELECT max(v.version)
          FROM risk_threshold_versions v WHERE v.effective_at <= h.decided_at)),
        'RELEASE_WITH_TIMEOUT_LABEL')
      FROM decision_states h JOIN v_transactions t ON t.transaction_id = h.transaction_id
      WHERE h.decision = 'HOLD' AND h.review_deadline_at < ?
      AND NOT EXISTS (SELECT 1 FROM decision_states n WHERE n.transaction_id = h.transaction_id
        AND n.decision_sequence > 1)
      ORDER BY h.review_deadline_at LIMIT ?
      """;

  private final DataSource dataSource;

  /**
   * Creates the adapter.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JdbcOverdueHolds(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public List<HoldSchedulePort.DueHold> overdue(Instant before, int max) {
    List<HoldSchedulePort.DueHold> overdue = new ArrayList<>();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      List<UUID> institutions = new ArrayList<>();
      try (PreparedStatement s = c.prepareStatement(SELECT_INSTITUTIONS)) {
        s.setObject(1, Tenant.utc(before));
        try (ResultSet rows = s.executeQuery()) {
          while (rows.next()) {
            institutions.add(rows.getObject(1, UUID.class));
          }
        }
      }
      for (UUID institution : institutions) {
        Tenant.use(c, institution);
        try (PreparedStatement s = c.prepareStatement(SELECT_OVERDUE)) {
          s.setObject(1, Tenant.utc(before));
          s.setInt(2, max - overdue.size());
          try (ResultSet row = s.executeQuery()) {
            while (row.next()) {
              overdue.add(
                  new HoldSchedulePort.DueHold(
                      institution,
                      row.getObject(1, UUID.class),
                      row.getString(3),
                      MediumTimeoutPolicy.valueOf(row.getString(4)),
                      row.getObject(2, OffsetDateTime.class).toInstant()));
            }
          }
        }
        if (overdue.size() >= max) {
          break;
        }
      }
      c.commit();
      return overdue;
    } catch (SQLException e) {
      throw new IllegalStateException("overdue holds are unavailable", e);
    }
  }
}
