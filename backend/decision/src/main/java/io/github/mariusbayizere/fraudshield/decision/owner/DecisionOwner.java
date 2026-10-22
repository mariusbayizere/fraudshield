package io.github.mariusbayizere.fraudshield.decision.owner;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Closing;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict.Outcome;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner of a transaction's decision (decision-owner contract §4.3). Every requester calls it
 * inside its own runner transaction, on that transaction's connection, which it never opens or
 * commits itself. It locks the owner row, creating it by §4.2 if absent, looks the request up,
 * evaluates {@link OwnerRules}, and writes the outcome. Recording the outcome through the spool is
 * the caller's, after its commit (§4.6).
 */
public final class DecisionOwner {

  private static final String SELECT_OWNER =
      """
      SELECT first_event_id, first_decision, first_deadline, timeout_policy, sequence, decision,
      decided_by, claim, claim_decision_id, deadline, prior_claim, prior_deadline,
      contested_since, resolution_id FROM decision_owners WHERE institution_id = ? AND
      transaction_id = ? FOR UPDATE
      """;

  private static final String FIRST_DECISION_LOCK =
      "SELECT pg_advisory_xact_lock(hashtextextended('first_decision:' || ? || ':' || ?, 0))";

  private static final String SELECT_FIRST =
      """
      SELECT event_id, decision, review_deadline_at, timeout_policy FROM decision_states WHERE
      institution_id = ? AND transaction_id = ? AND decision_sequence = 1
      """;

  private static final String INSERT_OWNER =
      """
      INSERT INTO decision_owners (institution_id, transaction_id, first_event_id, first_decision,
      first_deadline, timeout_policy, sequence, decision, decided_by, deadline) VALUES (?, ?, ?, ?,
      ?, ?, 1, ?, 'MODEL', ?) ON CONFLICT (institution_id, transaction_id) DO NOTHING
      """;

  private static final String UPDATE_OWNER =
      """
      UPDATE decision_owners SET first_event_id = ?, sequence = ?, decision = ?, decided_by = ?,
      claim = ?, claim_decision_id = ?, deadline = ?, prior_claim = ?, prior_deadline = ?,
      contested_since = ?, resolution_id = ?, version = version + 1 WHERE institution_id = ? AND
      transaction_id = ?
      """;

  private static final String SELECT_REQUEST =
      "SELECT outcome, reason, event_id FROM transaction_requests WHERE request_id = ?";

  private static final String INSERT_REQUEST =
      """
      INSERT INTO transaction_requests (institution_id, transaction_id, request_id, kind, outcome,
      reason, sequence, decision, supersedes_decision, reason_codes, event_id, decided_by,
      decided_at, event_body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
      ON CONFLICT (request_id) DO NOTHING
      """;

  private static final String SELECT_FACTS =
      """
      SELECT
      (SELECT f.outcome = 'FAILED' FROM alert_decision_effects f WHERE f.decision_id = ?),
      b.id, EXISTS (SELECT 1 FROM unblock_events u WHERE u.auto_block_event_id = b.id)
      FROM (SELECT 1) AS one
      LEFT JOIN auto_block_events b ON b.transaction_id = ?
      """;

  private static final String SELECT_TRANSACTION_TIME =
      "SELECT transaction_timestamp FROM transaction_ids WHERE transaction_id = ?";

  private static final String LOCK_OPEN_ENTRIES =
      """
      SELECT id FROM alert_queue_entries WHERE transaction_id = ? AND status IN ('PENDING',
      'IN_REVIEW') ORDER BY id FOR UPDATE
      """;

  private static final String CLOSE_ON_TIMEOUT =
      """
      UPDATE alert_queue_entries SET status = ?, version = version + 1 WHERE transaction_id = ? AND
      (status IN ('PENDING', 'IN_REVIEW') OR (status IN ('CONFIRMED_FRAUD', 'MARKED_LEGITIMATE')
      AND id IN (SELECT d.alert_queue_entry_id FROM alert_decisions d
        JOIN decision_conflict_resolutions z ON z.decision_id = d.id AND z.kind = 'RELEASE_FAILED'
        JOIN transaction_requests q ON q.request_id = fs_name_uuid('release_failed|' || z.id)
          AND q.outcome = 'RELEASED')))
      RETURNING id, created_at
      """;

