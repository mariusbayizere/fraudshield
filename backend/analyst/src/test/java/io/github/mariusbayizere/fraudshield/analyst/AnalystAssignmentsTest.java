package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.analyst.decisions.OwnerTestAutoConfiguration;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Alert assignment (ADR 0116) against the real application and database: assign, self-assign and
 * unassign, idempotency scoped to the operation, the fixed order of refusals (A1, A3, A4/A5, A2,
 * A6), PENDING_COMMIT, the narrowed {@code analyst_id} filter, the pool, and {@code
 * listAssignableStaff}'s two modes. Every body is validated against the contract.
 *
 * <p>Tagged with the register's existing rows: UX-ROLE-SN-01 and UX-ROLE-SN-02 are not rows of this
 * branch's register (they are on {@code m8/frontend-rebase}).
 */
@Tag("FR-04-10")
@Tag("FR-04-09")
@Tag("D-29")
class AnalystAssignmentsTest extends AnalystIntegrationTest {

  private static final UUID BANK = DB.createInstitution("analyst-assignments");
  private static final UUID OTHER_BANK = DB.createInstitution("analyst-assignments-other");

  private Session as(String role) {
    return issueSession(createAccount(BANK, role, "ACTIVE"));
  }

  /** An open HIGH entry with no deadline, so no timeout can close it during a test. */
  private static Alert open() {
    Tx tx = new Tx(BANK);
    tx.at = Instant.now();
    tx.riskTier = "HIGH";
    tx.score = 0.95;
    tx.decision = "DECLINE";
    Alert alert = new Alert(tx.write());
    alert.tier = "HIGH";
    return alert.write();
  }

  private static Alert escalatedTo(String target) {
    Alert original = open();
    Alert escalated = new Alert(original.tx);
    escalated.tier = "HIGH";
    escalated.escalatedFrom = original.id;
    escalated.targetRole = target;
    return escalated.write();
  }

  private static Map<String, Object> body(UUID assignee, long version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assignee_user_id", assignee == null ? null : assignee.toString());
    body.put("alert_version", version);
    return body;
  }

  private Http.Response assign(Session session, Alert alert, Object body, UUID key) {
    String[] bearer = session.bearer();
    return http.send(
        "PUT",
        "/api/v1/alerts/" + alert.id + "/assignment",
        body,
        bearer[0],
        bearer[1],
        "Idempotency-Key",
        key.toString());
  }

  private Http.Response assign(Session session, Alert alert, UUID assignee, long version) {
    return assign(session, alert, body(assignee, version), UUID.randomUUID());
  }

  private static String one(String sql, Object... args) {
    Object value = query(sql, args);
    return value == null ? null : value.toString();
  }

  private static void assertProblem(Http.Response response, int status, String type) {
    assertThat(response.status()).as(response.body()).isEqualTo(status);
    assertThat(response.json().get("type").asString()).isEqualTo("urn:fraudshield:problem:" + type);
  }

  private static void assertValidation(Http.Response response, String code) {
    assertProblem(response, 422, "validation");
    assertThat(response.json().get("errors").get(0).get("code").asString()).isEqualTo(code);
  }

  private static void assertSummary(Http.Response response) {
    assertThat(response.status()).as(response.body()).isEqualTo(200);
    assertThat(OpenApiSchemas.errors("AlertSummary", response.json())).isEmpty();
    List<String> problems = new ArrayList<>();
    AnalystInspectorTest.inspect("assignAlert", "", null, response.json(), problems);
    assertThat(problems).isEmpty();
  }

