package io.github.mariusbayizere.fraudshield.notify.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers {@code decision.final} webhooks (D-14) from V63 {@code webhook_deliveries}.
 *
 * <p>Sequence 1 is never sent (the integrator has it in the ingest response). A newer state of a
 * transaction supersedes a pending older one, and an older state arriving after a newer one was
 * queued or delivered is recorded as superseded and never sent. Each attempt is signed at send time
 * with every active secret; a 2xx within 10 seconds delivers it, anything else is retried per
 * {@link RetryPolicy} and dead-lettered after 24 hours.
 */
public final class WebhookDispatcher {

  private static final String LATEST =
      """
      SELECT max(decision_sequence) FROM webhook_deliveries
      WHERE transaction_id = ? AND state IN ('PENDING', 'DELIVERED')
      """;
  private static final String SUPERSEDE =
      """
      UPDATE webhook_deliveries SET state = 'SUPERSEDED', next_attempt_at = NULL
      WHERE transaction_id = ? AND state = 'PENDING'
      """;
  private static final String INSERT =
      """
      INSERT INTO webhook_deliveries (institution_id, transaction_id, event_id, decision_sequence,
      body, state, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
      """;
  private static final String SERIALISE = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
  private static final String DUE =
      "SELECT delivery_id, institution_id FROM" + " webhook_deliveries_due(?, ?)";
  private static final String LOCK =
      """
      SELECT body, attempts, first_attempt_at FROM webhook_deliveries
      WHERE id = ? AND state = 'PENDING' AND next_attempt_at <= ? FOR UPDATE SKIP LOCKED
      """;
  private static final String RECORD =
      """
      UPDATE webhook_deliveries SET state = ?, attempts = ?, first_attempt_at = ?,
      next_attempt_at = ?, last_status_code = ?, last_error = ? WHERE id = ?
      """;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final DataSource dataSource;
  private final WebhookEndpoints endpoints;
  private final WebhookTransport transport;
  private final RetryPolicy retries;
  private final Clock clock;
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong deadLettered = new AtomicLong();