  private static final String CLOSE_ON_RESOLVE =
      """
      UPDATE alert_queue_entries SET status = 'OVERRIDDEN', resolution_id = ?, version = version
      + 1 WHERE transaction_id = ? AND status IN ('PENDING', 'IN_REVIEW') RETURNING id, created_at
      """;

  private static final String INSERT_AUTO_DECISION =
      """
      INSERT INTO alert_decisions (institution_id, alert_queue_entry_id, decision, idempotency_key)
      VALUES (?, ?, ?, ?) ON CONFLICT (institution_id, idempotency_key) DO NOTHING
      """;

  private static final String INSERT_UNBLOCK =
      """
      INSERT INTO unblock_events (institution_id, auto_block_event_id, cause, actor_user_id,
      verification_id, unblocked_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (auto_block_event_id) DO
      NOTHING
      """;

  private static final String INSERT_CONFLICT =
      """
      INSERT INTO decision_conflicts (institution_id, transaction_id, event_id, decision,
      review_deadline_at, detected_by) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
      """;

  private static final String CONTEST =
      """
      UPDATE decision_owners SET contested_since = ?, version = version + 1 WHERE institution_id = ?
      AND transaction_id = ?
      """;

  /**
   * What a request did.
   *
   * @param outcome the outcome; for a replay, the stored one (PRE_V81 reads as REFUSED)
   * @param refusal why it was refused or deferred, or null
   * @param reschedule for a TIMEOUT refused as early, the deadline to reschedule at
   * @param requestId the request's id, or null
   * @param replay whether the request had already been made, and nothing was written now
   * @param state the new state, for APPLIED (null for a replay)
   * @param events the events to record after the commit (empty for a replay: the recorder has
   *     them)
   * @param closedEntries entries the request closed
   * @param clearedClaim the analyst decision whose claim a resolution cleared, or null
   */
  public record Result(
      Outcome outcome,
      Verdict.Refusal refusal,
      Instant reschedule,
      UUID requestId,
      boolean replay,
      DecisionState state,
      List<DecisionEvent> events,
      List<UUID> closedEntries,
      UUID clearedClaim) {
    /** Copies the lists. */
    public Result {
      events = List.copyOf(events);
      closedEntries = List.copyOf(closedEntries);
    }
  }

  /**
   * Submits a request. Runs in the caller's transaction, which must have the tenant set.
   *
   * @param c the caller's transaction-bound connection
   * @param institutionId institution
   * @param transactionId transaction
   * @param request the request
   * @return what it did
   * @throws SQLException on a database error, which the caller's runner handles
   */
  public Result submit(
      Connection c, UUID institutionId, UUID transactionId, OwnerRequest request)
      throws SQLException {
    Instant now = dbTime(c);
    if (request instanceof OwnerRequest.Timeout || request instanceof OwnerRequest.Resolve) {
      lockOpenEntries(c, transactionId);
    }
    Optional<OwnerState> locked = lockOrCreate(c, institutionId, transactionId, request);
    UUID requestId = request.requestId(institutionId, transactionId);
    if (requestId != null) {
      Optional<Result> stored = stored(c, requestId);
      if (stored.isPresent()) {
        return stored.get();
      }
    }
    if (locked.isEmpty()) {
      if (request instanceof OwnerRequest.Customer customer && !customer.wasMe()) {
        return labelWithoutOwner(c, institutionId, transactionId, customer, requestId, now);
      }
      return result(
          Verdict.deferred(Verdict.Refusal.NO_FIRST_DECISION),
          requestId,
          null,
          List.of(),
          List.of(),
          null);
    }
    OwnerState owner = locked.get();
    OwnerRules.Facts facts = facts(c, owner);
    Verdict verdict = OwnerRules.evaluate(owner, request, now, facts);
    return write(c, owner, request, requestId, verdict, facts, now);
  }

