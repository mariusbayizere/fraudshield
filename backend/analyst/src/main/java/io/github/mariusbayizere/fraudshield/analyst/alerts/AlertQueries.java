package io.github.mariusbayizere.fraudshield.analyst.alerts;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The alert reads of P1 (ADR 0110, ADR 0112), over {@code alert_queue_entries} joined to the tenant
 * views of the transaction and its score. Every query runs inside the caller's tenant transaction,
 * so row-level security limits it to the caller's institution; a row of another institution is
 * simply not found.
 */
public final class AlertQueries {

  /** The queues of {@code listAlerts}. */
  public enum Queue {
    /** HIGH and MEDIUM entries that are not escalations. */
    STANDARD,
    /** Isolation-Forest-only entries that are not escalations (D-10). */
    ANOMALY,
    /** Escalated entries the caller may see. */
    ESCALATED,
    /** The union of the three, for search (FR-04-09). */
    ALL
  }

  /** The orders of {@code listAlerts}. */
  public enum Sort {
    /** HIGH first, then fraud probability descending; anomaly score in the anomaly queue. */
    PRIORITY,
    /** Calibrated probability times the amount in RWF, descending (D-10). */
    EXPECTED_LOSS,
    /** Most recently raised first. */
    NEWEST
  }

  /**
   * The caller, as the escalated queue's filter needs it.
   *
   * @param userId the caller
   * @param role the caller's role
   */
  public record Caller(UUID userId, StaffRole role) {}

