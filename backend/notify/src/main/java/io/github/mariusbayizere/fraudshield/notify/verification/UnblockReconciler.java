package io.github.mariusbayizere.fraudshield.notify.verification;

import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerRequest;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerTransactions;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submits customer answers that have no owner request yet (decision-owner contract §6.5): answers
 * deferred while a human reviewed the payment or while it was contested, and answers M6 recorded
 * but never applied before the cutover (V81 marks only those whose effect is complete). A "no"
 * whose label was never recorded is submitted too, so it has a recorder of last resort.
 *
 * <p>It lists only answers whose owner can act on them now, so a contested or claimed transaction
 * never holds a batch's head (review 8, finding 8). Each answer is one owner request, applied at
 * most once whatever the number of runs: its request id is the verification's.
 */
public final class UnblockReconciler {

  /** How long after the answer the sweep waits before submitting it itself. */
  public static final Duration GRACE = Duration.ofSeconds(10);

  private static final int BATCH = 100;
  private static final Logger LOG = LoggerFactory.getLogger(UnblockReconciler.class);

  private static final String SELECT_INSTITUTIONS =
      "SELECT institutions_with_unapplied_unblocks FROM institutions_with_unapplied_unblocks(?)";

  private static final String SELECT_UNAPPLIED =
      """
      SELECT r.verification_id, r.answer, b.transaction_id FROM customer_verification_responses r
      JOIN customer_verifications v ON v.id = r.verification_id
      JOIN auto_block_events b ON b.id = v.auto_block_event_id
      WHERE r.responded_at < ?
      AND NOT EXISTS (SELECT 1 FROM transaction_requests q
        WHERE q.request_id = fs_name_uuid('verification|' || r.verification_id::text))
      AND NOT EXISTS (SELECT 1 FROM decision_owners o WHERE o.transaction_id = b.transaction_id
        AND (o.contested_since IS NOT NULL OR o.claim = 'ANALYST'
          OR (o.claim = 'ESCALATED' AND o.deadline > clock_timestamp())))
      ORDER BY r.responded_at LIMIT ?
      """;

  private final DataSource dataSource;
  private final OwnerTransactions transactions;
  private final DecisionOwner owner;
  private final RequestRecorder recorder;
  private final HoldSchedulePort holds;
  private final String instanceId;
  private final Clock clock;

  /**
   * Creates the reconciler.
   *
   * @param dataSource connections as {@code fs_app}
   * @param transactions the owner's runner
   * @param owner the transaction's owner
   * @param recorder records an outcome after the commit
   * @param holds the leadership lease shared with the hold sweep
   * @param instanceId this instance
   * @param clock clock
   */
  public UnblockReconciler(
      DataSource dataSource,
      OwnerTransactions transactions,
      DecisionOwner owner,
      RequestRecorder recorder,
      HoldSchedulePort holds,
      String instanceId,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.holds = Objects.requireNonNull(holds, "holds");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Submits what is outstanding, as the leader.
   *
   * @return the answers whose request was made by this sweep
   */
  public int reconcile() {
    if (!holds.acquireLeadership(instanceId)) {
      return 0;
    }
    int submitted = 0;
    for (Unapplied answer : unapplied(clock.instant().minus(GRACE))) {
      try {
        DecisionOwner.Result result =
            transactions.inTenant(
                answer.institution(),
                c ->
                    owner.submit(
                        c,
                        answer.institution(),
                        answer.transaction(),
                        new OwnerRequest.Customer(answer.verification(), answer.wasMe())));
        recorder.record(answer.institution(), result);
        submitted++;
      } catch (SQLException | RuntimeException failed) {
        LOG.warn("a customer answer could not be submitted; the sweep tries again next tick", failed);
        return submitted;
      }
    }
    return submitted;
  }

  private record Unapplied(UUID institution, UUID verification, boolean wasMe, UUID transaction) {}

  private List<Unapplied> unapplied(Instant before) {
    List<Unapplied> unapplied = new ArrayList<>();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      List<UUID> institutions = new ArrayList<>();
      try (PreparedStatement s = c.prepareStatement(SELECT_INSTITUTIONS)) {
        s.setObject(1, utc(before));
        try (ResultSet rows = s.executeQuery()) {
          while (rows.next()) {
            institutions.add(rows.getObject(1, UUID.class));
          }
        }
      }
      for (UUID institution : institutions) {
        try (PreparedStatement tenant =
            c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
          tenant.setString(1, institution.toString());
          tenant.execute();
        }
        try (PreparedStatement s = c.prepareStatement(SELECT_UNAPPLIED)) {
          s.setObject(1, utc(before));
          s.setInt(2, BATCH - unapplied.size());
          try (ResultSet row = s.executeQuery()) {
            while (row.next()) {
              unapplied.add(
                  new Unapplied(
                      institution,
                      row.getObject(1, UUID.class),
                      "WAS_ME".equals(row.getString(2)),
                      row.getObject(3, UUID.class)));
            }
          }
        }
        if (unapplied.size() >= BATCH) {
          break;
        }
      }
      c.commit();
      return unapplied;
    } catch (SQLException e) {
      throw new IllegalStateException("unapplied answers are unavailable", e);
    }
  }

  private static OffsetDateTime utc(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }
}
