package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.analyst.decisions.OwnerTestAutoConfiguration;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import io.github.mariusbayizere.fraudshield.decision.application.port.AlertChangePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * P2's four decision operations against the real application and database, under the decision-owner
 * contract (§9, ADR 0114). The committer, the effects worker and the resolver run on their own
 * schedulers, every 500 ms, as in production.
 */
@Tag("FR-04-04")
@Tag("FR-04-10")
@Tag("D-29")
@Tag("D-44")
class AnalystDecisionsTest extends AnalystIntegrationTest {

  private static final UUID BANK = DB.createInstitution("analyst-decisions");

  private Session as(String role) {
    return issueSession(createAccount(BANK, role, "ACTIVE"));
  }

  /** A MEDIUM alert whose hold runs 30 s from now, so it can still be decided. */
  private static Alert heldNow() {
    Tx tx = new Tx(BANK);
    tx.at = Instant.now();
    return new Alert(tx.write()).write();
  }

  private static Alert highBlocked() {
    Tx tx = new Tx(BANK);
    tx.at = Instant.now();
    tx.riskTier = "HIGH";
    tx.score = 0.95;
    tx.decision = "DECLINE";
    Alert alert = new Alert(tx.write());
    alert.tier = "HIGH";
    return alert.write();
  }

  private Http.Response post(Session session, String path, Object body, UUID key) {
    String[] bearer = session.bearer();
    return key == null
        ? http.post(path, body, bearer)
        : http.post(path, body, bearer[0], bearer[1], "Idempotency-Key", key.toString());
  }

  private static Map<String, Object> decision(String kind, long version) {
    return Map.of(
        "decision", kind, "comment", "Checked the device history", "alert_version", version);
  }