  @Test
  void seniorAssignsReplaysAndReturnsTheEntryToThePool() {
    Alert alert = open();
    final Session senior = as("SENIOR_ANALYST");
    final Account analyst = createAccount(BANK, "ANALYST", "ACTIVE");
    UUID key = UUID.randomUUID();

    Http.Response assigned = assign(senior, alert, body(analyst.id(), 0), key);
    assertSummary(assigned);
    JsonNode summary = assigned.json();
    assertThat(summary.get("version").asLong()).isEqualTo(1);
    assertThat(summary.get("assigned_analyst").get("user_id").asString())
        .isEqualTo(analyst.id().toString());
    assertThat(summary.get("assigned_analyst").get("role").asString()).isEqualTo("ANALYST");
    assertThat(summary.get("status").asString())
        .as("assignment never sets IN_REVIEW")
        .isEqualTo("PENDING");

    Http.Response replay = assign(senior, alert, body(analyst.id(), 0), key);
    assertSummary(replay);
    assertThat(replay.json().get("version").asLong()).as("a replay never trips A5").isEqualTo(1);
    assertProblem(assign(senior, alert, body(null, 0), key), 409, "idempotency-conflict");
    assertProblem(
        assign(as("SENIOR_ANALYST"), alert, body(analyst.id(), 0), key),
        409,
        "idempotency-conflict");

    Http.Response unassigned = assign(senior, alert, null, 1);
    assertSummary(unassigned);
    assertThat(unassigned.json().get("assigned_analyst").isNull()).isTrue();
    assertThat(unassigned.json().get("version").asLong()).isEqualTo(2);
    assertValidation(assign(senior, alert, null, 2), "no_change");

    assertThat(
            one(
                "SELECT count(*) || '/' || count(assignee_id) FROM fraudshield.alert_assignments"
                    + " WHERE alert_queue_entry_id = ?",
                alert.id))
        .isEqualTo("2/1");
    assertThat(
            one(
                "SELECT (assigned_analyst_id IS NULL AND assigned_at IS NULL)::text FROM"
                    + " fraudshield.alert_queue_entries WHERE id = ?",
                alert.id))
        .isEqualTo("true");
    assertThat(auditActions(alert.id))
        .containsExactly("ANALYST_DECISION/ASSIGNED", "ANALYST_DECISION/UNASSIGNED");
    assertThat(
            OwnerTestAutoConfiguration.CHANGES.offered.stream()
                .filter(o -> o.alertId().equals(alert.id))
                .map(o -> o.change() + "/" + o.version() + "/" + o.institutionId().equals(BANK))
                .toList())
        .as("each applied assignment is published once, and a replay or no_change never is")
        .containsExactly("ASSIGNED/1/true", "ASSIGNED/2/true");
  }

  @Test
  void selfAssignmentFromThePoolAndRefusedRequestsRecordNoKey() {
    Alert alert = open();
    final Account seniorAccount = createAccount(BANK, "SENIOR_ANALYST", "ACTIVE");
    final Session senior = issueSession(seniorAccount);
    UUID key = UUID.randomUUID();
    assertProblem(assign(senior, alert, body(seniorAccount.id(), 5), key), 409, "stale-alert");
    Http.Response self = assign(senior, alert, body(seniorAccount.id(), 0), key);
    assertSummary(self);
    assertThat(self.json().get("assigned_analyst").get("user_id").asString())
        .isEqualTo(seniorAccount.id().toString());
  }

  @Test
  void staleVersionAnswersTheCurrentAlert() {
    Alert alert = open();
    Http.Response stale =
        assign(as("SENIOR_ANALYST"), alert, createAccount(BANK, "ANALYST", "ACTIVE").id(), 3);
    assertProblem(stale, 409, "stale-alert");
    assertThat(OpenApiSchemas.errors("StaleAlertProblem", stale.json())).isEmpty();
    assertThat(stale.json().get("current").get("alert_id").asString())
        .isEqualTo(alert.id.toString());
    assertThat(stale.json().get("current").has("assigned_analyst")).isTrue();
  }

  @Test
  void rankRulesRefuseInTheirFixedOrder() {
    final Session senior = as("SENIOR_ANALYST");
    final Account officerAccount = createAccount(BANK, "RISK_OFFICER", "ACTIVE");
    final Session officer = issueSession(officerAccount);
    final Account analyst = createAccount(BANK, "ANALYST", "ACTIVE");

    // A2's upper bound: no assigning up.
    Alert alert = open();
    assertValidation(assign(senior, alert, officerAccount.id(), 0), "assignee_not_eligible");
    // A2: not on duty, unknown, another institution's, ADMIN: one answer for all.
    assertValidation(
        assign(senior, alert, createAccount(BANK, "ANALYST", "DEACTIVATED").id(), 0),
        "assignee_not_eligible");
    assertValidation(assign(senior, alert, UUID.randomUUID(), 0), "assignee_not_eligible");
    assertValidation(
        assign(senior, alert, createAccount(OTHER_BANK, "ANALYST", "ACTIVE").id(), 0),
        "assignee_not_eligible");
    assertValidation(
        assign(officer, alert, createAccount(BANK, "ADMIN", "ACTIVE").id(), 0),
        "assignee_not_eligible");
    // LOCKED is on duty (ASSUMED).
    assertSummary(assign(senior, alert, createAccount(BANK, "ANALYST", "LOCKED").id(), 0));

    // A3: a risk officer's self-assignment is not the senior's to take back.
    Alert held = open();
    assertSummary(assign(officer, held, officerAccount.id(), 0));
    assertProblem(assign(senior, held, analyst.id(), 1), 403, "assignment-held-by-higher-role");
    assertProblem(assign(senior, held, null, 1), 403, "assignment-held-by-higher-role");
    // A3 comes before A5: a stale version still answers 403.
    assertProblem(assign(senior, held, null, 0), 403, "assignment-held-by-higher-role");
    // A deactivated holder holds nothing.
    superuser(
        "UPDATE fraudshield.users SET status = 'DEACTIVATED' WHERE id = ?", officerAccount.id());
    assertSummary(assign(senior, held, analyst.id(), 1));

    // A1, before A5: an entry escalated to RISK_OFFICER is not the senior's.
    Alert toOfficer = escalatedTo("RISK_OFFICER");
    assertProblem(assign(senior, toOfficer, null, 7), 403, "escalation-level-required");
    // A2 for an escalated entry: at or above its target.
    Alert toSenior = escalatedTo("SENIOR_ANALYST");
    final Session otherOfficer = as("RISK_OFFICER");
    assertValidation(assign(otherOfficer, toSenior, analyst.id(), 0), "assignee_not_eligible");
    assertSummary(
        assign(otherOfficer, toSenior, createAccount(BANK, "SENIOR_ANALYST", "ACTIVE").id(), 0));

    // Roles: an analyst cannot assign, not even themselves.
    final Session analystSession = issueSession(analyst);
    assertProblem(assign(analystSession, open(), analyst.id(), 0), 403, "forbidden");
  }

