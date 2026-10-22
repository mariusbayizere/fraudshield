package io.github.mariusbayizere.fraudshield.decision.owner;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcDecisionStates;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.PostgresSink;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolRecord;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.testing.FactScenarios;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner against PostgreSQL (decision-owner contract §4, §6.1, §6.2, §7.1): row creation under
 * the first-decision lock, the sink's cases, the contest, and request ids.
 */
@Tag("requires-docker")
@Tag("D-14")
@Tag("FR-03-02")
class DecisionOwnerTest {

  @TempDir Path deadLetters;
  private TestDatabase db;
  private DataSource app;
  private PostgresSink sink;
  private OwnerTransactions transactions;
  private final DecisionOwner owner = new DecisionOwner();

  /** A MEDIUM hold's sequence-1 record and its alert, from the shared scenarios. */
  private List<DecisionEvent> heldRecord;

  private DecisionState hold;

  @BeforeEach
  void setUp() throws SQLException {
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    app = db.dataSource("fs_app");
    sink = new PostgresSink(app, deadLetters);
    transactions = new OwnerTransactions(app, 3);
    List<DecisionEvent> facts = FactScenarios.everyKindOfFact();
    DecisionEvent.TransactionDecided decided =
        facts.stream()
            .filter(DecisionEvent.TransactionDecided.class::isInstance)
            .map(DecisionEvent.TransactionDecided.class::cast)
            .filter(e -> e.state().decision() == DecisionValue.HOLD)
            .findFirst()
            .orElseThrow();
    hold = decided.state();
    heldRecord = new ArrayList<>();
    heldRecord.add(decided);
    facts.stream()
        .filter(DecisionEvent.AlertRaised.class::isInstance)
        .map(DecisionEvent.AlertRaised.class::cast)
        .filter(a -> a.transaction().transactionId().equals(hold.transactionId()))
        .forEach(heldRecord::add);
  }

  private static List<SpoolRecord> records(List<DecisionEvent> facts) {
    List<SpoolRecord> records = new ArrayList<>();
    long offset = 0;
    for (DecisionEvent fact : facts) {
      byte[] payload = FactCodec.encode(List.of(fact));
      records.add(new SpoolRecord(offset, offset + payload.length + 8, payload));
      offset += payload.length + 8;
    }
    return records;
  }

  private OwnerRequest.Timeout timeoutOf(DecisionState basis) {
    return new OwnerRequest.Timeout(
        basis.eventId(),
        basis.decision(),
        basis.reviewDeadlineAt(),
        MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL,
        "CARD");
  }

  private DecisionOwner.Result submit(OwnerRequest request) throws SQLException {
    return transactions.inTenant(
        INSTITUTION, c -> owner.submit(c, INSTITUTION, hold.transactionId(), request));
  }

