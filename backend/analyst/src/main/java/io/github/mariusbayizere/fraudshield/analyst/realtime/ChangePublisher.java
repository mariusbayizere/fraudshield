package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.decision.application.port.AlertChangePublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes every later change to an alert queue entry on {@code fs.alerts.changes}, best effort
 * and never blocking (ADR 0115 point 6.2).
 *
 * <p>A writer offers ids, an enum and a number after its commit; the offer only adds them to a
 * bounded hand-off queue, and drops them (counted) when it is full. One thread drains the queue,
 * re-reads the entry's summary in one short tenant transaction of the offered institution, and
 * sends. The re-read may see a later version than the one offered; the event then carries what it
 * read, and its {@code event_id} follows that version, so two publications of one snapshot share
 * it.
 */
public final class ChangePublisher implements AlertChangePublisher, AutoCloseable {

  /** The topic. */
  public static final String TOPIC = "fs.alerts.changes";

  private static final Logger LOG = LoggerFactory.getLogger(ChangePublisher.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  // The escalator is the author of the ESCALATE that created the entry. listAlerts matches any
  // ESCALATE on the parent; the two agree because an escalated parent is closed and cannot be
  // escalated again, which the push-equals-list test checks.
  private static final String ROUTING =
      """
      SELECT e.escalation_target_role,
        (SELECT d.analyst_id FROM alert_decisions d WHERE d.decision = 'ESCALATE'
          AND d.escalated_to_alert_id = e.id ORDER BY d.created_at LIMIT 1) AS escalated_by
      FROM alert_queue_entries e WHERE e.id = ?
      """;

  /** Sends one message; the implementation must not block for longer than its own bound. */
  @FunctionalInterface
  public interface Sender {
    /**
     * Sends one message.
     *
     * @param key the record key
     * @param value the envelope
     * @param failed told, possibly on another thread, when the send fails
     */
    void send(String key, byte[] value, Runnable failed);
  }

  private record Offer(
      UUID institutionId, UUID alertId, long version, Change change, UUID actorUserId) {}

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final AlertQueries alerts;
  private final Sender sender;
  private final String producer;
  private final Clock clock;
  private final BlockingQueue<Offer> queue;
  private final Thread worker;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong published = new AtomicLong();
  private volatile boolean running = true;

  /**
   * Creates and starts the publisher.
   *
   * @param tenants the tenant runner, for the re-read
   * @param jdbc JDBC template of the same data source
   * @param alerts the summary's only builder
   * @param sender the dedicated producer
   * @param producer the envelope's producer name
   * @param capacity the hand-off queue's bound
   * @param clock clock
   */
  public ChangePublisher(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      AlertQueries alerts,
      Sender sender,
      String producer,
      int capacity,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.sender = Objects.requireNonNull(sender, "sender");
    this.producer = Objects.requireNonNull(producer, "producer");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.worker = Thread.ofPlatform().name("fs-realtime-publisher").daemon().unstarted(this::run);
    worker.start();
  }

  @Override
  public void offer(
      UUID institutionId, UUID alertId, long version, Change change, UUID actorUserId) {
    if (!running || !queue.offer(new Offer(institutionId, alertId, version, change, actorUserId))) {
      dropped.incrementAndGet();
    }
  }

  private void run() {
    while (running) {
      Offer offer;
      try {
        offer = queue.poll(200, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (offer != null) {
        publish(offer);
      }
    }
  }

  private void publish(Offer offer) {
    Optional<Message> message;
    try {
      message = tenants.inTenant(offer.institutionId(), () -> read(offer));
    } catch (RuntimeException unavailable) {
      // The next re-read of the console repairs it (ADR 0115 point 6.2).
      failed.incrementAndGet();
      LOG.debug("a change could not be re-read; dropped", unavailable);
      return;
    }
    if (message.isEmpty()) {
      failed.incrementAndGet();
      return;
    }
    try {
      sender.send(message.get().key(), message.get().value(), failed::incrementAndGet);
      published.incrementAndGet();
    } catch (RuntimeException refused) {
      failed.incrementAndGet();
      LOG.debug("a change could not be sent; dropped", refused);
    }
  }

  private record Message(String key, byte[] value) {}

  private Optional<Message> read(Offer offer) {
    Optional<Map<String, Object>> summary = alerts.summary(offer.alertId());
    if (summary.isEmpty()) {
      return Optional.empty();
    }
    List<Map<String, Object>> routing = jdbc.queryForList(ROUTING, offer.alertId());
    Map<String, Object> route = routing.isEmpty() ? Map.of() : routing.getFirst();
    return Optional.of(message(offer, summary.get(), route));
  }

  private Message message(Offer offer, Map<String, Object> summary, Map<String, Object> route) {
    Map<String, Object> alert = idsOnly(summary);
    long version = ((Number) alert.get("version")).longValue();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("alert_id", offer.alertId().toString());
    payload.put("version", version);
    payload.put("transaction_id", String.valueOf(alert.get("transaction_id")));
    payload.put("change", offer.change().name());
    payload.put("actor_user_id", text(offer.actorUserId()));
    payload.put("escalation_target_role", route.get("escalation_target_role"));
    payload.put("escalated_by_user_id", text(route.get("escalated_by")));
    payload.put("alert", alert);
    UUID eventId = eventId(offer.alertId(), version);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("event_id", eventId.toString());
    envelope.put("event_type", "alert.changed");
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", clock.instant().toString());
    // The changed row's institution, read in the tenant transaction that wrote it (point 4).
    envelope.put("institution_id", offer.institutionId().toString());
    envelope.put("producer", producer);
    envelope.put("payload", payload);
    return new Message(
        String.valueOf(alert.get("transaction_id")), JSON.writeValueAsBytes(envelope));
  }

  /**
   * The summary with staff as ids only (ADR 0012 point 4): {@code assigned_analyst} becomes {@code
   * assigned_analyst_id}, present and null before ADR 0116 assigns anyone.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> idsOnly(Map<String, Object> summary) {
    Map<String, Object> alert = new LinkedHashMap<>();
    for (Map.Entry<String, Object> field : summary.entrySet()) {
      Object value = field.getValue();
      alert.put(field.getKey(), value instanceof UUID id ? id.toString() : value);
    }
    Object assigned = alert.remove("assigned_analyst");
    alert.put(
        "assigned_analyst_id",
        assigned instanceof Map<?, ?> reference
            ? text(((Map<String, Object>) reference).get("user_id"))
            : null);
    return alert;
  }

  /**
   * The envelope's {@code event_id}: the name-based UUID of {@code alert_changed|<id>|<version>}.
   *
   * @param alertId the entry
   * @param version its version
   * @return the id
   */
  public static UUID eventId(UUID alertId, long version) {
    return UUID.nameUUIDFromBytes(
        ("alert_changed|" + alertId + "|" + version).getBytes(StandardCharsets.UTF_8));
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * Changes dropped because the hand-off queue was full.
   *
   * @return the count
   */
  public long dropped() {
    return dropped.get();
  }

  /**
   * Changes that could not be re-read or sent.
   *
   * @return the count
   */
  public long failed() {
    return failed.get();
  }

  /**
   * Changes handed to the producer.
   *
   * @return the count
   */
  public long published() {
    return published.get();
  }

  @Override
  public void close() {
    running = false;
    worker.interrupt();
  }
}