  /**
   * {@code listAlerts}' filters, already validated.
   *
   * @param queue the queue
   * @param transactionId a transaction, or null
   * @param accountToken an account token, or null
   * @param amountMin least amount in RWF, inclusive, or null
   * @param amountMax greatest amount in RWF, inclusive, or null
   * @param channels channels, empty for all
   * @param from earliest transaction time, inclusive, or null
   * @param to latest transaction time, exclusive, or null
   * @param tiers tiers, empty for all
   * @param analystId an analyst who is assigned to or decided the entry, or null
   * @param outcomes statuses, empty for entries still awaiting a decision
   * @param sort the order
   */
  public record Filter(
      Queue queue,
      UUID transactionId,
      String accountToken,
      BigDecimal amountMin,
      BigDecimal amountMax,
      List<String> channels,
      Instant from,
      Instant to,
      List<String> tiers,
      UUID analystId,
      List<String> outcomes,
      Sort sort) {

    /** Copies the lists. */
    public Filter {
      Objects.requireNonNull(queue, "queue");
      Objects.requireNonNull(sort, "sort");
      channels = List.copyOf(channels);
      tiers = List.copyOf(tiers);
      outcomes = List.copyOf(outcomes);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private static final String TIER_RANK =
      "(CASE e.tier WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END)::double precision";
  private static final String ANOMALY_SCORE =
      "CASE WHEN f.ml_unavailable_fallback THEN NULL ELSE f.anomaly_score END";

  private static final String COLUMNS =
      """
      SELECT e.id, e.version, e.transaction_id, e.tier, e.status, e.fraud_probability,
        e.expected_loss_rwf, e.review_deadline_at, e.escalated_from_alert_id, t.amount, t.currency,
        t.channel, t.merchant_name, t.transaction_timestamp, t.account_token, t.counterparty_token,
        f.shap_top5::text AS shap_top5, %s AS anomaly_score, f.ml_unavailable_fallback
      """
          .formatted(ANOMALY_SCORE);

  private static final String FROM =
      """
      FROM alert_queue_entries e
      JOIN v_transactions t ON t.transaction_id = e.transaction_id
      JOIN v_fraud_scores f ON f.id = e.fraud_score_id AND f.transaction_id = e.transaction_id
      """;

  private static final String ESCALATION_VISIBLE =
      """
      (e.escalated_from_alert_id IS NOT NULL AND (
        (CASE e.escalation_target_role WHEN 'SENIOR_ANALYST' THEN 2 WHEN 'RISK_OFFICER' THEN 3 END)
          <= ?
        OR EXISTS (SELECT 1 FROM alert_decisions d WHERE d.alert_queue_entry_id
          = e.escalated_from_alert_id AND d.decision = 'ESCALATE' AND d.analyst_id = ?)))
      """;

  private final JdbcTemplate jdbc;

  /**
   * Creates the queries.
   *
   * @param jdbc JDBC template of the application role
   */
  public AlertQueries(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * One page of {@code listAlerts}. Must run in the caller's tenant transaction.
   *
   * @param caller the caller
   * @param filter the filters
   * @param cursor the cursor, or null for the first page
   * @param limit page size
   * @return the page, in the contract's {@code AlertPage} shape
   */
  public Map<String, Object> list(Caller caller, Filter filter, String cursor, int limit) {
    List<String> keys = sortKeys(filter);
    StringBuilder sql = new StringBuilder(COLUMNS);
    for (int i = 0; i < keys.size(); i++) {
      sql.append(", ").append(keys.get(i)).append(" AS k").append(i);
    }
    sql.append(' ').append(FROM).append(" WHERE ");
    List<Object> args = new ArrayList<>();
    queue(filter.queue(), caller, sql, args);
    if (filter.outcomes().isEmpty()) {
      sql.append(" AND e.status IN ('PENDING', 'IN_REVIEW')");
    } else {
      in(sql, args, "e.status", filter.outcomes());
    }
    equal(sql, args, "e.transaction_id", filter.transactionId());
    equal(sql, args, "t.account_token", filter.accountToken());
    if (filter.amountMin() != null) {
      sql.append(" AND t.amount_rwf >= ?");
      args.add(filter.amountMin());
    }
    if (filter.amountMax() != null) {
      sql.append(" AND t.amount_rwf <= ?");
      args.add(filter.amountMax());
    }
    in(sql, args, "t.channel", filter.channels());
    if (filter.from() != null) {
      sql.append(" AND t.transaction_timestamp >= ?");
      args.add(Timestamp.from(filter.from()));
    }
    if (filter.to() != null) {
      sql.append(" AND t.transaction_timestamp < ?");
      args.add(Timestamp.from(filter.to()));
    }
    in(sql, args, "e.tier", filter.tiers());
    if (filter.analystId() != null) {
      sql.append(
          " AND (e.assigned_analyst_id = ? OR EXISTS (SELECT 1 FROM alert_decisions d"
              + " WHERE d.alert_queue_entry_id = e.id AND d.analyst_id = ?))");
      args.add(filter.analystId());
      args.add(filter.analystId());
    }
    String order = order(filter);
    Position after = Position.decode(cursor, order, keys.size());
    if (after != null) {
      sql.append(" AND (").append(String.join(", ", keys)).append(", e.id) > (");
      for (Double key : after.keys()) {
        sql.append("?, ");
        args.add(key);
      }
      sql.append("?)");
      args.add(after.id());
    }
    sql.append(" ORDER BY ").append(String.join(", ", keys)).append(", e.id LIMIT ?");
    args.add(limit + 1);
    List<Row> rows = jdbc.query(sql.toString(), (row, i) -> row(row, keys.size()), args.toArray());
    List<Row> items = rows.size() > limit ? rows.subList(0, limit) : rows;
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", items.stream().map(Row::summary).toList());
    page.put(
        "next_cursor",
        rows.size() > limit
            ? new Position(order, items.getLast().keys(), items.getLast().id()).encode()
            : null);
    return page;
  }

  /**
   * {@code getAlert}. Must run in the caller's tenant transaction.
   *
   * @param alertId the entry
   * @return the contract's {@code AlertDetail}, or empty
   */
  public Optional<Map<String, Object>> detail(UUID alertId) {
    List<Row> rows =
        jdbc.query(COLUMNS + FROM + " WHERE e.id = ?", (row, i) -> row(row, 0), alertId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Row row = rows.getFirst();
    Map<String, Object> detail = row.summary();
    detail.put("account_token", row.accountToken());
    detail.put("counterparty_token", row.counterpartyToken());
    detail.put("summary_template_key", Summaries.key(row.tier(), row.fallback(), row.reasons()));
    detail.put(
        "summary_parameters",
        Summaries.parameters(row.fraudProbability(), row.reasons(), row.anomalyScore()));
    detail.put("decisions", decisions(alertId));
    return Optional.of(detail);
  }

  /**
   * {@code getAlertExplanation}: all 44 contributions, ranked by absolute value, with the base
   * value and margin the scorer returned. Must run in the caller's tenant transaction.
   *
   * @param alertId the entry
   * @return the contract's {@code Explanation}, or empty when the entry is unknown or its score has
   *     no SHAP (below 0.60, the rule-based fallback, or scored before V80)
   */
  public Optional<Map<String, Object>> explanation(UUID alertId) {
    List<Map<String, Object>> found =
        jdbc.query(
            """
            SELECT e.transaction_id, e.tier, f.ensemble_score, f.ml_unavailable_fallback,
              f.shap_all::text AS shap_all, f.shap_top5::text AS shap_top5, f.shap_base_value,
              f.final_margin
            """
                + FROM
                + " WHERE e.id = ?",
            (row, i) -> explanation(row),
            alertId);
    return found.isEmpty() || found.getFirst() == null
        ? Optional.empty()
        : Optional.of(found.getFirst());
  }

  private static Map<String, Object> explanation(ResultSet row) throws SQLException {
    String all = row.getString("shap_all");
    double base = row.getDouble("shap_base_value");
    boolean hasBase = !row.wasNull();
    double margin = row.getDouble("final_margin");
    boolean hasMargin = !row.wasNull();
    if (all == null || !hasBase || !hasMargin) {
      return null;
    }
    List<Map<String, Object>> contributions = new ArrayList<>(Views.rows(all));
    contributions.sort(
        Comparator.comparingDouble((Map<String, Object> c) -> -Math.abs(shap(c)))
            .thenComparing(c -> String.valueOf(c.get("feature"))));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("transaction_id", row.getObject("transaction_id", UUID.class));
    view.put("base_value", base);
    view.put("final_margin", margin);
    view.put("calibrated_probability", row.getDouble("ensemble_score"));
    view.put("contributions", contributions);
    List<Map<String, Object>> reasons = top3(Views.rows(row.getString("shap_top5")));
    String tier = row.getString("tier");
    boolean fallback = row.getBoolean("ml_unavailable_fallback");
    view.put("summary_template_key", Summaries.key(tier, fallback, reasons));
    view.put(
        "summary_parameters", Summaries.parameters(row.getDouble("ensemble_score"), reasons, null));
    return view;
  }

  private List<Map<String, Object>> decisions(UUID alertId) {
    return jdbc.query(
        """
        SELECT s.id, s.decision, s.analyst_comment, s.created_at, s.override_of_decision_id,
          s.state, u.id AS user_id, u.first_name, u.last_name, u.role
        FROM v_alert_decision_state s
        LEFT JOIN users u ON u.id = s.analyst_id
        WHERE s.alert_queue_entry_id = ?
        ORDER BY s.created_at, s.id
        """,
        (row, i) -> decision(row),
        alertId);
  }

  private static Map<String, Object> decision(ResultSet row) throws SQLException {
    Map<String, Object> view = new LinkedHashMap<>();
    String decision = row.getString("decision");
    view.put("decision_id", row.getObject("id", UUID.class));
    view.put("decision", decision);
    UUID user = row.getObject("user_id", UUID.class);
    if (user == null) {
      view.put("analyst", null);
    } else {
      Map<String, Object> analyst = new LinkedHashMap<>();
      analyst.put("user_id", user);
      analyst.put("first_name", row.getString("first_name"));
      analyst.put("last_name", row.getString("last_name"));
      analyst.put("role", row.getString("role"));
      view.put("analyst", analyst);
    }
    view.put("comment", row.getString("analyst_comment"));
    view.put("decided_at", Views.timestamp(Views.instant(row, "created_at")));
    // Only an analyst's decision has a grace to undo it in (D-44); every other kind is final when
    // written, whatever the view derives from the commit table (ADR 0112).
    boolean graced = "CONFIRM_FRAUD".equals(decision) || "MARK_LEGITIMATE".equals(decision);
    view.put("state", graced ? row.getString("state") : "COMMITTED");
    view.put("override_of_decision_id", row.getObject("override_of_decision_id", UUID.class));
    view.put("false_positive_confirmed", null);
    return view;
  }

  private static void queue(Queue queue, Caller caller, StringBuilder sql, List<Object> args) {
    switch (queue) {
      case STANDARD ->
          sql.append("e.tier IN ('HIGH', 'MEDIUM') AND e.escalated_from_alert_id IS NULL");
      case ANOMALY -> sql.append("e.tier = 'ANOMALY' AND e.escalated_from_alert_id IS NULL");
      case ESCALATED -> {
        sql.append(ESCALATION_VISIBLE);
        args.add(Views.rank(caller.role()));
        args.add(caller.userId());
      }
      case ALL -> {
        sql.append("(e.escalated_from_alert_id IS NULL OR ").append(ESCALATION_VISIBLE).append(')');
        args.add(Views.rank(caller.role()));
        args.add(caller.userId());
      }
    }
  }

  /** The order's keys, each ascending, so that the keyset comparison is one row comparison. */
  private static List<String> sortKeys(Filter filter) {
    return switch (filter.sort()) {
      case PRIORITY ->
          filter.queue() == Queue.ANOMALY
              ? List.of("-COALESCE(" + ANOMALY_SCORE + ", -1)")
              : List.of(TIER_RANK, "-e.fraud_probability");
      case EXPECTED_LOSS -> List.of("-COALESCE(e.expected_loss_rwf::double precision, -1)");
      case NEWEST -> List.of("-(extract(epoch FROM e.created_at) * 1000000)::double precision");
    };
  }

  private static String order(Filter filter) {
    return filter.sort().name() + ":" + (filter.sort() == Sort.PRIORITY ? filter.queue() : "ANY");
  }

  private static void equal(StringBuilder sql, List<Object> args, String column, Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append(" = ?");
      args.add(value);
    }
  }

  private static void in(StringBuilder sql, List<Object> args, String column, List<String> values) {
    if (values.isEmpty()) {
      return;
    }
    sql.append(" AND ").append(column).append(" IN (");
    for (int i = 0; i < values.size(); i++) {
      sql.append(i == 0 ? "?" : ", ?");
      args.add(values.get(i));
    }
    sql.append(')');
  }

  private static Row row(ResultSet row, int keyCount) throws SQLException {
    List<Double> keys = new ArrayList<>(keyCount);
    for (int i = 0; i < keyCount; i++) {
      keys.add(row.getDouble("k" + i));
    }
    double anomaly = row.getDouble("anomaly_score");
    Double anomalyScore = row.wasNull() ? null : anomaly;
    BigDecimal expectedLoss = row.getBigDecimal("expected_loss_rwf");
    return new Row(
        row.getObject("id", UUID.class),
        row.getLong("version"),
        row.getObject("transaction_id", UUID.class),
        row.getString("tier"),
        row.getString("status"),
        row.getDouble("fraud_probability"),
        expectedLoss,
        row.getBigDecimal("amount"),
        row.getString("currency").strip(),
        row.getString("channel"),
        row.getString("merchant_name"),
        Views.instant(row, "transaction_timestamp"),
        top3(Views.rows(row.getString("shap_top5"))),
        Views.instant(row, "review_deadline_at"),
        row.getObject("escalated_from_alert_id", UUID.class),
        anomalyScore,
        row.getBoolean("ml_unavailable_fallback"),
        row.getString("account_token"),
        row.getString("counterparty_token"),
        List.copyOf(keys));
  }

  private static List<Map<String, Object>> top3(List<Map<String, Object>> top5) {
    return top5.size() > 3 ? List.copyOf(top5.subList(0, 3)) : top5;
  }

  private static double shap(Map<String, Object> contribution) {
    return ((Number) contribution.get("shap")).doubleValue();
  }

  private record Row(
      UUID id,
      long version,
      UUID transactionId,
      String tier,
      String status,
      double fraudProbability,
      BigDecimal expectedLoss,
      BigDecimal amount,
      String currency,
      String channel,
      String merchant,
      Instant transactionTimestamp,
      List<Map<String, Object>> reasons,
      Instant reviewDeadline,
      UUID escalatedFrom,
      Double anomalyScore,
      boolean fallback,
      String accountToken,
      String counterpartyToken,
      List<Double> keys) {

    Map<String, Object> summary() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("alert_id", id);
      view.put("version", version);
      view.put("transaction_id", transactionId);
      view.put("tier", tier);
      view.put("status", status);
      view.put("fraud_probability", fraudProbability);
      if (expectedLoss != null) {
        view.put("expected_loss_rwf", Views.decimal(expectedLoss));
      }
      Map<String, Object> money = new LinkedHashMap<>();
      money.put("amount", Views.decimal(amount));
      money.put("currency", currency);
      view.put("amount", money);
      view.put("channel", channel);
      // Always null until the PII vault offers a masked-only lookup (ADR 0110 point 3, PB-157).
      view.put("masked_account", null);
      view.put("merchant", merchant);
      view.put("transaction_timestamp", Views.timestamp(transactionTimestamp));
      view.put("top_reasons", reasons);
      view.put("review_deadline_at", Views.timestamp(reviewDeadline));
      view.put("escalated_from_alert_id", escalatedFrom);
      view.put("anomaly_score", anomalyScore);
      return view;
    }
  }

  /** Where the next page starts: the order it belongs to, its keys, and the last id. */
  private record Position(String order, List<Double> keys, UUID id) {

    String encode() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("o", order);
      value.put("k", keys);
      value.put("i", id.toString());
      return ENCODER.encodeToString(
          JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String cursor, String order, int keyCount) {
      if (cursor == null || cursor.isEmpty()) {
        return null;
      }
      try {
        JsonNode node = JSON.readTree(new String(DECODER.decode(cursor), StandardCharsets.UTF_8));
        JsonNode keys = node.get("k");
        if (!order.equals(node.get("o").asString()) || keys.size() != keyCount) {
          throw new IllegalArgumentException("cursor of another order");
        }
        List<Double> values = new ArrayList<>(keyCount);
        for (int i = 0; i < keyCount; i++) {
          values.add(keys.get(i).asDouble());
        }
        return new Position(order, List.copyOf(values), UUID.fromString(node.get("i").asString()));
      } catch (RuntimeException e) {
        throw ProblemException.validation(
            "cursor", "invalid_format", "Not a cursor of this queue and order");
      }
    }
  }
}
