package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records an owner request's stored events (decision-owner contract §4.6). The writer calls {@link
 * #record} after its commit; every instance runs {@link #recordPending} every P to complete what a
 * writer left, so a writer that crashed after its commit is always completed. A record is the
 * stored {@code event_body} through the spool, then the recording row, then the Redis state cache,
 * best effort: a failed cache write is counted and never retried, because no read trusts Redis
 * while PostgreSQL answers (ADR 0113).
 */
public final class RequestRecorder {

  /** How old an unrecorded request must be before the recorder takes it from its writer. */
  public static final Duration AGE = Duration.ofSeconds(30);

  private static final int BATCH = 100;
  private static final Logger LOG = LoggerFactory.getLogger(RequestRecorder.class);

  private static final String SELECT_INSTITUTIONS =
      "SELECT institutions_with_unrecorded_requests FROM institutions_with_unrecorded_requests(?)";

  private static final String SELECT_UNRECORDED =
      """
      SELECT r.event_id, r.event_body::text FROM transaction_requests r
      WHERE r.outcome IN ('APPLIED', 'LABEL_ONLY') AND r.decided_at < ?
      AND NOT EXISTS (SELECT 1 FROM transaction_request_recordings g WHERE g.event_id = r.event_id)
      ORDER BY r.decided_at LIMIT ?
      """;

  private static final String INSERT_RECORDING =
      """
      INSERT INTO transaction_request_recordings (event_id, institution_id) VALUES (?, ?) ON
      CONFLICT DO NOTHING
      """;

  private final DataSource dataSource;
  private final OwnerTransactions transactions;
  private final EventRecorder spool;
  private final DecisionStatePort cache;
  private final AtomicLong cacheFailures = new AtomicLong();

  /**
   * Creates the recorder.
   *
   * @param dataSource connections as {@code fs_app}, for the lister
   * @param transactions the runner, for the recording row
   * @param spool the durable spool
   * @param cache the Redis state cache, called directly, not through the resilient fallback
   */
  public RequestRecorder(
      DataSource dataSource,
      OwnerTransactions transactions,
      EventRecorder spool,
      DecisionStatePort cache) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.spool = Objects.requireNonNull(spool, "spool");
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /**
   * Records a request's events after the writer's commit. A failure leaves the request to {@link
   * #recordPending}.
   *
   * @param institutionId institution
   * @param result the owner's result
   * @return whether the events were recorded now
   */
  public boolean record(UUID institutionId, DecisionOwner.Result result) {
    if (result.events().isEmpty() || result.replay()) {
      return false;
    }
    try {
      record(institutionId, OwnerIds.event(result.requestId()), result.events());
      return true;
    } catch (RuntimeException | SQLException failed) {
      LOG.warn("a request's events were not recorded; the recorder records them", failed);
      return false;
    }
  }

  private void record(UUID institutionId, UUID eventId, List<DecisionEvent> events)
      throws SQLException {
    spool.record(events);
    transactions.inTenant(
        institutionId,
        c -> {
          try (PreparedStatement s = c.prepareStatement(INSERT_RECORDING)) {
            s.setObject(1, eventId);
            s.setObject(2, institutionId);
            return s.executeUpdate();
          }
        });
    for (DecisionEvent event : events) {
      if (event instanceof DecisionEvent.DecisionChanged changed) {
        try {
          cache.save(changed.state());
        } catch (RuntimeException failed) {
          cacheFailures.incrementAndGet();
        }
      }
    }
  }

  /**
   * Records every request older than {@link #AGE} that has no recording row (contract §4.6).
   * Recording is idempotent, so concurrent runs on several instances are safe.
   *
   * @param now this instance's clock; the lister compares with the database's time
   * @return requests recorded
   */
  public int recordPending(Instant now) {
    int recorded = 0;
    try {
      for (UUID institution : institutions(now.minus(AGE))) {
        for (Pending pending : pending(institution, now.minus(AGE))) {
          try {
            record(
                institution,
                pending.eventId(),
                FactCodec.decode(pending.body().getBytes(StandardCharsets.UTF_8)));
            recorded++;
          } catch (RuntimeException | SQLException failed) {
            LOG.warn("an unrecorded request could not be recorded; tried again next run", failed);
            return recorded;
          }
        }
      }
    } catch (SQLException e) {
      LOG.warn("unrecorded requests are unavailable", e);
    }
    return recorded;
  }

  /**
   * Redis state writes that failed and were not retried.
   *
   * @return count
   */
  public long cacheFailures() {
    return cacheFailures.get();
  }

  private record Pending(UUID eventId, String body) {}

  private List<UUID> institutions(Instant before) throws SQLException {
    List<UUID> institutions = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement(SELECT_INSTITUTIONS)) {
      s.setObject(1, OffsetDateTime.ofInstant(before, ZoneOffset.UTC));
      try (ResultSet rows = s.executeQuery()) {
        while (rows.next()) {
          institutions.add(rows.getObject(1, UUID.class));
        }
      }
    }
    return institutions;
  }

  private List<Pending> pending(UUID institution, Instant before) throws SQLException {
    return transactions.inTenant(
        institution,
        c -> {
          List<Pending> pending = new ArrayList<>();
          try (PreparedStatement s = c.prepareStatement(SELECT_UNRECORDED)) {
            s.setObject(1, OffsetDateTime.ofInstant(before, ZoneOffset.UTC));
            s.setInt(2, BATCH);
            try (ResultSet rows = s.executeQuery()) {
              while (rows.next()) {
                pending.add(new Pending(rows.getObject(1, UUID.class), rows.getString(2)));
              }
            }
          }
          return pending;
        });
  }
}
