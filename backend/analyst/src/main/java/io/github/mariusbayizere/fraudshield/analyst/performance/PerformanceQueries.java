package io.github.mariusbayizere.fraudshield.analyst.performance;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The figures of ADR 0117 over the decision tables. Every query runs inside the caller's tenant
 * transaction, so row-level security limits it to the caller's institution; the explicit {@code
 * institution_id = current_institution()} predicates only let the planner use V83's indexes. The
 * SQL is constants only; every value is a bind parameter.
 *
 * <p>A review is a CONFIRM_FRAUD or MARK_LEGITIMATE decision by a user that was not undone (point
 * 2); a decision still in its undo window counts. Its time to decision is V83's {@code
 * decision_duration_seconds}; averages are over HIGH and MEDIUM entries only, and a NULL (a
 * decision made before V83) is left out of an average, never counted as 0 (point 3).
 */
public final class PerformanceQueries {

  private static final String NOT_UNDONE =
      " AND NOT EXISTS (SELECT 1 FROM alert_decision_undos u WHERE u.decision_id = d.id)";

  private static final String REVIEWS =
      """
      SELECT count(*) AS reviews,
        avg(d.decision_duration_seconds) FILTER (WHERE e.tier IN ('HIGH', 'MEDIUM')) AS average
      FROM alert_decisions d
      JOIN alert_queue_entries e ON e.id = d.alert_queue_entry_id
      WHERE d.institution_id = current_institution()
        AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') AND d.analyst_id IS NOT NULL
        AND d.created_at >= ? AND d.created_at < ?
      """
          + NOT_UNDONE;

  private static final String MY_REVIEWS = REVIEWS + " AND d.analyst_id = ?";

  private static final String DAILY =
      """
      SELECT (d.created_at AT TIME ZONE ?)::date AS day, count(*) AS reviews,
        avg(d.decision_duration_seconds) FILTER (WHERE e.tier IN ('HIGH', 'MEDIUM')) AS average
      FROM alert_decisions d
      JOIN alert_queue_entries e ON e.id = d.alert_queue_entry_id
      WHERE d.institution_id = current_institution()
        AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') AND d.analyst_id = ?
        AND d.created_at >= ? AND d.created_at < ?
      """
          + NOT_UNDONE
          + " GROUP BY 1";

  private static final String OPEN =
      """
      SELECT count(*) FILTER (WHERE e.tier = 'HIGH') AS high_open,
        count(*) FILTER (WHERE e.tier = 'MEDIUM') AS medium_open
      FROM alert_queue_entries e
      WHERE e.status IN ('PENDING', 'IN_REVIEW') AND e.tier IN ('HIGH', 'MEDIUM')
        AND e.escalated_from_alert_id IS NULL
      """;

  private static final String AUTO_BLOCKED =
      """
      SELECT count(*) FROM auto_block_events b
      WHERE b.institution_id = current_institution() AND b.blocked_at >= ? AND b.blocked_at < ?
      """;

  /**
   * Point 4: the first DECLINE state is today, the latest state is still DECLINE (read the
   * contract's way, from v_decision_states, so a transaction without an owner row is not dropped),
   * the latest label is FRAUD, and the transaction is no older than the lookback.
   */
  private static final String FRAUD_PREVENTED =
      """
      SELECT coalesce(sum(t.amount_rwf), 0)
      FROM v_decision_states s
      JOIN v_transactions t ON t.transaction_id = s.transaction_id AND t.transaction_timestamp >= ?
      WHERE s.institution_id = current_institution() AND s.decision = 'DECLINE'
        AND s.decided_at >= ? AND s.decided_at < ?
        AND NOT EXISTS (SELECT 1 FROM v_decision_states p
          WHERE p.institution_id = s.institution_id AND p.transaction_id = s.transaction_id
            AND p.decision = 'DECLINE' AND p.decision_sequence < s.decision_sequence)
        AND (SELECT l.decision FROM v_decision_states l
          WHERE l.institution_id = s.institution_id AND l.transaction_id = s.transaction_id
          ORDER BY l.decision_sequence DESC LIMIT 1) = 'DECLINE'
        AND (SELECT b.label FROM label_events b
          WHERE b.institution_id = s.institution_id AND b.transaction_id = s.transaction_id
          ORDER BY b.label_available_at DESC, b.id DESC LIMIT 1) = 'FRAUD'
      """;

  private static final String BACKLOG =
      """
      SELECT count(*) FROM alert_queue_entries e
      WHERE e.assigned_analyst_id = ? AND e.status IN ('PENDING', 'IN_REVIEW')
      """;