  @Test
  void pendingCommitEntryIsNotOpenAndAnUndoKeepsItsAssignment() {
    Alert alert = open();
    final Session senior = as("SENIOR_ANALYST");
    final Account analystAccount = createAccount(BANK, "ANALYST", "ACTIVE");
    final Session analyst = issueSession(analystAccount);
    assertSummary(assign(senior, alert, analystAccount.id(), 0));

    String[] bearer = analyst.bearer();
    Http.Response decided =
        http.post(
            "/api/v1/alerts/" + alert.id + "/decisions",
            Map.of(
                "decision",
                "MARK_LEGITIMATE",
                "comment",
                "Known customer, verified by phone",
                "alert_version",
                1),
            bearer[0],
            bearer[1],
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(decided.status()).as(decided.body()).isEqualTo(202);
    String claim =
        one(
            "SELECT claim || '/' || coalesce(claim_decision_id::text, '') || '/' || version FROM"
                + " fraudshield.decision_owners WHERE transaction_id = ?",
            alert.tx.id);

    Http.Response refused = assign(senior, alert, null, 2);
    assertProblem(refused, 409, "stale-alert");
    assertThat(refused.json().get("current").get("status").asString())
        .isEqualTo("MARKED_LEGITIMATE");
    assertThat(
            one(
                "SELECT claim || '/' || coalesce(claim_decision_id::text, '') || '/' || version"
                    + " FROM fraudshield.decision_owners WHERE transaction_id = ?",
                alert.tx.id))
        .as("assignment never touches the owner")
        .isEqualTo(claim);

    Http.Response undone =
        http.post(
            "/api/v1/alerts/"
                + alert.id
                + "/decisions/"
                + decided.json().get("decision_id").asString()
                + "/undo",
            Map.of(),
            bearer);
    assertThat(undone.status()).as(undone.body()).isEqualTo(204);
    Http.Response detail = http.get("/api/v1/alerts/" + alert.id, bearer);
    assertThat(OpenApiSchemas.errors("AlertDetail", detail.json())).isEmpty();
    assertThat(detail.json().get("version").asLong()).isEqualTo(3);
    assertThat(detail.json().get("assigned_analyst").get("user_id").asString())
        .isEqualTo(analystAccount.id().toString());
    assertSummary(assign(senior, alert, null, 3));
  }

  @Test
  @Tag("FR-04-02")
  void analystFilterMatchesAssignmentAndLiveDecisionsOnlyAndThePoolIsFiltered() {
    // ADR 0116 point 7: fails against P1's query, which matched an undone decision too.
    Alert alert = open();
    final Account first = createAccount(BANK, "ANALYST", "ACTIVE");
    final Account second = createAccount(BANK, "ANALYST", "ACTIVE");
    final Session firstSession = issueSession(first);
    String[] bearer = firstSession.bearer();
    Http.Response decided =
        http.post(
            "/api/v1/alerts/" + alert.id + "/decisions",
            Map.of(
                "decision", "CONFIRM_FRAUD", "comment", "Device seen before", "alert_version", 0),
            bearer[0],
            bearer[1],
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(decided.status()).as(decided.body()).isEqualTo(202);
    Http.Response undone =
        http.post(
            "/api/v1/alerts/"
                + alert.id
                + "/decisions/"
                + decided.json().get("decision_id").asString()
                + "/undo",
            Map.of(),
            bearer);
    assertThat(undone.status()).as(undone.body()).isEqualTo(204);
    assertSummary(assign(as("SENIOR_ANALYST"), alert, second.id(), 2));

    assertThat(listed("analyst_id=" + first.id(), bearer)).doesNotContain(alert.id.toString());
    assertThat(listed("analyst_id=" + second.id(), bearer)).contains(alert.id.toString());
    assertThat(listed("analyst_id=" + second.id() + "&assignment=assigned", bearer))
        .contains(alert.id.toString());
    assertThat(listed("assignment=assigned", bearer)).contains(alert.id.toString());
    assertThat(listed("assignment=unassigned", bearer)).doesNotContain(alert.id.toString());

    Alert pooled = open();
    assertThat(listed("assignment=unassigned", bearer)).contains(pooled.id.toString());
    assertValidation(
        http.get("/api/v1/alerts?queue=all&assignment=unassigned&analyst_id=" + first.id(), bearer),
        "unsupported_value");
    assertValidation(http.get("/api/v1/alerts?assignment=nobody", bearer), "unsupported_value");
  }

  private List<String> listed(String query, String... bearer) {
    Http.Response page = http.get("/api/v1/alerts?queue=all&limit=200&" + query, bearer);
    assertThat(page.status()).as(page.body()).isEqualTo(200);
    assertThat(OpenApiSchemas.errors("AlertPage", page.json())).isEmpty();
    List<String> ids = new ArrayList<>();
    page.json().get("items").forEach(item -> ids.add(item.get("alert_id").asString()));
    return ids;
  }

  @Test
  void assignableStaffListsTheDropdownAndTheFilterWithoutEmail() {
    UUID bank = DB.createInstitution("analyst-assignees");
    final Account senior = createAccount(bank, "SENIOR_ANALYST", "ACTIVE");
    final Account officer = createAccount(bank, "RISK_OFFICER", "ACTIVE");
    final Account analyst = createAccount(bank, "ANALYST", "ACTIVE");
    final Account locked = createAccount(bank, "ANALYST", "LOCKED");
    final Account gone = createAccount(bank, "ANALYST", "DEACTIVATED");
    createAccount(bank, "ADMIN", "ACTIVE");
    createAccount(OTHER_BANK, "ANALYST", "ACTIVE");
    final Session seniorSession = issueSession(senior);

    Http.Response assignable = http.get("/api/v1/team/assignees", seniorSession.bearer());
    assertThat(assignable.status()).as(assignable.body()).isEqualTo(200);
    assertThat(OpenApiSchemas.errors("AssignableStaffList", assignable.json())).isEmpty();
    assertThat(ids(assignable.json()))
        .containsExactlyInAnyOrder(
            senior.id().toString(), analyst.id().toString(), locked.id().toString());
    assignable.json().get("items").forEach(item -> assertThat(item.has("status")).isFalse());

    Http.Response filter =
        http.get("/api/v1/team/assignees?purpose=filter", seniorSession.bearer());
    assertThat(filter.status()).as(filter.body()).isEqualTo(200);
    assertThat(OpenApiSchemas.errors("AssignableStaffList", filter.json())).isEmpty();
    assertThat(ids(filter.json()))
        .containsExactlyInAnyOrder(
            senior.id().toString(),
            officer.id().toString(),
            analyst.id().toString(),
            locked.id().toString(),
            gone.id().toString());
    filter.json().get("items").forEach(item -> assertThat(item.has("status")).isTrue());
    for (Http.Response response : List.of(assignable, filter)) {
      assertThat(response.body()).doesNotContain("@");
      List<String> problems = new ArrayList<>();
      AnalystInspectorTest.inspect("listAssignableStaff", "", null, response.json(), problems);
      assertThat(problems).isEmpty();
    }

    assertValidation(
        http.get(
            "/api/v1/team/assignees?purpose=filter&alert_id=" + UUID.randomUUID(),
            seniorSession.bearer()),
        "unsupported_value");
    assertValidation(
        http.get("/api/v1/team/assignees?purpose=everyone", seniorSession.bearer()),
        "unsupported_value");
    assertProblem(
        http.get("/api/v1/team/assignees?alert_id=" + UUID.randomUUID(), seniorSession.bearer()),
        404,
        "not-found");
    assertProblem(
        http.get("/api/v1/team/assignees", issueSession(analyst).bearer()), 403, "forbidden");
  }

  @Test
  void assignableStaffForAnEscalatedEntryStartsAtItsTarget() {
    Alert toOfficer = escalatedTo("RISK_OFFICER");
    final Account officer = createAccount(BANK, "RISK_OFFICER", "ACTIVE");
    Http.Response response =
        http.get("/api/v1/team/assignees?alert_id=" + toOfficer.id, issueSession(officer).bearer());
    assertThat(response.status()).as(response.body()).isEqualTo(200);
    response
        .json()
        .get("items")
        .forEach(item -> assertThat(item.get("role").asString()).isEqualTo("RISK_OFFICER"));
    assertThat(ids(response.json())).contains(officer.id().toString());
  }

  private static List<String> ids(JsonNode list) {
    List<String> ids = new ArrayList<>();
    list.get("items").forEach(item -> ids.add(item.get("user_id").asString()));
    return ids;
  }
}