  private Result write(
      Connection c,
      OwnerState owner,
      OwnerRequest request,
      UUID requestId,
      Verdict verdict,
      OwnerRules.Facts facts,
      Instant now)
      throws SQLException {
    UUID inst = owner.institutionId();
    UUID txn = owner.transactionId();
    if (verdict.contest() != null) {
      contest(c, owner, verdict.contest(), now);
    }
    switch (verdict.outcome()) {
      case DEFERRED -> {
        return result(verdict, requestId, null, List.of(), List.of(), null);
      }
      case CLAIMED -> {
        updateOwner(c, verdict.owner());
        return result(verdict, requestId, null, List.of(), List.of(), null);
      }
      case REFUSED, RELEASED -> {
        if (verdict.recorded()) {
          insertRequest(c, inst, txn, requestId, request, verdict, null, null, now);
        }
        if (verdict.owner() != null) {
          updateOwner(c, verdict.owner());
        }
        return result(verdict, requestId, null, List.of(), List.of(), null);
      }
      default -> {
        // APPLIED and LABEL_ONLY: below.
      }
    }
    DecisionState state = null;
    List<DecisionEvent> events = new ArrayList<>();
    UUID eventId = OwnerIds.event(requestId);
    UUID actor = actor(request);
    if (verdict.outcome() == Outcome.APPLIED) {
      Verdict.Transition t = verdict.transition();
      state =
          DecisionState.succeeding(
              inst,
              txn,
              owner.sequence(),
              owner.decision(),
              owner.decidedBy(),
              eventId,
              t.value(),
              t.by(),
              now,
              t.reasons(),
              t.resolution());
      String channel = request instanceof OwnerRequest.Timeout timeout ? timeout.channel() : null;
      events.add(new DecisionEvent.DecisionChanged(state, channel, actor));
    }
    if (verdict.label() != null) {
      Verdict.Label l = verdict.label();
      events.add(
          new DecisionEvent.LabelRecorded(
              OwnerIds.label(request),
              inst,
              txn,
              l.fraud(),
              l.source(),
              transactionTime(c, txn),
              now,
              l.supersedesLabelId()));
    }
    insertRequest(c, inst, txn, requestId, request, verdict, state, events, now);
    if (verdict.owner() != null) {
      updateOwner(c, verdict.owner());
    }
    List<UUID> closed = close(c, owner, request, requestId, verdict);
    if (verdict.unblock() != null) {
      unblock(c, inst, txn, request, verdict.unblock(), now);
    }
    UUID cleared =
        request instanceof OwnerRequest.Resolve && owner.claim() == Claim.ANALYST
            ? owner.claimDecisionId()
            : null;
    return result(verdict, requestId, state, events, closed, cleared);
  }

  private static Result result(
      Verdict verdict,
      UUID requestId,
      DecisionState state,
      List<DecisionEvent> events,
      List<UUID> closed,
      UUID cleared) {
    return new Result(
        verdict.outcome(),
        verdict.refusal(),
        verdict.reschedule(),
        requestId,
        false,
        state,
        events,
        closed,
        cleared);
  }

  /** §4.2: the row, created at most once from sequence 1 or a timeout's basis. */
  private Optional<OwnerState> lockOrCreate(
      Connection c, UUID inst, UUID txn, OwnerRequest request) throws SQLException {
    Optional<OwnerState> existing = selectOwner(c, inst, txn);
    if (existing.isPresent()) {
      return existing;
    }
    try (PreparedStatement s = c.prepareStatement(FIRST_DECISION_LOCK)) {
      s.setString(1, inst.toString());
      s.setString(2, txn.toString());
      s.execute();
    }
    boolean created = createFromFirst(c, inst, txn);
    if (!created && request instanceof OwnerRequest.Timeout t && t.basisEventId() != null) {
      insertOwner(
          c,
          inst,
          txn,
          t.basisEventId(),
          t.basisDecision(),
          t.basisDeadline(),
          t.policy(),
          t.basisDecision() == DecisionValue.HOLD ? t.basisDeadline() : null);
    }
    return selectOwner(c, inst, txn);
  }