  /**
   * Point 5: reviews of auto-blocked transactions whose customer answered after the decision. The
   * answer's row records what the customer said, whatever the owner did with it (decision-owner
   * contract §6.5).
   */
  private static final String AGREEMENT =
      """
      SELECT d.analyst_id, count(*) AS verified,
        count(*) FILTER (WHERE (d.decision = 'CONFIRM_FRAUD' AND r.answer = 'NOT_ME')
          OR (d.decision = 'MARK_LEGITIMATE' AND r.answer = 'WAS_ME')) AS agreeing,
        count(*) FILTER (WHERE r.answer = 'WAS_ME') AS verified_legitimate,
        count(*) FILTER (WHERE r.answer = 'WAS_ME' AND d.decision = 'CONFIRM_FRAUD')
          AS false_positives
      FROM alert_decisions d
      JOIN alert_queue_entries e ON e.id = d.alert_queue_entry_id
      JOIN auto_block_events a ON a.institution_id = e.institution_id
        AND a.transaction_id = e.transaction_id
      JOIN customer_verifications v ON v.auto_block_event_id = a.id
      JOIN customer_verification_responses r ON r.verification_id = v.id
      WHERE d.institution_id = current_institution()
        AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') AND d.analyst_id IS NOT NULL
        AND d.created_at >= ? AND d.created_at < ?
        AND r.responded_at > d.created_at
      """
          + NOT_UNDONE;

  private static final String MY_AGREEMENT =
      AGREEMENT + " AND d.analyst_id = ? GROUP BY d.analyst_id";
  private static final String TEAM_AGREEMENT = AGREEMENT + " GROUP BY d.analyst_id";

  /** Point 5: reviews a risk officer overrode with a different decision. */
  private static final String OVERTURNED =
      """
      SELECT d.analyst_id, count(DISTINCT d.id) AS overturned
      FROM alert_decisions d
      JOIN alert_decisions o ON o.override_of_decision_id = d.id AND o.decision = 'SENIOR_OVERRIDE'
        AND o.override_decision <> d.decision
      WHERE d.institution_id = current_institution()
        AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') AND d.analyst_id IS NOT NULL
        AND d.created_at >= ? AND d.created_at < ?
      """
          + NOT_UNDONE;

  private static final String MY_OVERTURNED =
      OVERTURNED + " AND d.analyst_id = ? GROUP BY d.analyst_id";
  private static final String TEAM_OVERTURNED = OVERTURNED + " GROUP BY d.analyst_id";

  /** Point 7: each person's work in the range. */
  private static final String TEAM_ACTIVITY =
      """
      SELECT d.analyst_id,
        count(*) FILTER (WHERE d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE')) AS reviews,
        count(DISTINCT date_trunc('hour', d.created_at AT TIME ZONE ?))
          FILTER (WHERE d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE')) AS active_hours,
        avg(d.decision_duration_seconds) FILTER (WHERE d.decision IN ('CONFIRM_FRAUD',
          'MARK_LEGITIMATE') AND e.tier IN ('HIGH', 'MEDIUM')) AS average,
        count(*) FILTER (WHERE d.decision = 'ESCALATE') AS escalations,
        count(*) FILTER (WHERE d.decision = 'SENIOR_OVERRIDE') AS overrides
      FROM alert_decisions d
      JOIN alert_queue_entries e ON e.id = d.alert_queue_entry_id
      WHERE d.institution_id = current_institution() AND d.analyst_id IS NOT NULL
        AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE', 'ESCALATE', 'SENIOR_OVERRIDE')
        AND d.created_at >= ? AND d.created_at < ?
      """
          + NOT_UNDONE
          + " GROUP BY d.analyst_id";

  /**
   * Point 7: the rows. Users of the roles the caller may see (the filter rule: risk officers only
   * for a risk officer) who are ACTIVE or LOCKED now, or did work in the range; one more than the
   * limit, so that a larger answer is refused rather than truncated.
   */
  private static final String TEAM_MEMBERS =
      """
      SELECT u.id, u.first_name, u.last_name, u.role FROM users u
      WHERE (u.role IN ('ANALYST', 'SENIOR_ANALYST') OR (u.role = 'RISK_OFFICER' AND ?))
        AND (u.status IN ('ACTIVE', 'LOCKED') OR EXISTS (SELECT 1 FROM alert_decisions d
          WHERE d.institution_id = u.institution_id AND d.analyst_id = u.id
            AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE', 'ESCALATE', 'SENIOR_OVERRIDE')
            AND d.created_at >= ? AND d.created_at < ?
      """
          + NOT_UNDONE
          + "))"
          + " ORDER BY u.id LIMIT ?";