  /**
   * Creates the dispatcher.
   *
   * @param dataSource connections as {@code fs_app}
   * @param endpoints institution endpoints and secrets
   * @param transport HTTP transport
   * @param retries retry policy
   * @param clock clock
   */
  public WebhookDispatcher(
      DataSource dataSource,
      WebhookEndpoints endpoints,
      WebhookTransport transport,
      RetryPolicy retries,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.retries = Objects.requireNonNull(retries, "retries");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Webhooks delivered since start.
   *
   * @return count
   */
  public long delivered() {
    return delivered.get();
  }

  /**
   * Webhooks dead-lettered since start.
   *
   * @return count
   */
  public long deadLettered() {
    return deadLettered.get();
  }

  /**
   * Queues a decision state for delivery.
   *
   * @param institutionId the owning institution, from the event envelope
   * @param finalDecision the {@code FinalDecision} JSON, compact, as it will be sent
   * @return true when queued as pending; false when it is sequence 1 or already superseded
   * @throws SQLException when the database is unavailable
   */
  public boolean enqueue(UUID institutionId, byte[] finalDecision) throws SQLException {
    return enqueue(institutionId, JSON.readTree(finalDecision));
  }

  /**
   * Queues a decision state for delivery, from a consumed envelope's payload.
   *
   * @param institutionId the owning institution
   * @param state the {@code FinalDecision} payload
   * @return true when queued as pending
   * @throws SQLException when the database is unavailable
   */
  public boolean enqueue(UUID institutionId, JsonNode state) throws SQLException {
    byte[] finalDecision = JSON.writeValueAsBytes(state);
    int sequence = state.get("decision_sequence").asInt();
    if (sequence < 2) {
      return false;
    }
    UUID transaction = UUID.fromString(state.get("transaction_id").asString());
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        tenant(c, institutionId);
        try (PreparedStatement lock = c.prepareStatement(SERIALISE)) {
          lock.setString(1, "webhook:" + transaction);
          lock.execute();
        }
        int latest = 0;
        try (PreparedStatement s = c.prepareStatement(LATEST)) {
          s.setObject(1, transaction);
          try (ResultSet row = s.executeQuery()) {
            row.next();
            latest = row.getInt(1);
          }
        }
        boolean pending = sequence > latest;
        if (pending) {
          try (PreparedStatement s = c.prepareStatement(SUPERSEDE)) {
            s.setObject(1, transaction);
            s.executeUpdate();
          }
        }
        try (PreparedStatement s = c.prepareStatement(INSERT)) {
          s.setObject(1, institutionId);
          s.setObject(2, transaction);
          s.setObject(3, UUID.fromString(state.get("event_id").asString()));
          s.setInt(4, sequence);
          s.setString(5, new String(finalDecision, StandardCharsets.UTF_8));
          s.setString(6, pending ? "PENDING" : "SUPERSEDED");
          s.setObject(7, pending ? utc(clock.instant()) : null);
          pending = s.executeUpdate() == 1 && pending;
        }
        c.commit();
        return pending;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  /**
   * Attempts every due delivery once.
   *
   * @param limit most deliveries this call attempts
   * @return deliveries attempted
   * @throws SQLException when the database is unavailable
   */
  public int deliverDue(int limit) throws SQLException {
    List<UUID[]> due = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement(DUE)) {
      s.setObject(1, utc(clock.instant()));
      s.setInt(2, limit);
      try (ResultSet rows = s.executeQuery()) {
        while (rows.next()) {
          due.add(new UUID[] {rows.getObject(1, UUID.class), rows.getObject(2, UUID.class)});
        }
      }
    }
    int attempted = 0;
    for (UUID[] delivery : due) {
      if (attempt(delivery[0], delivery[1])) {
        attempted++;
      }
    }
    return attempted;
  }

  private boolean attempt(UUID id, UUID institutionId) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        tenant(c, institutionId);
        Instant now = clock.instant();
        String body;
        int attempts;
        Instant first;
        try (PreparedStatement s = c.prepareStatement(LOCK)) {
          s.setObject(1, id);
          s.setObject(2, utc(now));
          try (ResultSet row = s.executeQuery()) {
            if (!row.next()) {
              c.rollback();
              return false;
            }
            body = row.getString(1);
            attempts = row.getInt(2);
            OffsetDateTime firstAt = row.getObject(3, OffsetDateTime.class);
            first = firstAt == null ? now : firstAt.toInstant();
          }
        }
        Outcome outcome = send(institutionId, body.getBytes(StandardCharsets.UTF_8), now);
        attempts++;
        String state;
        Instant next = null;
        if (outcome.status() >= 200 && outcome.status() < 300) {
          state = "DELIVERED";
          delivered.incrementAndGet();
        } else if (outcome.permanent() || retries.exhausted(first, now)) {
          state = "DEAD_LETTERED";
          deadLettered.incrementAndGet();
        } else {
          state = "PENDING";
          next = now.plus(retries.delay(attempts));
        }
        try (PreparedStatement s = c.prepareStatement(RECORD)) {
          s.setString(1, state);
          s.setInt(2, attempts);
          s.setObject(3, utc(first));
          s.setObject(4, utc(next));
          if (outcome.status() > 0) {
            s.setInt(5, outcome.status());
          } else {
            s.setNull(5, java.sql.Types.INTEGER);
          }
          s.setString(6, outcome.error());
          s.setObject(7, id);
          s.executeUpdate();
        }
        c.commit();
        return true;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private record Outcome(int status, String error, boolean permanent) {}

  private Outcome send(UUID institutionId, byte[] body, Instant now) {
    Optional<WebhookEndpoints.Endpoint> endpoint = endpoints.find(institutionId);
    if (endpoint.isEmpty()) {
      return new Outcome(0, "no webhook endpoint is registered", true);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("X-FraudShield-Event", "decision.final");
    headers.put("X-FraudShield-Delivery", UUID.randomUUID().toString());
    headers.put(
        "X-FraudShield-Signature",
        WebhookSignatures.header(endpoint.get().secrets(), now.getEpochSecond(), body));
    try {
      int status = transport.post(endpoint.get().url(), headers, body);
      return new Outcome(status, status >= 200 && status < 300 ? null : "HTTP " + status, false);
    } catch (IOException e) {
      return new Outcome(0, "no response: " + e.getClass().getSimpleName(), false);
    }
  }

  private static void tenant(Connection c, UUID institutionId) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
      s.setString(1, institutionId.toString());
      s.execute();
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
