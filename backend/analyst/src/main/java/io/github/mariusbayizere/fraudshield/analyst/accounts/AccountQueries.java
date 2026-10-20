package io.github.mariusbayizere.fraudshield.analyst.accounts;

import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The account's context (FR-04-06 to FR-04-08): its history, its behavioural fingerprint and its
 * counterparty network, read through the tenant views. Every method must run in the caller's tenant
 * transaction.
 */
public final class AccountQueries {

  private static final Duration WINDOW = Duration.ofDays(90);
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private static final String HISTORY =
      """
      SELECT t.transaction_id, t.amount, t.currency, t.channel, t.transaction_timestamp,
        f.ensemble_score, f.risk_tier, d.decision
      FROM v_transactions t
      JOIN LATERAL (SELECT s.ensemble_score, s.risk_tier FROM v_fraud_scores s
        WHERE s.transaction_id = t.transaction_id ORDER BY s.scored_at DESC LIMIT 1) f ON true
      LEFT JOIN LATERAL (SELECT ds.decision FROM decision_states ds
        WHERE ds.transaction_id = t.transaction_id ORDER BY ds.decision_sequence DESC LIMIT 1) d
        ON true
      WHERE t.account_token = ?
      """;

  private static final String PROFILE_COLUMNS =
      """
      SELECT transaction_timestamp, amount_rwf, currency, channel, counterparty_token, device_token,
        counterparty_country FROM v_transactions
      """;

  private final JdbcTemplate jdbc;
  private final BehaviourProfiles profiles;

