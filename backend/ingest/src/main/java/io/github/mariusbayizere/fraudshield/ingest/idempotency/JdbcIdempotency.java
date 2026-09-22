package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import io.github.mariusbayizere.fraudshield.decision.application.IngestDecision;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.ingest.application.DecisionResponses;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Idempotency from PostgreSQL while Redis is down (C.4), with degraded latency. It never writes:
 * {@code transaction_ids} is append-only, so a claim recorded for a decision that then failed could
 * never be released. A known transaction id with a different fingerprint is a conflict; with the
 * same fingerprint the persisted decision is rendered again, byte for byte, or, if it is not
 * persisted yet, reported in flight so the client retries. Two first submissions racing while Redis
 * is down can both be decided; the PostgreSQL writer keeps the first (unique keys).
 */
public final class JdbcIdempotency implements IdempotencyStore {

  private static final String LOOKUP =
      """
      SELECT i.request_fingerprint, d.decision, d.reason_codes, d.review_deadline_at, s.id,
      s.risk_tier, s.model_version, s.ml_unavailable_fallback, t.processing_duration_ms
      FROM transaction_ids i
      LEFT JOIN decision_states d ON d.transaction_id = i.transaction_id
        AND d.decision_sequence = 1
      LEFT JOIN v_fraud_scores s ON s.transaction_id = i.transaction_id
      LEFT JOIN v_transactions t ON t.transaction_id = i.transaction_id
      WHERE i.transaction_id = ? ORDER BY s.scored_at LIMIT 1
      """;

  private final DataSource dataSource;

  /**
   * Creates the store.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JdbcIdempotency(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public java.util.Optional<byte[]> decided(UUID institutionId, UUID transactionId) {
    return switch (lookup(institutionId, transactionId, null)) {
      case Replay replay -> java.util.Optional.of(replay.response());
      default -> java.util.Optional.empty();
    };
  }

  @Override
  public Claim claim(UUID institutionId, UUID transactionId, byte[] fingerprint) {
    return lookup(institutionId, transactionId, fingerprint);
  }

  private Claim lookup(UUID institutionId, UUID transactionId, byte[] fingerprint) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try (PreparedStatement tenant =
          c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
        tenant.setString(1, institutionId.toString());
        tenant.execute();
      }
      try (PreparedStatement s = c.prepareStatement(LOOKUP)) {
        s.setObject(1, transactionId);
        try (ResultSet row = s.executeQuery()) {
          Claim claim =
              !row.next()
                  ? new Claimed()
                  : !Arrays.equals(row.getBytes(1), fingerprint)
                      ? new Conflict()
                      : row.getString(2) == null || row.getObject(5) == null
                          ? new InFlight()
                          : new Replay(DecisionResponses.render(decision(transactionId, row)));
          c.commit();
          return claim;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("idempotency is unavailable", e);
    }
  }

  private static IngestDecision decision(UUID transactionId, ResultSet row) throws SQLException {
    OffsetDateTime deadline = row.getObject(4, OffsetDateTime.class);
    return new IngestDecision(
        transactionId,
        Decision.valueOf(row.getString(2)),
        RiskTier.valueOf(row.getString(6)),
        List.of((String[]) row.getArray(3).getArray()),
        row.getObject(5, UUID.class),
        row.getString(7),
        row.getLong(9),
        deadline == null ? null : deadline.toInstant(),
        row.getBoolean(8));
  }

  @Override
  public void complete(UUID institutionId, UUID transactionId, byte[] response) {
    // The decision is persisted by the spool writer; nothing else to store.
  }

  @Override
  public void release(UUID institutionId, UUID transactionId) {
    // Nothing was written by the claim.
  }
}
