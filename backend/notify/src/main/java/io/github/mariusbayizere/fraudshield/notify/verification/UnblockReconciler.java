package io.github.mariusbayizere.fraudshield.notify.verification;

import io.github.mariusbayizere.fraudshield.decision.application.DecisionTransitionService;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
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
 * Applies the decision transition of a customer unblock that was recorded but never reached the
 * decision path (Principal Review finding 6).
 *
 * <p>{@link VerificationService#answer} commits the customer's answer and the {@code
 * unblock_events} row before calling {@link DecisionTransitionService#customerAnswered}. If that
 * call fails, the block is recorded as lifted while the decision stays DECLINE: no webhook, no
 * label, and no second chance for the customer, whose token is now used. This leader-run sweep
 * finds those rows (V64) and applies the transition. It is safe to run repeatedly: a transition the
 * decision path already made is refused and skipped.
 */
public final class UnblockReconciler {

  /** How long after the answer the sweep waits before applying the transition itself. */
  public static final Duration GRACE = Duration.ofSeconds(10);

  private static final int BATCH = 100;
  private static final Logger LOG = LoggerFactory.getLogger(UnblockReconciler.class);

  private static final String SELECT_INSTITUTIONS =
      "SELECT institutions_with_unapplied_unblocks FROM institutions_with_unapplied_unblocks(?)";

  private static final String SELECT_UNAPPLIED =
      """
      SELECT b.transaction_id, t.transaction_timestamp FROM unblock_events u
      JOIN auto_block_events b ON b.id = u.auto_block_event_id
      JOIN v_transactions t ON t.transaction_id = b.transaction_id
      WHERE u.cause = 'CUSTOMER_VERIFICATION' AND u.unblocked_at < ?
      AND NOT EXISTS (SELECT 1 FROM decision_states s WHERE s.transaction_id = b.transaction_id
        AND s.decided_by = 'CUSTOMER_VERIFICATION')
      ORDER BY u.unblocked_at LIMIT ?
      """;

  private final DataSource dataSource;
  private final DecisionTransitionService transitions;
  private final HoldSchedulePort holds;
  private final String instanceId;
  private final Clock clock;

  /**
   * Creates the reconciler.
   *
   * @param dataSource connections as {@code fs_app}
   * @param transitions decision transitions
   * @param holds the leadership lease shared with the hold sweep
   * @param instanceId this instance
   * @param clock clock
   */
  public UnblockReconciler(
      DataSource dataSource,
      DecisionTransitionService transitions,
      HoldSchedulePort holds,
      String instanceId,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.holds = Objects.requireNonNull(holds, "holds");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Applies what is outstanding, as the leader.
   *
   * @return the transitions this sweep made
   */
  public int reconcile() {
    if (!holds.acquireLeadership(instanceId)) {
      return 0;
    }
    int applied = 0;
    for (Unapplied unblock : unapplied(clock.instant().minus(GRACE))) {
      try {
        transitions.customerAnswered(
            unblock.institution(), unblock.transaction(), true, unblock.transactionAt());
        applied++;
        LOG.warn(
            "an unblock recorded for transaction {} had not reached the decision path; applied by"
                + " the sweep",
            unblock.transaction());
      } catch (DecisionTransitionService.TransitionRefusedException resolved) {
        // The decision moved on (an analyst, a senior override): nothing to lift.
      } catch (RuntimeException failed) {
        LOG.warn("an unblock could not be applied; the sweep tries again on its next tick", failed);
        return applied;
      }
    }
    return applied;
  }

  private record Unapplied(UUID institution, UUID transaction, Instant transactionAt) {}

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
                      row.getObject(2, OffsetDateTime.class).toInstant()));
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
      throw new IllegalStateException("unapplied unblocks are unavailable", e);
    }
  }

  private static OffsetDateTime utc(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }
}