  /**
   * Creates the queries.
   *
   * @param jdbc JDBC template of the application role
   * @param profiles the profile computation
   */
  public AccountQueries(JdbcTemplate jdbc, BehaviourProfiles profiles) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
  }

  /**
   * One page of the account's history, most recent first.
   *
   * @param account the account token
   * @param cursor the cursor, or null for the first page
   * @param limit page size, at most 30
   * @return the contract's {@code AccountTransactionPage}, or empty when the account has no
   *     transaction at all
   */
  public Optional<Map<String, Object>> history(String account, String cursor, int limit) {
    StringBuilder sql = new StringBuilder(HISTORY);
    List<Object> args = new ArrayList<>();
    args.add(account);
    Position after = Position.decode(cursor);
    if (after != null) {
      sql.append(" AND (t.transaction_timestamp, t.transaction_id) < (?, ?)");
      args.add(Timestamp.from(after.at()));
      args.add(after.id());
    }
    sql.append(" ORDER BY t.transaction_timestamp DESC, t.transaction_id DESC LIMIT ?");
    args.add(limit + 1);
    List<Item> rows = jdbc.query(sql.toString(), (row, i) -> item(row), args.toArray());
    if (rows.isEmpty() && after == null) {
      return Optional.empty();
    }
    List<Item> items = rows.size() > limit ? rows.subList(0, limit) : rows;
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", items.stream().map(Item::view).toList());
    page.put(
        "next_cursor",
        rows.size() > limit
            ? new Position(items.getLast().at(), items.getLast().id()).encode()
            : null);
    return Optional.of(page);
  }

  /**
   * The behavioural fingerprint against one of the account's transactions.
   *
   * @param account the account token
   * @param transactionId the compared transaction
   * @return the contract's {@code BehaviourProfile}, or empty when the transaction is not the
   *     account's
   */
  public Optional<Map<String, Object>> profile(String account, UUID transactionId) {
    List<BehaviourProfiles.Txn> compared =
        jdbc.query(
            PROFILE_COLUMNS + " WHERE account_token = ? AND transaction_id = ?",
            (row, i) -> txn(row),
            account,
            transactionId);
    if (compared.isEmpty()) {
      return Optional.empty();
    }
    BehaviourProfiles.Txn current = compared.getFirst();
    List<BehaviourProfiles.Txn> window =
        jdbc.query(
            PROFILE_COLUMNS
                + " WHERE account_token = ? AND transaction_timestamp >= ?"
                + " AND transaction_timestamp < ? ORDER BY transaction_timestamp, transaction_id",
            (row, i) -> txn(row),
            account,
            Timestamp.from(current.at().minus(WINDOW)),
            Timestamp.from(current.at()));
    String country =
        jdbc.queryForObject(
            "SELECT country FROM institutions WHERE id = current_institution()", String.class);
    return Optional.of(profiles.profile(current, window, country));
  }

  /**
   * The counterparty network over the last 90 days, at most 50 nodes.
   *
   * @param account the account token
   * @return the contract's {@code AccountNetwork}, or empty when the account has no transaction
   */
  public Optional<Map<String, Object>> network(String account) {
    boolean known =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM v_transactions WHERE account_token = ?)",
                Boolean.class,
                account));
    if (!known) {
      return Optional.empty();
    }
    Timestamp from = jdbc.queryForObject("SELECT now() - interval '90 days'", Timestamp.class);
    List<Networks.Link> first =
        jdbc.query(
            """
            SELECT account_token, counterparty_token FROM v_transactions
            WHERE transaction_timestamp >= ? AND (account_token = ? OR counterparty_token = ?)
            """,
            (row, i) -> link(row),
            from,
            account,
            account);
    Map<String, Integer> degrees = Networks.firstDegree(account, first);
    Set<String> neighbours = Networks.degreeOne(degrees);
    if (!neighbours.isEmpty() && degrees.size() < Networks.MAX_NODES) {
      Networks.secondDegree(degrees, links(from, neighbours, false));
    }
    Set<String> nodes = degrees.keySet();
    List<Networks.Link> between = links(from, nodes, true);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("nodes", Networks.nodeItems(degrees, confirmedFraud(from, nodes)));
    view.put("edges", Networks.edges(nodes, between));
    return Optional.of(view);
  }

  /** Transactions of the window with a node at one end ({@code both}: at both ends). */
  private List<Networks.Link> links(Timestamp from, Set<String> tokens, boolean both) {
    String list = placeholders(tokens.size());
    String sql =
        "SELECT account_token, counterparty_token FROM v_transactions WHERE"
            + " transaction_timestamp >= ? AND (account_token IN ("
            + list
            + (both ? ") AND " : ") OR ")
            + "counterparty_token IN ("
            + list
            + "))";
    List<Object> args = new ArrayList<>();
    args.add(from);
    args.addAll(tokens);
    args.addAll(tokens);
    return jdbc.query(sql, (row, i) -> link(row), args.toArray());
  }

  /**
   * The nodes that sent a transaction in the window whose current label is FRAUD: the latest by
   * availability, ties by the greater label id.
   */
  private Set<String> confirmedFraud(Timestamp from, Set<String> tokens) {
    String sql =
        """
        SELECT DISTINCT t.account_token FROM v_transactions t
        JOIN LATERAL (SELECT l.label FROM label_events l WHERE l.transaction_id = t.transaction_id
          ORDER BY l.label_available_at DESC, l.id DESC LIMIT 1) cur ON true
        WHERE t.transaction_timestamp >= ? AND cur.label = 'FRAUD' AND t.account_token IN (
        """
            + placeholders(tokens.size())
            + ")";
    List<Object> args = new ArrayList<>();
    args.add(from);
    args.addAll(tokens);
    return new HashSet<>(
        jdbc.query(sql, (row, i) -> row.getString("account_token"), args.toArray()));
  }

  private static String placeholders(int count) {
    return String.join(", ", Collections.nCopies(count, "?"));
  }

  private static Networks.Link link(ResultSet row) throws SQLException {
    return new Networks.Link(row.getString("account_token"), row.getString("counterparty_token"));
  }

  private static BehaviourProfiles.Txn txn(ResultSet row) throws SQLException {
    String country = row.getString("counterparty_country");
    return new BehaviourProfiles.Txn(
        Views.instant(row, "transaction_timestamp"),
        row.getBigDecimal("amount_rwf"),
        row.getString("currency").strip(),
        row.getString("channel"),
        row.getString("counterparty_token"),
        row.getString("device_token"),
        country == null ? null : country.strip());
  }

  private static Item item(ResultSet row) throws SQLException {
    return new Item(
        row.getObject("transaction_id", UUID.class),
        Views.instant(row, "transaction_timestamp"),
        Views.decimal(row.getBigDecimal("amount")),
        row.getString("currency").strip(),
        row.getString("channel"),
        row.getDouble("ensemble_score"),
        row.getString("risk_tier"),
        outcome(row.getString("decision")));
  }

  /** A transaction's outcome from its latest decision state; no state yet reads as pending. */
  private static String outcome(String decision) {
    if (decision == null) {
      return "PENDING";
    }
    return switch (decision) {
      case "APPROVE", "TIMEOUT_RELEASE" -> "APPROVED";
      case "DECLINE" -> "BLOCKED";
      default -> "PENDING";
    };
  }

  private record Item(
      UUID id,
      Instant at,
      String amount,
      String currency,
      String channel,
      double riskScore,
      String riskTier,
      String outcome) {

    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("transaction_id", id);
      Map<String, Object> money = new LinkedHashMap<>();
      money.put("amount", amount);
      money.put("currency", currency);
      view.put("amount", money);
      view.put("channel", channel);
      view.put("transaction_timestamp", Views.timestamp(at));
      view.put("risk_score", riskScore);
      view.put("risk_tier", riskTier);
      view.put("outcome", outcome);
      return view;
    }
  }

  /** A page's end: base64url of {@code <epoch micros>|<transaction id>}. */
  private record Position(Instant at, UUID id) {

    String encode() {
      long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
      return ENCODER.encodeToString((micros + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String cursor) {
      if (cursor == null || cursor.isEmpty()) {
        return null;
      }
      try {
        String text = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        int bar = text.indexOf('|');
        long micros = Long.parseLong(text.substring(0, bar));
        Instant at =
            Instant.ofEpochSecond(
                Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
        return new Position(at, UUID.fromString(text.substring(bar + 1)));
      } catch (RuntimeException e) {
        throw ProblemException.validation("cursor", "invalid_format", "Not a valid cursor");
      }
    }
  }
}