  private String one(String sql) throws SQLException {
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  @Test
  void theSqlIdsMatchTheJavaIds() throws SQLException {
    for (String name :
        List.of("verification|" + UUID.randomUUID(), "resolve|x", "hold_timeout|a|b", "é")) {
      assertThat(one("SELECT fraudshield.fs_name_uuid('" + name + "')"))
          .as(name)
          .isEqualTo(OwnerIds.name(name).toString());
    }
  }

  @Test
  void timeoutOnOwnHoldAppliesOnceAndRerunReturnsTheStoredOutcome() throws Exception {
    sink.accept(records(heldRecord));
    DecisionOwner.Result first = submit(timeoutOf(hold));
    assertThat(first.outcome()).isEqualTo(Verdict.Outcome.APPLIED);
    assertThat(first.state().decision()).isEqualTo(DecisionValue.TIMEOUT_RELEASE);
    assertThat(first.state().sequence()).isEqualTo(2);
    assertThat(first.closedEntries()).hasSize(1);
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries"))
        .isEqualTo("AUTO_RELEASED");
    assertThat(one("SELECT decision FROM fraudshield.alert_decisions")).isEqualTo("AUTO_RELEASED");

    DecisionOwner.Result again = submit(timeoutOf(hold));
    assertThat(again.replay()).isTrue();
    assertThat(one("SELECT count(*) FROM fraudshield.transaction_requests")).isEqualTo("1");
    assertThat(new JdbcDecisionStates(app).latest(INSTITUTION, hold.transactionId()).orElseThrow())
        .extracting(DecisionState::decision)
        .isEqualTo(DecisionValue.TIMEOUT_RELEASE);
  }

  @Test
  @Tag("NFR-REL-01")
  void theOwnersOwnFirstDecisionArrivingAfterItsTimeoutIsWrittenInFull() throws Exception {
    // P7a, review 1's MAJOR 1: the timeout creates the owner from its DueHold, then the hold's
    // own record drains, as after any PostgreSQL outage longer than the hold window.
    DecisionOwner.Result timedOut = submit(timeoutOf(hold));
    assertThat(timedOut.outcome()).isEqualTo(Verdict.Outcome.APPLIED);
    sink.accept(records(heldRecord));
    assertThat(sink.duplicateDecisions()).isZero();
    assertThat(one("SELECT count(*) FROM fraudshield.decision_states WHERE decision_sequence = 1"))
        .isEqualTo("1");
    assertThat(one("SELECT count(*) FROM fraudshield.transaction_ids")).isEqualTo("1");
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries"))
        .as("§6.2 closes the late entry")
        .isEqualTo("AUTO_RELEASED");
  }

  @Test
  void timeoutWhoseHoldDiffersFromTheDrainedFirstDecisionRecordsTheContest() throws Exception {
    // P7, review 8's MAJOR 1: the retry's DECLINE reached PostgreSQL first.
    DecisionState declined =
        DecisionState.initial(
            INSTITUTION,
            hold.transactionId(),
            io.github.mariusbayizere.fraudshield.decision.domain.Decision.DECLINE,
            hold.decidedAt().plusSeconds(12),
            List.of(),
            null);
    DecisionEvent.TransactionDecided decided = (DecisionEvent.TransactionDecided) heldRecord.get(0);
    sink.accept(
        records(
            List.of(
                new DecisionEvent.TransactionDecided(
                    decided.transaction(),
                    decided.scoring(),
                    decided.outcome(),
                    declined,
                    decided.thresholdsVersion(),
                    decided.requestFingerprint(),
                    3))));
    DecisionOwner.Result result = submit(timeoutOf(hold));
    assertThat(result.outcome()).isEqualTo(Verdict.Outcome.REFUSED);
    assertThat(result.refusal()).isEqualTo(Verdict.Refusal.CONFLICT);
    assertThat(one("SELECT count(*) FROM fraudshield.decision_conflicts")).isEqualTo("1");
    assertThat(one("SELECT contested_since IS NOT NULL FROM fraudshield.decision_owners"))
        .isEqualTo("t");
    assertThat(one("SELECT count(*) FROM fraudshield.transaction_requests")).isEqualTo("0");
    assertThat(new JdbcDecisionStates(app).poll(INSTITUTION, hold.transactionId()).contested())
        .isTrue();
  }

  @Test
  void conflictingFirstDecisionDrainingAfterTheTimeoutIsWrittenAndContests() throws Exception {
    // §6.1 case 5, the inherited reverse order: the timeout acted from the DueHold first.
    submit(timeoutOf(hold));
    DecisionState declined =
        DecisionState.initial(
            INSTITUTION,
            hold.transactionId(),
            io.github.mariusbayizere.fraudshield.decision.domain.Decision.DECLINE,
            hold.decidedAt().plusSeconds(12),
            List.of(),
            null);
    DecisionEvent.TransactionDecided decided = (DecisionEvent.TransactionDecided) heldRecord.get(0);
    sink.accept(
        records(
            List.of(
                new DecisionEvent.TransactionDecided(
                    decided.transaction(),
                    decided.scoring(),
                    decided.outcome(),
                    declined,
                    decided.thresholdsVersion(),
                    decided.requestFingerprint(),
                    3))));
    assertThat(one("SELECT decision FROM fraudshield.decision_states")).isEqualTo("DECLINE");
    assertThat(one("SELECT contested_since IS NOT NULL FROM fraudshield.decision_owners"))
        .isEqualTo("t");

    // The hold's own record draining later re-contests nothing (review 2, finding 3; P8c).
    String since = one("SELECT contested_since FROM fraudshield.decision_owners");
    sink.accept(records(heldRecord));
    assertThat(one("SELECT contested_since FROM fraudshield.decision_owners")).isEqualTo(since);
    assertThat(one("SELECT count(*) FROM fraudshield.decision_conflicts")).isEqualTo("1");
  }

  @Test
  void appliedRequestOutOfSequenceIsRefusedByTheBackstop() throws Exception {
    sink.accept(records(heldRecord));
    submit(timeoutOf(hold));
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  s.execute(
                      "SET fraudshield.institution_id = '"
                          + INSTITUTION
                          + "'; INSERT INTO fraudshield.transaction_requests (institution_id,"
                          + " transaction_id, request_id, kind, outcome, sequence, decision,"
                          + " supersedes_decision, reason_codes, event_id, decided_by,"
                          + " event_body) VALUES ('"
                          + INSTITUTION
                          + "', '"
                          + hold.transactionId()
                          + "', gen_random_uuid(), 'OVERRIDE', 'APPLIED', 2, 'DECLINE',"
                          + " 'TIMEOUT_RELEASE', '{}', gen_random_uuid(), 'SENIOR_OVERRIDE',"
                          + " '[]')"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("not the owner's next");
    }
  }

  /** A model DECLINE for the scenario's transaction, as the sink would have written it. */
  private DecisionState declined() throws Exception {
    DecisionEvent.TransactionDecided decided = (DecisionEvent.TransactionDecided) heldRecord.get(0);
    DecisionState decline =
        DecisionState.initial(
            INSTITUTION,
            hold.transactionId(),
            io.github.mariusbayizere.fraudshield.decision.domain.Decision.DECLINE,
            hold.decidedAt(),
            List.of(),
            null);
    sink.accept(
        records(
            List.of(
                new DecisionEvent.TransactionDecided(
                    decided.transaction(),
                    decided.scoring(),
                    decided.outcome(),
                    decline,
                    decided.thresholdsVersion(),
                    decided.requestFingerprint(),
                    3))));
    return decline;
  }

  @Test
  @Tag("FR-03-05")
  void concurrentRequestsTakeConsecutiveSequencesNeverTheSameOne() throws Exception {
    // P5: a senior's override and a customer's "yes" race on one transaction.
    declined();
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<DecisionOwner.Result> override =
          pool.submit(
              () ->
                  submit(
                      new OwnerRequest.SeniorOverride(
                          UUID.randomUUID(), true, UUID.randomUUID(), null)));
      java.util.concurrent.Future<DecisionOwner.Result> customer =
          pool.submit(() -> submit(new OwnerRequest.Customer(UUID.randomUUID(), true)));
      override.get();
      customer.get();
    } finally {
      pool.shutdown();
    }
    assertThat(
            one(
                "SELECT string_agg(sequence::text, ',' ORDER BY sequence) FROM"
                    + " fraudshield.transaction_requests WHERE outcome = 'APPLIED'"))
        .isIn("2", "2,3");
    assertThat(one("SELECT sequence FROM fraudshield.decision_owners"))
        .isEqualTo(one("SELECT coalesce(max(sequence), 1) FROM fraudshield.transaction_requests"));
  }