  private final JdbcTemplate jdbc;

  /**
   * Creates the queries.
   *
   * @param jdbc JDBC template of the application role
   */
  public PerformanceQueries(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * The caller's institution's zone (V83).
   *
   * @return the zone
   */
  public ZoneId zone() {
    return ZoneId.of(
        jdbc.queryForObject(
            "SELECT time_zone FROM institutions WHERE id = current_institution()", String.class));
  }

  /**
   * {@code getDashboardStats}' figures from the database.
   *
   * @param today the institution's today
   * @return the figures, without {@code analysts_online}
   */
  public Map<String, Object> dashboard(InstitutionDay today) {
    Map<String, Object> stats = new LinkedHashMap<>();
    jdbc.query(
        OPEN,
        row -> {
          stats.put("high_open", row.getLong("high_open"));
          stats.put("medium_open", row.getLong("medium_open"));
        });
    stats.put("auto_blocked_today", count(AUTO_BLOCKED, ts(today.start()), ts(today.end())));
    Reviews reviews = reviews(REVIEWS, ts(today.start()), ts(today.end()));
    stats.put("reviewed_today", reviews.count());
    BigDecimal prevented =
        jdbc.queryForObject(
            FRAUD_PREVENTED,
            BigDecimal.class,
            ts(today.start().minus(Duration.ofDays(Figures.DECLINE_LOOKBACK_DAYS))),
            ts(today.start()),
            ts(today.end()));
    Map<String, Object> money = new LinkedHashMap<>();
    money.put("amount", Views.decimal(prevented == null ? BigDecimal.ZERO : prevented));
    money.put("currency", "RWF");
    stats.put("fraud_prevented_today", money);
    stats.put("average_seconds_to_decision", reviews.average());
    return stats;
  }

  /**
   * {@code getMyPerformance}'s figures.
   *
   * @param userId the caller
   * @param today the institution's today
   * @return the figures, in the contract's order but for the day fields
   */
  public Map<String, Object> mine(UUID userId, InstitutionDay today) {
    Map<String, Object> mine = new LinkedHashMap<>();
    Reviews reviews = reviews(MY_REVIEWS, ts(today.start()), ts(today.end()), userId);
    mine.put("reviewed_today", reviews.count());
    mine.put("average_seconds_to_decision", reviews.average());
    mine.put("backlog", count(BACKLOG, userId));
    InstitutionDay first = today.plusDays(1 - Figures.WINDOW_DAYS);
    Agreement agreement =
        agreements(MY_AGREEMENT, ts(first.start()), ts(today.end()), userId)
            .getOrDefault(userId, Agreement.NONE);
    agreement.into(mine);
    mine.put(
        "overturned_decisions",
        overturned(MY_OVERTURNED, ts(first.start()), ts(today.end()), userId)
            .getOrDefault(userId, 0L));
    mine.put("daily", daily(userId, first, today));
    return mine;
  }

  /**
   * {@code getTeamPerformance}'s rows.
   *
   * @param range the range
   * @param zone the institution's zone
   * @param withRiskOfficers whether the caller may see risk officers' rows
   * @return the rows, at most {@link Figures#MAX_ROWS} plus one
   */
  public List<Map<String, Object>> team(
      Figures.Range range, ZoneId zone, boolean withRiskOfficers) {
    final InstitutionDay from = new InstitutionDay(zone, range.from());
    final InstitutionDay to = new InstitutionDay(zone, range.to());
    final Timestamp start = ts(from.start());
    final Timestamp end = ts(to.end());
    List<Map<String, Object>> rows =
        jdbc.query(
            TEAM_MEMBERS,
            (row, i) -> member(row),
            withRiskOfficers,
            start,
            end,
            Figures.MAX_ROWS + 1);
    if (rows.size() > Figures.MAX_ROWS) {
      return rows;
    }
    final Map<UUID, Activity> activity = new HashMap<>();
    jdbc.query(
        TEAM_ACTIVITY,
        row -> {
          activity.put(
              row.getObject("analyst_id", UUID.class),
              new Activity(
                  row.getLong("reviews"),
                  row.getLong("active_hours"),
                  average(row),
                  row.getLong("escalations"),
                  row.getLong("overrides")));
        },
        zone.getId(),
        start,
        end);
    final Map<UUID, Long> overturned = overturned(TEAM_OVERTURNED, start, end);
    final InstitutionDay first = to.plusDays(1 - Figures.WINDOW_DAYS);
    final Map<UUID, Agreement> agreements = agreements(TEAM_AGREEMENT, ts(first.start()), end);
    for (Map<String, Object> row : rows) {
      UUID user = (UUID) row.get("user_id");
      Activity work = activity.getOrDefault(user, Activity.NONE);
      row.put("reviews", work.reviews());
      row.put("active_hours", work.activeHours());
      row.put("reviews_per_active_hour", Figures.perActiveHour(work.reviews(), work.activeHours()));
      row.put("average_seconds_to_decision", work.average());
      // ADR 0116's alert_assignments (V84) is not in this schema yet, so no entry has an
      // assignment by another user: null for everyone until assignment is in use (point 3).
      row.put("average_seconds_from_assignment", null);
      row.put("escalations", work.escalations());
      row.put("overturned_decisions", overturned.getOrDefault(user, 0L));
      row.put("overrides_made", "RISK_OFFICER".equals(row.get("role")) ? work.overrides() : null);
      agreements.getOrDefault(user, Agreement.NONE).into(row);
    }
    return rows;
  }

  private List<Map<String, Object>> daily(UUID userId, InstitutionDay first, InstitutionDay today) {
    Map<LocalDate, Map<String, Object>> found = new HashMap<>();
    jdbc.query(
        DAILY,
        row -> {
          LocalDate day = row.getObject("day", LocalDate.class);
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("date", day.toString());
          item.put("reviews", row.getLong("reviews"));
          item.put("average_seconds_to_decision", average(row));
          found.put(day, item);
        },
        today.zone().getId(),
        userId,
        ts(first.start()),
        ts(today.end()));
    List<Map<String, Object>> days = new ArrayList<>();
    for (InstitutionDay day = first; !day.date().isAfter(today.date()); day = day.plusDays(1)) {
      Map<String, Object> item = found.get(day.date());
      if (item == null) {
        item = new LinkedHashMap<>();
        item.put("date", day.date().toString());
        item.put("reviews", 0L);
        item.put("average_seconds_to_decision", null);
      }
      days.add(item);
    }
    return days;
  }

  private static Map<String, Object> member(ResultSet row) throws SQLException {
    Map<String, Object> member = new LinkedHashMap<>();
    member.put("user_id", row.getObject("id", UUID.class));
    member.put("first_name", row.getString("first_name"));
    member.put("last_name", row.getString("last_name"));
    member.put("role", row.getString("role"));
    return member;
  }

  private Reviews reviews(String sql, Object... args) {
    return jdbc.queryForObject(
        sql, (row, i) -> new Reviews(row.getLong("reviews"), average(row)), args);
  }

  private Map<UUID, Agreement> agreements(String sql, Object... args) {
    Map<UUID, Agreement> found = new HashMap<>();
    jdbc.query(
        sql,
        row -> {
          found.put(
              row.getObject("analyst_id", UUID.class),
              new Agreement(
                  row.getLong("verified"),
                  row.getLong("agreeing"),
                  row.getLong("verified_legitimate"),
                  row.getLong("false_positives")));
        },
        args);
    return found;
  }

  private Map<UUID, Long> overturned(String sql, Object... args) {
    Map<UUID, Long> found = new HashMap<>();
    jdbc.query(
        sql,
        row -> {
          found.put(row.getObject("analyst_id", UUID.class), row.getLong("overturned"));
        },
        args);
    return found;
  }

  private long count(String sql, Object... args) {
    Long count = jdbc.queryForObject(sql, Long.class, args);
    return count == null ? 0 : count;
  }

  private static Double average(ResultSet row) throws SQLException {
    BigDecimal average = row.getBigDecimal("average");
    return average == null ? null : average.doubleValue();
  }

  private static Timestamp ts(Instant at) {
    return Timestamp.from(at);
  }

  private record Reviews(long count, Double average) {}

  private record Activity(
      long reviews, long activeHours, Double average, long escalations, long overrides) {
    static final Activity NONE = new Activity(0, 0, null, 0, 0);
  }

  /** The verified counts of point 5, and the rates they give. */
  private record Agreement(
      long verified, long agreeing, long verifiedLegitimate, long falsePositives) {
    static final Agreement NONE = new Agreement(0, 0, 0, 0);

    void into(Map<String, Object> figures) {
      figures.put("customer_agreement_rate", Figures.rate(agreeing, verified));
      figures.put("verified_decisions", verified);
      figures.put("false_positive_rate", Figures.rate(falsePositives, verifiedLegitimate));
      figures.put("verified_legitimate_decisions", verifiedLegitimate);
    }
  }
}