  private static void eventually(String what, Supplier<Boolean> condition) {
    long until = System.nanoTime() + 20_000_000_000L;
    while (!condition.get()) {
      if (System.nanoTime() > until) {
        throw new AssertionError("timed out waiting for " + what);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  /** Whether the entry's change was offered to the real-time channel (ADR 0115 point 6.2). */
  private static void published(
      UUID alertId, long version, AlertChangePublisher.Change change, boolean byPerson) {
    eventually(
        change + " published",
        () ->
            OwnerTestAutoConfiguration.CHANGES.offered.stream()
                .anyMatch(
                    o ->
                        o.alertId().equals(alertId)
                            && o.version() == version
                            && o.change() == change
                            && o.institutionId().equals(BANK)
                            && (o.actorUserId() != null) == byPerson));
  }

  private static String one(String sql, Object... args) {
    Object value = query(sql, args);
    return value == null ? null : value.toString();
  }

  @Test
  void decidingHoldCommitsAfterTheWindowAndItsEffectDeclinesThePayment() {
    Alert alert = heldNow();
    Session analyst = as("ANALYST");
    UUID key = UUID.randomUUID();

    Http.Response accepted =
        post(
            analyst,
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("CONFIRM_FRAUD", 0),
            key);
    assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
    JsonNode body = accepted.json();
    assertThat(OpenApiSchemas.errors("AlertDecisionAccepted", body)).isEmpty();
    assertThat(body.get("state").asString()).isEqualTo("PENDING_COMMIT");
    published(alert.id, 1, AlertChangePublisher.Change.DECIDED, true);

    Http.Response replay =
        post(
            analyst,
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("CONFIRM_FRAUD", 0),
            key);
    assertThat(replay.status()).isEqualTo(202);
    assertThat(replay.json().get("decision_id")).isEqualTo(body.get("decision_id"));
    Http.Response conflicting =
        post(
            analyst,
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("MARK_LEGITIMATE", 0),
            key);
    assertThat(conflicting.status()).isEqualTo(409);
    assertThat(conflicting.json().get("type").asString())
        .isEqualTo("urn:fraudshield:problem:idempotency-conflict");

    String decisionId = body.get("decision_id").asString();
    eventually(
        "the effect",
        () ->
            "APPLIED"
                .equals(
                    one(
                        "SELECT outcome FROM fraudshield.alert_decision_effects WHERE decision_id"
                            + " = ?::uuid",
                        decisionId)));
    assertThat(
            one(
                "SELECT decision || '/' || decided_by FROM fraudshield.v_decision_states WHERE"
                    + " transaction_id = ? AND decision_sequence = 2",
                alert.tx.id))
        .isEqualTo("DECLINE/ANALYST");
    assertThat(auditActions(UUID.fromString(decisionId))).contains("DECIDED", "COMMITTED");
  }

  @Test
  void undoInsideTheWindowPutsTheAlertBackAndReleasesTheClaim() {
    Alert alert = heldNow();
    Session analyst = as("ANALYST");
    Http.Response accepted =
        post(
            analyst,
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("MARK_LEGITIMATE", 0),
            UUID.randomUUID());
    String decisionId = accepted.json().get("decision_id").asString();
    Http.Response undone =
        post(
            analyst,
            "/api/v1/alerts/" + alert.id + "/decisions/" + decisionId + "/undo",
            Map.of(),
            null);
    assertThat(undone.status()).as(undone.body()).isEqualTo(204);
    published(alert.id, 2, AlertChangePublisher.Change.UNDONE, true);
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries WHERE id = ?", alert.id))
        .isEqualTo("PENDING");
    assertThat(
            one(
                "SELECT claim FROM fraudshield.decision_owners WHERE transaction_id = ?",
                alert.tx.id))
        .isEqualTo("NONE");

    Session other = as("ANALYST");
    Http.Response notTheirs =
        post(
            other,
            "/api/v1/alerts/" + alert.id + "/decisions/" + decisionId + "/undo",
            Map.of(),
            null);
    assertThat(notTheirs.status()).isEqualTo(403);
  }

  @Test
  void staleVersionIsConflictCarryingTheCurrentAlert() {
    Alert alert = heldNow();
    Http.Response stale =
        post(
            as("ANALYST"),
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("CONFIRM_FRAUD", 7),
            UUID.randomUUID());
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.json().get("type").asString())
        .isEqualTo("urn:fraudshield:problem:stale-alert");
    assertThat(stale.json().get("current").get("alert_id").asString())
        .isEqualTo(alert.id.toString());
    List<String> problems = new ArrayList<>();
    AnalystInspectorTest.inspect("stale-alert", "", null, stale.json(), problems);
    assertThat(problems).as("the current alert carries tokens and masked values only").isEmpty();
  }

  @Test
  void holdPastItsDeadlineCanNoLongerBeDecidedHere() {
    Tx tx = new Tx(BANK);
    tx.at = Instant.now().minusSeconds(60);
    Alert alert = new Alert(tx.write()).write();
    Http.Response late =
        post(
            as("ANALYST"),
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("CONFIRM_FRAUD", 0),
            UUID.randomUUID());
    assertThat(late.status()).isEqualTo(409);
    assertThat(late.json().get("type").asString())
        .isEqualTo("urn:fraudshield:problem:review-deadline-passed");
  }

  @Test
  void escalatingStopsTheTimerAndOnlyTheTargetRoleDecidesTheEscalatedEntry() {
    Alert alert = heldNow();
    Http.Response escalated =
        post(
            as("ANALYST"),
            "/api/v1/alerts/" + alert.id + "/escalations",
            Map.of(
                "target_role", "SENIOR_ANALYST", "reason", "Unusual pattern", "alert_version", 0),
            UUID.randomUUID());
    assertThat(escalated.status()).as(escalated.body()).isEqualTo(201);
    JsonNode summary = escalated.json();
    assertThat(OpenApiSchemas.errors("AlertSummary", summary)).isEmpty();
    List<String> problems = new ArrayList<>();
    AnalystInspectorTest.inspect("escalateAlert", "", null, summary, problems);
    assertThat(problems).isEmpty();
    UUID escalatedId = UUID.fromString(summary.get("alert_id").asString());
    published(alert.id, 1, AlertChangePublisher.Change.ESCALATED, true);
    published(escalatedId, 0, AlertChangePublisher.Change.ESCALATION_CREATED, true);
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries WHERE id = ?", alert.id))
        .isEqualTo("ESCALATED");
    assertThat(
            one(
                "SELECT deadline > now() + interval '10 minutes' FROM fraudshield.decision_owners"
                    + " WHERE transaction_id = ?",
                alert.tx.id))
        .as("the escalation window, W")
        .isEqualTo("true");

    Http.Response analyst =
        post(
            as("ANALYST"),
            "/api/v1/alerts/" + escalatedId + "/decisions",
            decision("CONFIRM_FRAUD", 0),
            UUID.randomUUID());
    assertThat(analyst.status()).isEqualTo(403);
    Http.Response senior =
        post(
            as("SENIOR_ANALYST"),
            "/api/v1/alerts/" + escalatedId + "/decisions",
            decision("CONFIRM_FRAUD", 0),
            UUID.randomUUID());
    assertThat(senior.status()).as(senior.body()).isEqualTo(202);
  }

  @Test
  void riskOfficerFraudOverrideOnBlockedPaymentIsStateNotJustLabel() {
    // Review 8, MAJOR 3: an analyst marks a still-blocked payment legitimate (a label only), and a
    // risk officer confirms fraud. That must be a state, DECLINE → DECLINE by SENIOR_OVERRIDE.
    Alert alert = highBlocked();
    Http.Response legit =
        post(
            as("ANALYST"),
            "/api/v1/alerts/" + alert.id + "/decisions",
            decision("MARK_LEGITIMATE", 0),
            UUID.randomUUID());
    String original = legit.json().get("decision_id").asString();
    eventually(
        "the original's effect",
        () ->
            one(
                    "SELECT outcome FROM fraudshield.alert_decision_effects WHERE decision_id ="
                        + " ?::uuid",
                    original)
                != null);
    Http.Response override =
        post(
            as("RISK_OFFICER"),
            "/api/v1/alerts/" + alert.id + "/overrides",
            Map.of(
                "original_decision_id",
                original,
                "new_decision",
                "CONFIRM_FRAUD",
                "reason",
                "Device seen on three other fraud cases",
                "alert_version",
                1),
            UUID.randomUUID());
    assertThat(override.status()).as(override.body()).isEqualTo(201);
    assertThat(OpenApiSchemas.errors("DecisionRecord", override.json())).isEmpty();
    assertThat(override.json().get("override_decision").asString()).isEqualTo("CONFIRM_FRAUD");
    assertThat(
            one(
                "SELECT decision || '/' || supersedes_decision || '/' || decided_by FROM"
                    + " fraudshield.v_decision_states WHERE transaction_id = ? ORDER BY"
                    + " decision_sequence DESC LIMIT 1",
                alert.tx.id))
        .isEqualTo("DECLINE/DECLINE/SENIOR_OVERRIDE");
    published(alert.id, 2, AlertChangePublisher.Change.OVERRIDDEN, true);
  }

  @Test
  void concurrentDecidesOnOneAlertWithDifferentKeysAcceptExactlyOne() throws Exception {
    // P1: the entry lock and the one-live-decision backstop.
    Alert alert = heldNow();
    Session first = as("ANALYST");
    Session second = as("ANALYST");
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<Http.Response> a =
          pool.submit(
              () ->
                  post(
                      first,
                      "/api/v1/alerts/" + alert.id + "/decisions",
                      decision("CONFIRM_FRAUD", 0),
                      UUID.randomUUID()));
      java.util.concurrent.Future<Http.Response> b =
          pool.submit(
              () ->
                  post(
                      second,
                      "/api/v1/alerts/" + alert.id + "/decisions",
                      decision("MARK_LEGITIMATE", 0),
                      UUID.randomUUID()));
      List<Integer> statuses = List.of(a.get().status(), b.get().status());
      assertThat(statuses).containsExactlyInAnyOrder(202, 409);
    } finally {
      pool.shutdown();
    }
    assertThat(
            one(
                "SELECT count(*) FROM fraudshield.alert_decisions WHERE alert_queue_entry_id = ?",
                alert.id))
        .isEqualTo("1");
  }

  @Test
  void operatorResolutionIsAppliedByTheResolverAndEndsTheContest() {
    // P8a, P9: the SQL function authorises and records; the resolver applies it through the owner.
    Alert alert = highBlocked();
    tenantWrite(
        BANK,
        """
        INSERT INTO fraudshield.decision_owners (institution_id, transaction_id, first_event_id,
          first_decision, sequence, decision, decided_by, contested_since)
        SELECT institution_id, transaction_id, event_id, decision, 1, decision, 'MODEL', now()
        FROM fraudshield.decision_states WHERE transaction_id = ?
        """,
        alert.tx.id);
    Account officer = createAccount(BANK, "RISK_OFFICER", "ACTIVE");
    Object resolution =
        query(
            "SELECT fraudshield.resolve_contested_decision(?, 'APPROVE', ?,"
                + " 'Two first decisions; the bank was told DECLINE')",
            alert.tx.id,
            officer.id());
    assertThat(resolution).isNotNull();
    eventually(
        "the resolver",
        () ->
            "APPLIED"
                .equals(
                    one(
                        "SELECT outcome FROM fraudshield.transaction_requests WHERE kind ="
                            + " 'RESOLVE' AND transaction_id = ?",
                        alert.tx.id)));
    assertThat(
            one(
                "SELECT decision || '/' || decided_by || '/' || array_to_string(reason_codes, ',')"
                    + " FROM fraudshield.v_decision_states WHERE transaction_id = ? ORDER BY"
                    + " decision_sequence DESC LIMIT 1",
                alert.tx.id))
        .isEqualTo("APPROVE/SENIOR_OVERRIDE/CONTEST_RESOLVED");
    assertThat(
            one(
                "SELECT contested_since IS NULL FROM fraudshield.decision_owners WHERE"
                    + " transaction_id = ?",
                alert.tx.id))
        .isEqualTo("true");
    assertThat(one("SELECT status FROM fraudshield.alert_queue_entries WHERE id = ?", alert.id))
        .isEqualTo("OVERRIDDEN");
    published(
        alert.id,
        Long.parseLong(
            one("SELECT version FROM fraudshield.alert_queue_entries WHERE id = ?", alert.id)),
        AlertChangePublisher.Change.RESOLVED,
        false);
  }
}
