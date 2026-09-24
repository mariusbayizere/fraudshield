package io.github.mariusbayizere.fraudshield.common.config;

import io.github.mariusbayizere.fraudshield.common.config.ConfigAuditEvent.Action;
import io.github.mariusbayizere.fraudshield.common.config.DualControlException.Refusal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asymmetric dual control for risk configuration (owner decision, ADR 0014).
 *
 * <ul>
 *   <li>Tightening: one RISK_OFFICER applies it at once; a different RISK_OFFICER must confirm it
 *       within {@link #CONFIRMATION_WINDOW}, otherwise {@link #revertOverdue()} restores the
 *       previous settings and records the revert.
 *   <li>Loosening (or mixed): one RISK_OFFICER proposes; it takes effect only when a different
 *       RISK_OFFICER approves.
 *   <li>Nobody reviews their own change, and ADMIN and analysts can neither propose nor review.
 *   <li>At most one open change per configuration kind. A tightening change is never blocked: it
 *       supersedes an open loosening proposal, and it folds an unconfirmed tightening change into
 *       itself, keeping that change's baseline, so one confirmation keeps both and one revert (by
 *       rejection or deadline) restores the baseline. A loosening change is refused while another
 *       change of its kind is open. The proposer may withdraw a loosening proposal.
 *   <li>Every applied change or revert creates a new configuration version.
 * </ul>
 *
 * <p>The clock is injected so the 24-hour revert is testable. Persistence and propagation (effect
 * within 60 seconds, FR-05-07) are the calling service's job.
 */
public final class DualControlWorkflow {

  /** How long a tightening change may stay unconfirmed before it is reverted. */
  public static final Duration CONFIRMATION_WINDOW = Duration.ofHours(24);

  private final Clock clock;
  private final Consumer<ConfigAuditEvent> audit;
  private final Map<ConfigKind, ConfigSettings> current = new EnumMap<>(ConfigKind.class);
  private final Map<ConfigKind, Long> versions = new EnumMap<>(ConfigKind.class);
  private final Map<UUID, ConfigChange> changes = new LinkedHashMap<>();