  private boolean createFromFirst(Connection c, UUID inst, UUID txn) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_FIRST)) {
      s.setObject(1, inst);
      s.setObject(2, txn);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          return false;
        }
        DecisionValue first = DecisionValue.valueOf(row.getString(2));
        Instant deadline = instant(row, 3);
        String policy = row.getString(4);
        insertOwner(
            c,
            inst,
            txn,
            row.getObject(1, UUID.class),
            first,
            deadline,
            policy == null ? null : MediumTimeoutPolicy.valueOf(policy),
            first == DecisionValue.HOLD ? deadline : null);
        return true;
      }
    }
  }

  private static void insertOwner(
      Connection c,
      UUID inst,
      UUID txn,
      UUID firstEventId,
      DecisionValue first,
      Instant firstDeadline,
      MediumTimeoutPolicy policy,
      Instant deadline)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_OWNER)) {
      s.setObject(1, inst);
      s.setObject(2, txn);
      s.setObject(3, firstEventId);
      s.setString(4, first.name());
      s.setObject(5, utc(first == DecisionValue.HOLD ? firstDeadline : null));
      s.setString(6, first == DecisionValue.HOLD && policy != null ? policy.name() : null);
      s.setString(7, first.name());
      s.setObject(8, utc(deadline));
      s.executeUpdate();
    }
  }

  private static Optional<OwnerState> selectOwner(Connection c, UUID inst, UUID txn)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_OWNER)) {
      s.setObject(1, inst);
      s.setObject(2, txn);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        String policy = row.getString(4);
        String priorClaim = row.getString(11);
        return Optional.of(
            new OwnerState(
                inst,
                txn,
                row.getObject(1, UUID.class),
                DecisionValue.valueOf(row.getString(2)),
                instant(row, 3),
                policy == null ? null : MediumTimeoutPolicy.valueOf(policy),
                row.getInt(5),
                DecisionValue.valueOf(row.getString(6)),
                DecidedBy.valueOf(row.getString(7)),
                Claim.valueOf(row.getString(8)),
                row.getObject(9, UUID.class),
                instant(row, 10),
                priorClaim == null ? null : Claim.valueOf(priorClaim),
                instant(row, 12),
                instant(row, 13),
                row.getObject(14, UUID.class)));
      }
    }
  }

  private static void updateOwner(Connection c, OwnerState o) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(UPDATE_OWNER)) {
      s.setObject(1, o.firstEventId());
      s.setInt(2, o.sequence());
      s.setString(3, o.decision().name());
      s.setString(4, o.decidedBy().name());
      s.setString(5, o.claim().name());
      s.setObject(6, o.claimDecisionId());
      s.setObject(7, utc(o.deadline()));
      s.setString(8, o.priorClaim() == null ? null : o.priorClaim().name());
      s.setObject(9, utc(o.priorDeadline()));
      s.setObject(10, utc(o.contestedSince()));
      s.setObject(11, o.resolutionId());
      s.setObject(12, o.institutionId());
      s.setObject(13, o.transactionId());
      s.executeUpdate();
    }
  }

  /** §4.3 step 2: a request already made returns its stored outcome. */
  private static Optional<Result> stored(Connection c, UUID requestId) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_REQUEST)) {
      s.setObject(1, requestId);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        String stored = row.getString(1);
        Outcome outcome = "PRE_V81".equals(stored) ? Outcome.REFUSED : Outcome.valueOf(stored);
        return Optional.of(
            new Result(
                outcome, null, null, requestId, true, null, List.of(), List.of(), null));
      }
    }
  }

  private static OwnerRules.Facts facts(Connection c, OwnerState o) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_FACTS)) {
      s.setObject(1, o.claimDecisionId());
      s.setObject(2, o.transactionId());
      try (ResultSet row = s.executeQuery()) {
        row.next();
        boolean failed = row.getBoolean(1);
        boolean blocked = row.getObject(2) != null;
        return new OwnerRules.Facts(failed, blocked, blocked && row.getBoolean(3));
      }
    }
  }

  private static boolean insertRequest(
      Connection c,
      UUID inst,
      UUID txn,
      UUID requestId,
      OwnerRequest request,
      Verdict verdict,
      DecisionState state,
      List<DecisionEvent> events,
      Instant now)
      throws SQLException {
    if (requestId == null) {
      return true;
    }
    boolean withEvent = events != null && !events.isEmpty();
    try (PreparedStatement s = c.prepareStatement(INSERT_REQUEST)) {
      s.setObject(1, inst);
      s.setObject(2, txn);
      s.setObject(3, requestId);
      s.setString(4, request.kind());
      s.setString(5, verdict.outcome().name());
      s.setString(6, verdict.refusal() == null ? null : verdict.refusal().reason());
      if (state != null) {
        s.setInt(7, state.sequence());
        s.setString(8, state.decision().name());
        s.setString(9, state.supersedes().name());
        Array codes = c.createArrayOf("text", state.reasonCodes().toArray());
        s.setArray(10, codes);
        s.setString(12, state.decidedBy().name());
      } else {
        s.setNull(7, Types.INTEGER);
        s.setNull(8, Types.VARCHAR);
        s.setNull(9, Types.VARCHAR);
        s.setNull(10, Types.ARRAY);
        s.setNull(12, Types.VARCHAR);
      }
      s.setObject(11, withEvent ? OwnerIds.event(requestId) : null);
      s.setObject(13, utc(now));
      s.setString(
          14,
          withEvent ? new String(FactCodec.encode(events), StandardCharsets.UTF_8) : null);
      return s.executeUpdate() == 1;
    }
  }

  /** §5: a TIMEOUT closes open and released entries as AUTO_*; a RESOLVE closes open ones. */
  private static List<UUID> close(
      Connection c, OwnerState o, OwnerRequest request, UUID requestId, Verdict verdict)
      throws SQLException {
    if (verdict.closeEntries() == Closing.NONE) {
      return List.of();
    }
    List<UUID> closed = new ArrayList<>();
    UUID newest = null;
    Instant newestAt = null;
    boolean declined = verdict.transition().value() == DecisionValue.DECLINE;
    String sql = verdict.closeEntries() == Closing.TIMEOUT ? CLOSE_ON_TIMEOUT : CLOSE_ON_RESOLVE;
    try (PreparedStatement s = c.prepareStatement(sql)) {
      if (verdict.closeEntries() == Closing.TIMEOUT) {
        s.setString(1, declined ? "AUTO_DECLINED" : "AUTO_RELEASED");
      } else {
        s.setObject(1, ((OwnerRequest.Resolve) request).resolutionId());
      }
      s.setObject(2, o.transactionId());
      try (ResultSet rows = s.executeQuery()) {
        while (rows.next()) {
          UUID id = rows.getObject(1, UUID.class);
          Instant at = instant(rows, 2);
          closed.add(id);
          if (newestAt == null || at.isAfter(newestAt)) {
            newest = id;
            newestAt = at;
          }
        }
      }
    }
    if (verdict.closeEntries() == Closing.TIMEOUT && newest != null) {
      try (PreparedStatement s = c.prepareStatement(INSERT_AUTO_DECISION)) {
        s.setObject(1, o.institutionId());
        s.setObject(2, newest);
        s.setString(3, declined ? "AUTO_BLOCKED" : "AUTO_RELEASED");
        s.setObject(4, OwnerIds.autoDecisionKey(requestId));
        s.executeUpdate();
      }
    }
    return closed;
  }

  private static void unblock(
      Connection c,
      UUID inst,
      UUID txn,
      OwnerRequest request,
      Verdict.Unblock unblock,
      Instant now)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id FROM auto_block_events WHERE transaction_id = ? ORDER BY blocked_at LIMIT 1")) {
      s.setObject(1, txn);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          return;
        }
        try (PreparedStatement insert = c.prepareStatement(INSERT_UNBLOCK)) {
          insert.setObject(1, inst);
          insert.setObject(2, row.getObject(1, UUID.class));
          insert.setString(3, unblock.cause());
          insert.setObject(4, unblock.actorUserId());
          insert.setObject(
              5,
              request instanceof OwnerRequest.Customer customer ? customer.verificationId() : null);
          insert.setObject(6, utc(now));
          insert.executeUpdate();
        }
      }
    }
  }

  /** §7.1: a second first decision, which contests the owner only when it is new. */
  private static void contest(Connection c, OwnerState o, Verdict.Contest contest, Instant now)
      throws SQLException {
    if (recordConflict(c, o.institutionId(), o.transactionId(), contest)
        && o.contestedSince() == null) {
      try (PreparedStatement s = c.prepareStatement(CONTEST)) {
        s.setObject(1, utc(now));
        s.setObject(2, o.institutionId());
        s.setObject(3, o.transactionId());
        s.executeUpdate();
      }
    }
  }

  static boolean recordConflict(Connection c, UUID inst, UUID txn, Verdict.Contest contest)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_CONFLICT)) {
      s.setObject(1, inst);
      s.setObject(2, txn);
      s.setObject(3, contest.eventId());
      s.setString(4, contest.decision().name());
      s.setObject(5, utc(contest.deadline()));
      s.setString(6, contest.detectedBy());
      return s.executeUpdate() == 1;
    }
  }

  /**
   * §6.1: a sequence-1 record reaching the sink. Called by {@code PostgresSink} for every {@code
   * TransactionDecided}, after it has taken the first-decision lock and before it writes {@code
   * transaction_ids}, with the kept sequence-1 row if one exists.
   *
   * @param c the sink's connection, with the tenant set
   * @param record the record's sequence-1 state
   * @param kept the sequence-1 state already in PostgreSQL, or empty
   * @return whether the sink writes the record in full; false only for M6's duplicate path
   * @throws SQLException on a database error
   */
  public boolean firstDecisionArrives(
      Connection c, DecisionState record, Optional<FirstDecision> kept) throws SQLException {
    UUID inst = record.institutionId();
    UUID txn = record.transactionId();
    Optional<OwnerState> owner = selectOwner(c, inst, txn);
    Instant now = dbTime(c);
    if (kept.isPresent()) {
      FirstDecision k = kept.get();
      if (k.eventId().equals(record.eventId())) {
        return true;
      }
      if (owner.isPresent()) {
        OwnerState o = owner.get();
        if (o.firstEventId().equals(record.eventId()) || conflictKnown(c, record.eventId())) {
          return false;
        }
        if (record.decision() != o.firstDecision()) {
          contest(c, o, contestOf(record, "SINK"), now);
        }
        return false;
      }
      if (record.decision() != k.decision()) {
        lockOrCreateForSink(c, inst, txn);
        Optional<OwnerState> created = selectOwner(c, inst, txn);
        if (created.isPresent()) {
          contest(c, created.get(), contestOf(record, "SINK"), now);
        }
      }
      return false;
    }
    if (owner.isEmpty()) {
      return true;
    }
    OwnerState o = owner.get();
    if (o.firstEventId().equals(record.eventId())) {
      return true;
    }
    if (record.decision() == o.firstDecision()) {
      updateOwner(c, o.standingOn(record.eventId()));
      return true;
    }
    contest(c, o, contestOf(record, "SINK"), now);
    return true;
  }

  /**
   * §6.2: a MEDIUM entry the sink has just inserted. Under the owner row's lock, an entry that
   * arrives after a TIMEOUT or a RESOLVE is closed at once, so no entry is left open. Called by the
   * sink, which already holds the first-decision lock.
   *
   * @param c the sink's connection, with the tenant set
   * @param inst institution
   * @param txn transaction
   * @param entryId the new entry
   * @throws SQLException on a database error
   */
  public void lateEntry(Connection c, UUID inst, UUID txn, UUID entryId) throws SQLException {
    createFromFirst(c, inst, txn);
    Optional<OwnerState> owner = selectOwner(c, inst, txn);
    if (owner.isEmpty() || owner.get().held()) {
      return;
    }
    UUID timeout = new OwnerRequest.Timeout(null, null, null, null, null,
        MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL, null).requestId(inst, txn);
    String timedOut = null;
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT decision FROM transaction_requests WHERE request_id = ? AND outcome = 'APPLIED'")) {
      s.setObject(1, timeout);
      try (ResultSet row = s.executeQuery()) {
        timedOut = row.next() ? row.getString(1) : null;
      }
    }
    if (timedOut != null) {
      boolean declined = DecisionValue.DECLINE.name().equals(timedOut);
      try (PreparedStatement s =
          c.prepareStatement(
              "UPDATE alert_queue_entries SET status = ?, version = version + 1 WHERE id = ?"
                  + " AND status IN ('PENDING', 'IN_REVIEW')")) {
        s.setString(1, declined ? "AUTO_DECLINED" : "AUTO_RELEASED");
        s.setObject(2, entryId);
        s.executeUpdate();
      }
      try (PreparedStatement s = c.prepareStatement(INSERT_AUTO_DECISION)) {
        s.setObject(1, inst);
        s.setObject(2, entryId);
        s.setString(3, declined ? "AUTO_BLOCKED" : "AUTO_RELEASED");
        s.setObject(4, OwnerIds.autoDecisionKey(timeout));
        s.executeUpdate();
      }
    } else if (owner.get().resolutionId() != null) {
      try (PreparedStatement s =
          c.prepareStatement(
              "UPDATE alert_queue_entries SET status = 'OVERRIDDEN', resolution_id = ?, version ="
                  + " version + 1 WHERE id = ? AND status IN ('PENDING', 'IN_REVIEW')")) {
        s.setObject(1, owner.get().resolutionId());
        s.setObject(2, entryId);
        s.executeUpdate();
      }
    }
  }

  private void lockOrCreateForSink(Connection c, UUID inst, UUID txn) throws SQLException {
    createFromFirst(c, inst, txn);
  }

  /**
   * The sequence-1 row already in PostgreSQL.
   *
   * @param eventId its event id
   * @param decision its value
   */
  public record FirstDecision(UUID eventId, DecisionValue decision) {}

  private static Verdict.Contest contestOf(DecisionState record, String detectedBy) {
    return new Verdict.Contest(
        record.eventId(), record.decision(), record.reviewDeadlineAt(), detectedBy);
  }

  private static boolean conflictKnown(Connection c, UUID eventId) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT 1 FROM decision_conflicts WHERE event_id = ?")) {
      s.setObject(1, eventId);
      try (ResultSet row = s.executeQuery()) {
        return row.next();
      }
    }
  }

  /** CUSTOMER_NO with no owner row yet: its label needs no state (§5). */
  private Result labelWithoutOwner(
      Connection c,
      UUID inst,
      UUID txn,
      OwnerRequest.Customer customer,
      UUID requestId,
      Instant now)
      throws SQLException {
    Verdict verdict =
        new Verdict(
            Outcome.LABEL_ONLY,
            null,
            true,
            null,
            null,
            new Verdict.Label(true, "CUSTOMER", null),
            Closing.NONE,
            null,
            null,
            null);
    List<DecisionEvent> events =
        List.of(
            new DecisionEvent.LabelRecorded(
                OwnerIds.label(customer), inst, txn, true, "CUSTOMER", transactionTime(c, txn), now,
                null));
    if (!insertRequest(c, inst, txn, requestId, customer, verdict, null, events, now)) {
      return stored(c, requestId).orElseThrow();
    }
    return result(verdict, requestId, null, events, List.of(), null);
  }

  private static UUID actor(OwnerRequest request) {
    return switch (request) {
      case OwnerRequest.Effect e -> e.analystId();
      case OwnerRequest.SeniorOverride o -> o.seniorId();
      case OwnerRequest.Resolve r -> r.officerId();
      default -> null;
    };
  }

  private static void lockOpenEntries(Connection c, UUID txn) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(LOCK_OPEN_ENTRIES)) {
      s.setObject(1, txn);
      try (ResultSet rows = s.executeQuery()) {
        while (rows.next()) {
          rows.getObject(1);
        }
      }
    }
  }

  private static Instant transactionTime(Connection c, UUID txn) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_TRANSACTION_TIME)) {
      s.setObject(1, txn);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          throw new SQLException("transaction " + txn + " has no transaction_ids row", "P0002");
        }
        return instant(row, 1);
      }
    }
  }

  private static Instant dbTime(Connection c) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT clock_timestamp()");
        ResultSet row = s.executeQuery()) {
      row.next();
      return instant(row, 1);
    }
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    OffsetDateTime at = row.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  private static OffsetDateTime utc(Instant at) {
    return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }
}