  @Test
  void timeoutUnderDeclineAndVerifyClosesTheEntryAsAutoDeclined() throws Exception {
    // P13. The owner holds the hold's policy, from the record's own thresholds (§4.2 point 4),
    // and it governs over the request's; the channel's policy here is DECLINE_AND_VERIFY.
    sink.accept(records(heldRecord));
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      s.execute("UPDATE fraudshield.decision_owners SET timeout_policy = 'DECLINE_AND_VERIFY'");
    }
    DecisionOwner.Result result =
        submit(
            new OwnerRequest.Timeout(
                hold.eventId(),
                hold.decision(),
                hold.reviewDeadlineAt(),
                MediumTimeoutPolicy.DECLINE_AND_VERIFY,
                "CARD"));
    assertThat(result.state().decision()).isEqualTo(DecisionValue.DECLINE);
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries"))
        .isEqualTo("AUTO_DECLINED");
    assertThat(one("SELECT decision FROM fraudshield.alert_decisions")).isEqualTo("AUTO_BLOCKED");
  }

  @Test
  @Tag("FR-04-10")
  void effectDeferredByContestKeepsOnlyItsLabelOnceTheContestIsResolved() throws Exception {
    // P8: a claim, a contest, the effect deferred, the resolution, then the effect's label only.
    sink.accept(records(heldRecord));
    UUID decision = UUID.randomUUID();
    assertThat(submit(new OwnerRequest.ClaimRequest(decision, false)).outcome())
        .isEqualTo(Verdict.Outcome.CLAIMED);
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      s.execute("UPDATE fraudshield.decision_owners SET contested_since = now()");
    }
    assertThat(submit(new OwnerRequest.Effect(decision, true, UUID.randomUUID())).outcome())
        .isEqualTo(Verdict.Outcome.DEFERRED);
    java.time.Instant since;
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet rows =
            s.executeQuery("SELECT contested_since FROM fraudshield.decision_owners")) {
      rows.next();
      since = rows.getObject(1, java.time.OffsetDateTime.class).toInstant();
    }
    DecisionOwner.Result resolved =
        submit(
            new OwnerRequest.Resolve(
                UUID.randomUUID(), DecisionValue.DECLINE, UUID.randomUUID(), since));
    assertThat(resolved.outcome()).isEqualTo(Verdict.Outcome.APPLIED);
    assertThat(resolved.clearedClaim()).isEqualTo(decision);
    DecisionOwner.Result effect =
        submit(new OwnerRequest.Effect(decision, true, UUID.randomUUID()));
    assertThat(effect.outcome()).isEqualTo(Verdict.Outcome.LABEL_ONLY);
    assertThat(effect.refusal()).isEqualTo(Verdict.Refusal.CLAIM_LOST);
    assertThat(effect.events()).singleElement().isInstanceOf(DecisionEvent.LabelRecorded.class);
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries")).isEqualTo("OVERRIDDEN");
  }

  @Test
  void theSequenceOneTableRefusesLaterStates() throws Exception {
    // P17: from V81 on, decision_states holds sequence 1 only.
    sink.accept(records(heldRecord));
    try (Connection c = db.superuser();
        Statement s = c.createStatement()) {
      s.execute("SET fraudshield.institution_id = '" + INSTITUTION + "'");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  s.execute(
                      "INSERT INTO fraudshield.decision_states (event_id, institution_id,"
                          + " transaction_id, decision_sequence, decision, final, decided_at,"
                          + " decided_by, supersedes_decision) VALUES (gen_random_uuid(), '"
                          + INSTITUTION
                          + "', '"
                          + hold.transactionId()
                          + "', 2, 'APPROVE', true, now(), 'ANALYST', 'HOLD')"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("decision_states_first_only");
    }
  }
}