  /**
   * Creates the workflow with the settings in effect at version 1.
   *
   * @param clock time source
   * @param audit receives every audit event, in order
   * @param thresholds channel thresholds in effect
   * @param circuitBreaker circuit-breaker settings in effect
   */
  public DualControlWorkflow(
      Clock clock,
      Consumer<ConfigAuditEvent> audit,
      ChannelThresholds thresholds,
      CircuitBreakerSettings circuitBreaker) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.audit = Objects.requireNonNull(audit, "audit");
    current.put(ConfigKind.CHANNEL_THRESHOLDS, Objects.requireNonNull(thresholds, "thresholds"));
    current.put(
        ConfigKind.MCC_CIRCUIT_BREAKER, Objects.requireNonNull(circuitBreaker, "circuitBreaker"));
    versions.put(ConfigKind.CHANNEL_THRESHOLDS, 1L);
    versions.put(ConfigKind.MCC_CIRCUIT_BREAKER, 1L);
  }

  /**
   * Settings in effect.
   *
   * @param kind configuration kind
   * @return the settings
   */
  public synchronized ConfigSettings current(ConfigKind kind) {
    return current.get(kind);
  }

  /**
   * Version of the settings in effect.
   *
   * @param kind configuration kind
   * @return the version, starting at 1
   */
  public synchronized long version(ConfigKind kind) {
    return versions.get(kind);
  }

  /**
   * A change by identifier.
   *
   * @param changeId identifier
   * @return the change, if known
   */
  public synchronized Optional<ConfigChange> find(UUID changeId) {
    return Optional.ofNullable(changes.get(changeId));
  }

  /**
   * Changes still waiting for a second risk officer, oldest first.
   *
   * @return open changes
   */
  public synchronized List<ConfigChange> open() {
    return changes.values().stream().filter(ConfigChange::isOpen).toList();
  }

  /**
   * Proposes new settings. Tightening is applied immediately; loosening waits for approval.
   *
   * @param actor the proposing staff member
   * @param proposed the complete new settings
   * @param baseVersion the version the proposer saw
   * @param reason why the change is needed
   * @return the recorded change
   * @throws DualControlException if a rule refuses the proposal
   */
  public synchronized ConfigChange propose(
      Actor actor, ConfigSettings proposed, long baseVersion, String reason) {
    requireRiskOfficer(actor);
    Objects.requireNonNull(proposed, "proposed");
    Objects.requireNonNull(reason, "reason");
    revertOverdue();
    ConfigKind kind = proposed.kind();
    if (baseVersion != versions.get(kind)) {
      throw new DualControlException(
          Refusal.STALE_BASE_VERSION,
          "proposal is based on version " + baseVersion + ", current is " + versions.get(kind));
    }
    ConfigSettings current = this.current.get(kind);
    ChangeDirection direction =
        proposed
            .directionFrom(current)
            .orElseThrow(
                () -> new DualControlException(Refusal.NO_CHANGE, "settings are unchanged"));
    Instant now = clock.instant();
    Optional<ConfigChange> open = open().stream().filter(c -> c.kind() == kind).findFirst();
    ConfigSettings baseline = current;
    if (open.isPresent()) {
      ConfigChange existing = open.get();
      if (direction == ChangeDirection.LOOSENING) {
        throw new DualControlException(
            Refusal.OPEN_CHANGE_EXISTS, "another " + kind + " change is waiting for review");
      }
      if (existing.status() == ChangeStatus.APPLIED_PENDING_CONFIRMATION) {
        // Folded in: the new change's revert must also undo the unconfirmed earlier tightening.
        baseline = existing.previous();
      }
      ConfigChange superseded = existing.withStatus(ChangeStatus.SUPERSEDED);
      changes.put(existing.changeId(), superseded);
      audit.accept(new ConfigAuditEvent(Action.SUPERSEDED, superseded, actor, now));
    }
    boolean tightening = direction == ChangeDirection.TIGHTENING;
    ConfigChange change =
        new ConfigChange(
            UUID.randomUUID(),
            direction,
            tightening ? ChangeStatus.APPLIED_PENDING_CONFIRMATION : ChangeStatus.PENDING_APPROVAL,
            actor,
            now,
            reason,
            baseVersion,
            baseline,
            proposed,
            tightening ? now.plus(CONFIRMATION_WINDOW) : null,
            null,
            null,
            null,
            null,
            null,
            null);
    if (tightening) {
      change = change.applied(now);
      apply(proposed);
    }
    changes.put(change.changeId(), change);
    audit.accept(
        new ConfigAuditEvent(
            tightening ? Action.APPLIED_PENDING_CONFIRMATION : Action.PROPOSED,
            change,
            actor,
            now));
    return change;
  }

  /**
   * Approves a loosening change (applying it) or confirms a tightening change.
   *
   * @param actor the reviewing staff member
   * @param changeId the change
   * @param comment optional reviewer comment, may be null
   * @return the change after approval
   * @throws DualControlException if a rule refuses the approval
   */
  public synchronized ConfigChange approve(Actor actor, UUID changeId, String comment) {
    ConfigChange change = openChangeForReview(actor, changeId);
    Instant now = clock.instant();
    ConfigChange result;
    if (change.direction() == ChangeDirection.TIGHTENING) {
      result = change.reviewed(ChangeStatus.CONFIRMED, actor, now, comment);
      audit.accept(new ConfigAuditEvent(Action.CONFIRMED, result, actor, now));
    } else {
      result = change.reviewed(ChangeStatus.APPROVED, actor, now, comment).applied(now);
      apply(change.proposed());
      audit.accept(new ConfigAuditEvent(Action.APPROVED, result, actor, now));
    }
    changes.put(changeId, result);
    return result;
  }

  /**
   * Rejects a loosening change, or rejects and immediately reverts a tightening change.
   *
   * @param actor the reviewing staff member
   * @param changeId the change
   * @param reason why it is rejected
   * @return the change after rejection
   * @throws DualControlException if a rule refuses the rejection
   */
  public synchronized ConfigChange reject(Actor actor, UUID changeId, String reason) {
    Objects.requireNonNull(reason, "reason");
    ConfigChange change = openChangeForReview(actor, changeId);
    Instant now = clock.instant();
    ConfigChange result;
    if (change.direction() == ChangeDirection.TIGHTENING) {
      result =
          change
              .reviewed(change.status(), actor, now, reason)
              .reverted(now, ConfigChange.RevertCause.REJECTED);
      apply(change.previous());
      audit.accept(new ConfigAuditEvent(Action.REVERTED, result, actor, now));
    } else {
      result = change.reviewed(ChangeStatus.REJECTED, actor, now, reason);
      audit.accept(new ConfigAuditEvent(Action.REJECTED, result, actor, now));
    }
    changes.put(changeId, result);
    return result;
  }

  /**
   * Withdraws a loosening proposal before review. Only its proposer may withdraw it; a tightening
   * change cannot be withdrawn, because undoing it would loosen without a second risk officer.
   *
   * @param actor the proposer
   * @param changeId the change
   * @return the withdrawn change
   * @throws DualControlException if a rule refuses the withdrawal
   */
  public synchronized ConfigChange withdraw(Actor actor, UUID changeId) {
    requireRiskOfficer(actor);
    ConfigChange change = existing(changeId);
    if (!change.proposedBy().userId().equals(actor.userId())) {
      throw new DualControlException(
          Refusal.NOT_PROPOSER, "only the proposer can withdraw a change");
    }
    if (change.status() != ChangeStatus.PENDING_APPROVAL) {
      throw new DualControlException(
          Refusal.CHANGE_NOT_OPEN, "only a loosening change pending approval can be withdrawn");
    }
    Instant now = clock.instant();
    ConfigChange result = change.withStatus(ChangeStatus.WITHDRAWN);
    changes.put(changeId, result);
    audit.accept(new ConfigAuditEvent(Action.WITHDRAWN, result, actor, now));
    return result;
  }

  /**
   * Reverts every tightening change whose confirmation deadline has passed. Called by a scheduler
   * and before every other action.
   *
   * @return the changes reverted by this call
   */
  public synchronized List<ConfigChange> revertOverdue() {
    Instant now = clock.instant();
    List<ConfigChange> reverted = new ArrayList<>();
    for (ConfigChange change : List.copyOf(changes.values())) {
      if (change.status() == ChangeStatus.APPLIED_PENDING_CONFIRMATION
          && !now.isBefore(change.confirmBy())) {
        ConfigChange result = change.reverted(now, ConfigChange.RevertCause.NOT_CONFIRMED_IN_TIME);
        apply(change.previous());
        changes.put(change.changeId(), result);
        audit.accept(new ConfigAuditEvent(Action.REVERTED, result, null, now));
        reverted.add(result);
      }
    }
    return reverted;
  }

  private ConfigChange openChangeForReview(Actor actor, UUID changeId) {
    requireRiskOfficer(actor);
    revertOverdue();
    ConfigChange change = existing(changeId);
    if (!change.isOpen()) {
      throw new DualControlException(Refusal.CHANGE_NOT_OPEN, "change is not waiting for review");
    }
    if (change.proposedBy().userId().equals(actor.userId())) {
      throw new DualControlException(
          Refusal.SELF_REVIEW, "a change must be reviewed by a different risk officer");
    }
    return change;
  }

  private ConfigChange existing(UUID changeId) {
    Objects.requireNonNull(changeId, "changeId");
    ConfigChange change = changes.get(changeId);
    if (change == null) {
      throw new DualControlException(Refusal.CHANGE_NOT_FOUND, "no such change");
    }
    return change;
  }

  private static void requireRiskOfficer(Actor actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.role() != StaffRole.RISK_OFFICER) {
      throw new DualControlException(
          Refusal.ROLE_NOT_PERMITTED,
          actor.role() + " cannot propose or review risk configuration");
    }
  }

  private void apply(ConfigSettings settings) {
    current.put(settings.kind(), settings);
    versions.merge(settings.kind(), 1L, Long::sum);
  }
}
