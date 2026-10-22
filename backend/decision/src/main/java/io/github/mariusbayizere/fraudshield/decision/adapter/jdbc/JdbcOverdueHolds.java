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
 * Overdue holds from the owner rows and {@code decision_states} across institutions (decision-owner
 * contract §6.3). Runs as {@code fs_app}: V62's {@code institutions_with_overdue_holds} names the
 * institutions (ids only), then one query per institution, because row-level security scopes every
 * read to one tenant.
 */
public final class JdbcOverdueHolds implements OverdueHoldsPort {

  private static final String SELECT_INSTITUTIONS =
      "SELECT institutions_with_overdue_holds FROM institutions_with_overdue_holds(?)";

  /**
   * The owner's view of overdue holds (decision-owner contract §6.3): owners still HOLD, not
   * claimed by an analyst, not contested, past their deadline (which an escalation may have
   * extended); and sequence-1 HOLDs that no owner has taken yet. The basis is the first decision
   * either stands on.
   */
  private static final String SELECT_OVERDUE =
      """
      SELECT o.transaction_id, o.deadline, t.channel,
      coalesce(o.timeout_policy, 'RELEASE_WITH_TIMEOUT_LABEL'), o.first_event_id, NULL::timestamptz
      FROM decision_owners o JOIN v_transactions t ON t.transaction_id = o.transaction_id
      WHERE o.decision = 'HOLD' AND o.claim <> 'ANALYST' AND o.contested_since IS NULL
      AND o.deadline < ?
      UNION ALL
      SELECT h.transaction_id, h.review_deadline_at, t.channel,
      coalesce(h.timeout_policy, (SELECT r.medium_timeout_policy FROM risk_thresholds r
        WHERE r.channel = t.channel AND r.version = (SELECT max(v.version)
          FROM risk_threshold_versions v WHERE v.effective_at <= h.decided_at)),
        'RELEASE_WITH_TIMEOUT_LABEL'), h.event_id, h.decided_at
      FROM decision_states h JOIN v_transactions t ON t.transaction_id = h.transaction_id
      WHERE h.decision = 'HOLD' AND h.decision_sequence = 1 AND h.review_deadline_at < ?
      AND NOT EXISTS (SELECT 1 FROM decision_owners o WHERE o.transaction_id = h.transaction_id)
      ORDER BY 2 LIMIT ?
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
          s.setObject(2, Tenant.utc(before));
          s.setInt(3, max - overdue.size());
          try (ResultSet row = s.executeQuery()) {
            while (row.next()) {
              OffsetDateTime decidedAt = row.getObject(6, OffsetDateTime.class);
              overdue.add(
                  new HoldSchedulePort.DueHold(
                      institution,
                      row.getObject(1, UUID.class),
                      row.getString(3),
                      MediumTimeoutPolicy.valueOf(row.getString(4)),
                      row.getObject(2, OffsetDateTime.class).toInstant(),
                      row.getObject(5, UUID.class),
                      decidedAt == null ? null : decidedAt.toInstant(),
                      List.of(),
                      null));
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
