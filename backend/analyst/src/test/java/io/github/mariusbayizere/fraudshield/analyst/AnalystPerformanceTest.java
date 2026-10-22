package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.analyst.performance.InstitutionDay;
import io.github.mariusbayizere.fraudshield.analyst.performance.Presence;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * P2's figures against the real application and database (ADR 0117): the header statistics, the
 * caller's own performance and the team table with its CSV. Every body is validated against the
 * contract's schema.
 */
class AnalystPerformanceTest extends AnalystIntegrationTest {

  private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired private StringRedisTemplate redis;

  // --- getDashboardStats ------------------------------------------------------------------------

  @Test
  @Tag("UX-DASH-01")
  @Tag("FR-04-11")
  void theHeaderFiguresAreDefinedAndCarryTheInstitutionsDay() {
    final UUID bank = DB.createInstitution("figures-header");
    final UUID analyst = createAccount(bank, "ANALYST", "ACTIVE").id();
    entry(bank, "HIGH", "PENDING", null, Instant.now());
    entry(bank, "MEDIUM", "IN_REVIEW", null, Instant.now());
    final UUID escalatedFrom = entry(bank, "MEDIUM", "ESCALATED", null, Instant.now());
    escalated(bank, escalatedFrom);
    entry(bank, "ANOMALY", "PENDING", null, Instant.now());
    review(
        bank,
        entry(bank, "MEDIUM", "CONFIRMED_FRAUD", null, Instant.now().minusSeconds(120)),
        analyst,
        "CONFIRM_FRAUD");
    review(
        bank,
        entry(bank, "ANOMALY", "MARKED_LEGITIMATE", null, Instant.now().minusSeconds(9000)),
        analyst,
        "MARK_LEGITIMATE");
    final UUID undone =
        review(
            bank,
            entry(bank, "HIGH", "PENDING", null, Instant.now().minusSeconds(60)),
            analyst,
            "CONFIRM_FRAUD");
    tenantWrite(
        bank,
        "INSERT INTO fraudshield.alert_decision_undos (decision_id, institution_id, undone_by)"
            + " VALUES (?, ?, ?)",
        undone,
        bank,
        analyst);
    autoBlock(bank, UUID.randomUUID(), Instant.now().minusSeconds(5));
    final Tx confirmed = declined(bank, "FRAUD");
    declined(bank, "LEGITIMATE");
    redis
        .opsForZSet()
        .add(
            Presence.key(bank),
            UUID.randomUUID().toString(),
            Instant.now().toEpochMilli() + 30_000);
    redis
        .opsForZSet()
        .add(
            Presence.key(bank),
            UUID.randomUUID().toString(),
            Instant.now().toEpochMilli() - 60_000);

    JsonNode stats =
        get(
            issueSession(createAccount(bank, "ANALYST", "ACTIVE")),
            "/api/v1/dashboard/stats",
            "DashboardStats");

    InstitutionDay today = InstitutionDay.of(KIGALI, Instant.now());
    assertThat(stats.get("high_open").asLong())
        .as("the undone review reopened nothing here")
        .isEqualTo(2);
    assertThat(stats.get("medium_open").asLong())
        .as("the escalated entry is not counted")
        .isEqualTo(1);
    assertThat(stats.get("auto_blocked_today").asLong()).isEqualTo(3);
    assertThat(stats.get("reviewed_today").asLong())
        .as("anomaly review in, undone out")
        .isEqualTo(2);
    assertThat(stats.get("average_seconds_to_decision").asDouble())
        .as("HIGH and MEDIUM only")
        .isBetween(120.0, 150.0);
    assertThat(stats.get("fraud_prevented_today").get("amount").asString())
        .isEqualTo(confirmed.amountRwf.toPlainString());
    assertThat(stats.get("fraud_prevented_today").get("currency").asString()).isEqualTo("RWF");
    assertThat(stats.get("analysts_online").asInt()).as("the expired entry is pruned").isEqualTo(1);
    assertThat(redis.opsForZSet().size(Presence.key(bank))).isEqualTo(1);
    assertThat(stats.get("day_starts_at").asString()).isEqualTo(today.start().toString());
    assertThat(stats.get("utc_offset").asString()).isEqualTo("+02:00");
    assertThat(stats.get("time_zone").asString()).isEqualTo("Africa/Kigali");
  }

  @Test
  @Tag("UX-DASH-01")
  void withoutReviewsTheAverageIsNullNotZero() {
    final UUID bank = DB.createInstitution("figures-empty");

    JsonNode stats =
        get(
            issueSession(createAccount(bank, "SENIOR_ANALYST", "ACTIVE")),
            "/api/v1/dashboard/stats",
            "DashboardStats");

    assertThat(stats.get("reviewed_today").asLong()).isZero();
    assertThat(stats.get("average_seconds_to_decision").isNull()).isTrue();
    assertThat(stats.get("analysts_online").asInt()).isZero();
    assertThat(stats.get("fraud_prevented_today").get("amount").asString()).isEqualTo("0");
  }

  // --- getMyPerformance -------------------------------------------------------------------------

  @Test
  @Tag("FR-04-11")
  void myPerformanceCountsAgreementAfterTheDecisionAndTheDailyTrend() {
    final UUID bank = DB.createInstitution("figures-mine");
    final UUID analyst = createAccount(bank, "ANALYST", "ACTIVE").id();
    final UUID officer = createAccount(bank, "RISK_OFFICER", "ACTIVE").id();
    List<UUID> reviews = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      // WAS_ME after the decision: 15 agree (MARK_LEGITIMATE), 5 are false positives.
      reviews.add(verified(bank, analyst, i < 15 ? "MARK_LEGITIMATE" : "CONFIRM_FRAUD", "WAS_ME"));
    }
    verified(bank, analyst, "CONFIRM_FRAUD", "NOT_ME");
    answeredBefore(bank, analyst);
    override(bank, reviews.get(0), officer, "CONFIRM_FRAUD");
    override(bank, reviews.get(1), officer, "MARK_LEGITIMATE");
    entry(bank, "MEDIUM", "PENDING", analyst, Instant.now());
    final LocalDate threeDaysAgo = LocalDate.now(KIGALI).minusDays(3);
    backdated(
        bank,
        entry(bank, "HIGH", "CONFIRMED_FRAUD", null, Instant.now().minusSeconds(4 * 86_400)),
        analyst,
        threeDaysAgo.atTime(LocalTime.of(10, 0)).atZone(KIGALI).toInstant(),
        300);

    JsonNode mine =
        get(
            issueSession(new Account(analyst, bank, null, "ANALYST", "ACTIVE")),
            "/api/v1/me/performance",
            "AnalystPerformance");

    assertThat(mine.get("reviewed_today").asLong()).isEqualTo(22);
    assertThat(mine.get("backlog").asLong()).isEqualTo(1);
    assertThat(mine.get("verified_decisions").asLong())
        .as("the answer given before the decision is not counted")
        .isEqualTo(21);
    assertThat(mine.get("customer_agreement_rate").asDouble()).isEqualTo(16.0 / 21);
    assertThat(mine.get("verified_legitimate_decisions").asLong()).isEqualTo(20);
    assertThat(mine.get("false_positive_rate").asDouble()).isEqualTo(0.25);
    assertThat(mine.get("overturned_decisions").asLong())
        .as("a same-value override is not an overturn")
        .isEqualTo(1);
    JsonNode daily = mine.get("daily");
    assertThat(daily).hasSize(30);
    assertThat(daily.get(29).get("date").asString()).isEqualTo(LocalDate.now(KIGALI).toString());
    assertThat(daily.get(29).get("reviews").asLong()).isEqualTo(22);
    assertThat(daily.get(26).get("date").asString()).isEqualTo(threeDaysAgo.toString());
    assertThat(daily.get(26).get("reviews").asLong()).isEqualTo(1);
    assertThat(daily.get(26).get("average_seconds_to_decision").asDouble()).isEqualTo(300.0);
    assertThat(daily.get(0).get("average_seconds_to_decision").isNull()).isTrue();
  }

  @Test
  @Tag("FR-04-11")
  void belowTwentyVerifiedReviewsTheRatesAreNullBesideTheirCounts() {
    final UUID bank = DB.createInstitution("figures-few");
    final UUID analyst = createAccount(bank, "ANALYST", "ACTIVE").id();
    verified(bank, analyst, "CONFIRM_FRAUD", "NOT_ME");

    JsonNode mine =
        get(
            issueSession(new Account(analyst, bank, null, "ANALYST", "ACTIVE")),
            "/api/v1/me/performance",
            "AnalystPerformance");

    assertThat(mine.get("verified_decisions").asLong()).isEqualTo(1);
    assertThat(mine.get("customer_agreement_rate").isNull()).isTrue();
    assertThat(mine.get("false_positive_rate").isNull()).isTrue();
  }

  // --- getTeamPerformance -----------------------------------------------------------------------

  @Test
  @Tag("FR-04-11")
  void seniorsSeeAnalystAndSeniorRowsAndRiskOfficersSeeAllThree() {
    final UUID bank = DB.createInstitution("figures-team");
    final UUID analyst = createAccount(bank, "ANALYST", "ACTIVE").id();
    final UUID senior = createAccount(bank, "SENIOR_ANALYST", "ACTIVE").id();
    final UUID officer = createAccount(bank, "RISK_OFFICER", "ACTIVE").id();
    final UUID locked = createAccount(bank, "ANALYST", "LOCKED").id();
    final UUID departed = createAccount(bank, "ANALYST", "DEACTIVATED").id();
    final UUID idle = createAccount(bank, "ANALYST", "DEACTIVATED").id();
    createAccount(bank, "ADMIN", "ACTIVE");
    final UUID first =
        review(
            bank,
            entry(bank, "MEDIUM", "CONFIRMED_FRAUD", null, Instant.now().minusSeconds(60)),
            analyst,
            "CONFIRM_FRAUD");
    review(
        bank,
        entry(bank, "HIGH", "MARKED_LEGITIMATE", null, Instant.now().minusSeconds(30)),
        analyst,
        "MARK_LEGITIMATE");
    review(
        bank,
        entry(bank, "MEDIUM", "CONFIRMED_FRAUD", null, Instant.now()),
        departed,
        "CONFIRM_FRAUD");
    escalation(bank, analyst);
    override(bank, first, officer, "MARK_LEGITIMATE");

    JsonNode seniors =
        get(
            issueSession(new Account(senior, bank, null, "SENIOR_ANALYST", "ACTIVE")),
            "/api/v1/team/performance",
            "TeamPerformance");
    JsonNode officers =
        get(
            issueSession(new Account(officer, bank, null, "RISK_OFFICER", "ACTIVE")),
            "/api/v1/team/performance",
            "TeamPerformance");

    assertThat(users(seniors)).containsExactlyInAnyOrder(analyst, senior, locked, departed);
    assertThat(users(officers))
        .containsExactlyInAnyOrder(analyst, senior, officer, locked, departed)
        .doesNotContain(idle);
    JsonNode row = row(officers, analyst);
    assertThat(row.get("reviews").asLong()).isEqualTo(2);
    assertThat(row.get("active_hours").asLong()).isBetween(1L, 2L);
    assertThat(row.get("escalations").asLong()).isEqualTo(1);
    assertThat(row.get("overturned_decisions").asLong()).isEqualTo(1);
    assertThat(row.get("overrides_made").isNull()).isTrue();
    assertThat(row.get("average_seconds_from_assignment").isNull()).isTrue();
    assertThat(row(officers, officer).get("overrides_made").asLong()).isEqualTo(1);
    assertThat(row(officers, officer).get("reviews").asLong()).isZero();
    final String today = LocalDate.now(KIGALI).toString();
    assertThat(officers.get("from").asString()).isEqualTo(today);
    assertThat(officers.get("to").asString()).isEqualTo(today);
    assertThat(officers.get("agreement_from").asString())
        .isEqualTo(LocalDate.now(KIGALI).minusDays(29).toString());
  }

  @Test
  @Tag("FR-04-11")
  void timeFromAssignmentStartsAtTheEarliestAssignmentByAnotherUser() throws Exception {
    // ADR 0117 point 3 (code review 2, MINOR 13): one analyst per rule. assigned_at is the
    // database's clock_timestamp(), so the gaps are made with real sleeps.
    final UUID bank = DB.createInstitution("figures-assignment");
    final UUID senior = createAccount(bank, "SENIOR_ANALYST", "ACTIVE").id();
    final UUID earliest = createAccount(bank, "ANALYST", "ACTIVE").id();
    final UUID other = createAccount(bank, "ANALYST", "ACTIVE").id();
    final UUID self = createAccount(bank, "SENIOR_ANALYST", "ACTIVE").id();
    final UUID late = createAccount(bank, "ANALYST", "ACTIVE").id();
    final UUID anomaly = createAccount(bank, "ANALYST", "ACTIVE").id();
    final UUID undone = createAccount(bank, "ANALYST", "ACTIVE").id();

    UUID reassigned = entry(bank, "HIGH", "PENDING", null, Instant.now());
    assigned(bank, reassigned, null, earliest, senior);
    Thread.sleep(1_200);
    assigned(bank, reassigned, earliest, other, senior);
    assigned(bank, reassigned, other, earliest, senior);
    Thread.sleep(1_200);
    review(bank, reassigned, earliest, "CONFIRM_FRAUD");

    UUID selfAssigned = entry(bank, "HIGH", "PENDING", null, Instant.now());
    assigned(bank, selfAssigned, null, self, self);
    review(bank, selfAssigned, self, "MARK_LEGITIMATE");

    UUID afterwards = entry(bank, "MEDIUM", "PENDING", null, Instant.now());
    review(bank, afterwards, late, "CONFIRM_FRAUD");
    assigned(bank, afterwards, null, late, senior);

    UUID anomalous = entry(bank, "ANOMALY", "PENDING", null, Instant.now());
    assigned(bank, anomalous, null, anomaly, senior);
    review(bank, anomalous, anomaly, "CONFIRM_FRAUD");

    UUID withdrawn = entry(bank, "HIGH", "PENDING", null, Instant.now());
    assigned(bank, withdrawn, null, undone, senior);
    UUID decision = review(bank, withdrawn, undone, "CONFIRM_FRAUD");
    tenantWrite(
        bank,
        "INSERT INTO fraudshield.alert_decision_undos (decision_id, institution_id, undone_by)"
            + " VALUES (?, ?, ?)",
        decision,
        bank,
        undone);

    JsonNode team =
        get(
            issueSession(new Account(senior, bank, null, "SENIOR_ANALYST", "ACTIVE")),
            "/api/v1/team/performance",
            "TeamPerformance");
    assertThat(row(team, earliest).get("average_seconds_from_assignment").asDouble())
        .as("from the earliest assignment, not the latest")
        .isGreaterThan(2.0);
    for (UUID none : List.of(self, late, anomaly, undone)) {
      assertThat(row(team, none).get("average_seconds_from_assignment").isNull())
          .as("self-assigned, assigned after, ANOMALY, or undone")
          .isTrue();
    }
  }

  private static void assigned(UUID bank, UUID entry, UUID previous, UUID assignee, UUID by) {
    byte[] fingerprint = new byte[32];
    RANDOM.nextBytes(fingerprint);
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_assignments (institution_id, alert_queue_entry_id,
          previous_assignee_id, assignee_id, assigned_by, idempotency_key, request_fingerprint)
        VALUES (?, ?, ?, ?, ?, gen_random_uuid(), ?)
        """,
        bank,
        entry,
        previous,
        assignee,
        by,
        fingerprint);
  }

  @Test
  @Tag("FR-04-11")
  void theRangeIsValidatedAndOnlySeniorsAndRiskOfficersMayCall() {
    final UUID bank = DB.createInstitution("figures-range");
    Http.Response analyst =
        http.get(
            "/api/v1/team/performance",
            issueSession(createAccount(bank, "ANALYST", "ACTIVE")).bearer());
    assertThat(analyst.status()).isEqualTo(403);

    final Session senior = issueSession(createAccount(bank, "SENIOR_ANALYST", "ACTIVE"));
    final LocalDate today = LocalDate.now(KIGALI);
    for (String query :
        List.of(
            "?to=" + today.plusDays(1),
            "?from=" + today + "&to=" + today.minusDays(1),
            "?from=" + today.minusDays(31) + "&to=" + today,
            "?from=yesterday")) {
      Http.Response refused = http.get("/api/v1/team/performance" + query, senior.bearer());
      assertThat(refused.status()).as(query).isEqualTo(422);
      assertThat(refused.problemType()).isEqualTo("urn:fraudshield:problem:validation");
      assertThat(OpenApiSchemas.errors("ValidationProblem", refused.json())).isEmpty();
    }
    JsonNode longest =
        get(
            senior,
            "/api/v1/team/performance?from=" + today.minusDays(30) + "&to=" + today,
            "TeamPerformance");
    assertThat(longest.get("from").asString()).isEqualTo(today.minusDays(30).toString());

    Http.Response xml =
        http.get("/api/v1/team/performance", concat(senior.bearer(), "Accept", "application/xml"));
    assertThat(xml.status()).isEqualTo(406);
  }

  @Test
  @Tag("FR-04-11")
  void theCsvIsAuditedBeforeItIsSentAndCarriesOnlyItsColumns() {
    final UUID bank = DB.createInstitution("figures-csv");
    final UUID analyst = createAccount(bank, "ANALYST", "ACTIVE").id();
    superuser(
        "UPDATE fraudshield.users SET first_name = '=cmd', last_name = 'Uwase, Jr' WHERE id = ?",
        analyst);
    final Session senior = issueSession(createAccount(bank, "SENIOR_ANALYST", "ACTIVE"));

    Http.Response csv =
        http.get("/api/v1/team/performance", concat(senior.bearer(), "Accept", "text/csv"));

    assertThat(csv.status()).as(csv.body()).isEqualTo(200);
    MediaType type = MediaType.parseMediaType(csv.header("Content-Type").orElseThrow());
    assertThat(type.isCompatibleWith(MediaType.parseMediaType("text/csv"))).isTrue();
    assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(type.getParameter("header")).isEqualTo("present");
    final String today = LocalDate.now(KIGALI).toString();
    assertThat(csv.header("Content-Disposition").orElseThrow())
        .contains("attachment")
        .contains("team-performance_" + today + "_" + today + ".csv");
    assertThat(csv.body()).startsWith("﻿user_id,first_name,last_name,role,from,to,");
    String[] lines = csv.body().substring(1).split("\r\n");
    assertThat(lines).hasSize(3);
    assertThat(lines[0]).doesNotContain("escalations").doesNotContain("overrides_made");
    assertThat(csv.body()).contains(analyst + ",'=cmd,\"Uwase, Jr\",ANALYST," + today + ",");
    assertThat(csv.body().getBytes(StandardCharsets.UTF_8).length).isLessThan(1_048_576);
    assertThat(
            query(
                "SELECT count(*) FROM fraudshield.audit_events WHERE institution_id = ?"
                    + " AND event_type = 'USER_ADMIN' AND action = 'TEAM_PERFORMANCE_EXPORTED'"
                    + " AND after_value ->> 'rows' = '2' AND after_value ->> 'time_zone' ="
                    + " 'Africa/Kigali'",
                bank))
        .isEqualTo(1L);

    get(senior, "/api/v1/team/performance", "TeamPerformance");
    assertThat(
            query(
                "SELECT count(*) FROM fraudshield.audit_events WHERE institution_id = ?"
                    + " AND action = 'TEAM_PERFORMANCE_EXPORTED'",
                bank))
        .as("the JSON variant is not audited")
        .isEqualTo(1L);
  }

  @Test
  @Tag("FR-04-11")
  void moreThanOneThousandRowsAreRefusedNeverTruncated() {
    final UUID bank = DB.createInstitution("figures-large");
    superuser(
        """
        INSERT INTO fraudshield.users (institution_id, first_name, last_name, email, role, status,
          employee_id, department, password_hash)
        SELECT ?, 'Amani', 'Uwase', 'bulk' || g || '-' || ? || '@example.com', 'ANALYST',
          'ACTIVE', 'BLK' || g, 'FRAUD_OPERATIONS',
          '$2b$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzabcd'
        FROM generate_series(1, 1001) g
        """,
        bank,
        Long.toHexString(RANDOM.nextLong()));

    Http.Response refused =
        http.get(
            "/api/v1/team/performance",
            issueSession(createAccount(bank, "RISK_OFFICER", "ACTIVE")).bearer());

    assertThat(refused.status()).isEqualTo(422);
    assertThat(refused.problemType()).isEqualTo("urn:fraudshield:problem:result-too-large");
    assertThat(OpenApiSchemas.errors("ResultTooLargeProblem", refused.json())).isEmpty();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private JsonNode get(Session session, String path, String schema) {
    Http.Response response = http.get(path, session.bearer());
    assertThat(response.status()).as(path + " " + response.body()).isEqualTo(200);
    JsonNode body = response.json();
    assertThat(OpenApiSchemas.errors(schema, body)).as(path).isEmpty();
    return body;
  }

  private static String[] concat(String[] headers, String name, String value) {
    String[] all = java.util.Arrays.copyOf(headers, headers.length + 2);
    all[headers.length] = name;
    all[headers.length + 1] = value;
    return all;
  }

  private static List<UUID> users(JsonNode team) {
    List<UUID> users = new ArrayList<>();
    for (JsonNode row : team.get("analysts")) {
      users.add(UUID.fromString(row.get("user_id").asString()));
    }
    return users;
  }

  private static JsonNode row(JsonNode team, UUID user) {
    for (JsonNode row : team.get("analysts")) {
      if (row.get("user_id").asString().equals(user.toString())) {
        return row;
      }
    }
    throw new AssertionError("no row for " + user);
  }

  private static UUID entry(
      UUID bank, String tier, String status, UUID assignee, Instant createdAt) {
    final UUID id = UUID.randomUUID();
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_queue_entries (id, institution_id, transaction_id,
          fraud_score_id, tier, status, fraud_probability, review_deadline_at,
          assigned_analyst_id, assigned_at, created_at)
        VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), ?, ?, 0.9, ?, ?, ?, ?)
        """,
        id,
        bank,
        tier,
        status,
        "MEDIUM".equals(tier) ? Timestamp.from(createdAt.plusSeconds(30)) : null,
        assignee,
        assignee == null ? null : Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }

  private static void escalated(UUID bank, UUID from) {
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_queue_entries (institution_id, transaction_id,
          fraud_score_id, tier, status, fraud_probability, escalated_from_alert_id,
          escalation_target_role, escalation_reason)
        VALUES (?, gen_random_uuid(), gen_random_uuid(), 'MEDIUM', 'PENDING', 0.9, ?,
          'SENIOR_ANALYST', 'Needs a senior view')
        """,
        bank,
        from);
  }

  private static UUID review(UUID bank, UUID entry, UUID analyst, String decision) {
    final UUID id = UUID.randomUUID();
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_decisions (id, institution_id, alert_queue_entry_id,
          analyst_id, decision, analyst_comment, idempotency_key)
        VALUES (?, ?, ?, ?, ?, 'Reviewed the account history', gen_random_uuid())
        """,
        id,
        bank,
        entry,
        analyst,
        decision);
    return id;
  }

  private static void escalation(UUID bank, UUID analyst) {
    final UUID from = entry(bank, "MEDIUM", "ESCALATED", null, Instant.now());
    final UUID to = UUID.randomUUID();
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_queue_entries (id, institution_id, transaction_id,
          fraud_score_id, tier, status, fraud_probability, escalated_from_alert_id,
          escalation_target_role, escalation_reason)
        VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), 'MEDIUM', 'PENDING', 0.9, ?,
          'SENIOR_ANALYST', 'Needs a senior view')
        """,
        to,
        bank,
        from);
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_decisions (institution_id, alert_queue_entry_id, analyst_id,
          decision, analyst_comment, idempotency_key, escalated_to_alert_id)
        VALUES (?, ?, ?, 'ESCALATE', 'Needs a senior view', gen_random_uuid(), ?)
        """,
        bank,
        from,
        analyst,
        to);
  }

  private static void override(UUID bank, UUID review, UUID officer, String decision) {
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.alert_decisions (institution_id, alert_queue_entry_id, analyst_id,
          decision, analyst_comment, idempotency_key, override_of_decision_id, override_decision)
        SELECT institution_id, alert_queue_entry_id, ?, 'SENIOR_OVERRIDE',
          'The customer confirmed the payment by phone', gen_random_uuid(), id, ?
        FROM fraudshield.alert_decisions WHERE id = ?
        """,
        officer,
        decision,
        review);
  }

  /** A review of an auto-blocked transaction whose customer answers after the decision. */
  private static UUID verified(UUID bank, UUID analyst, String decision, String answer) {
    final UUID transaction = UUID.randomUUID();
    final UUID entry = entryOfTransaction(bank, "HIGH", Instant.now(), transaction);
    final UUID verification = verification(bank, autoBlock(bank, transaction, Instant.now()));
    final UUID review = review(bank, entry, analyst, decision);
    respond(bank, verification, answer);
    return review;
  }

  /** A review whose customer answered before the decision, which verifies nothing. */
  private static void answeredBefore(UUID bank, UUID analyst) {
    final UUID transaction = UUID.randomUUID();
    final UUID entry = entryOfTransaction(bank, "HIGH", Instant.now(), transaction);
    respond(bank, verification(bank, autoBlock(bank, transaction, Instant.now())), "NOT_ME");
    review(bank, entry, analyst, "CONFIRM_FRAUD");
  }

  private static UUID entryOfTransaction(
      UUID bank, String tier, Instant createdAt, UUID transaction) {
    final UUID id = entry(bank, tier, "PENDING", null, createdAt);
    tenantWrite(
        bank,
        "UPDATE fraudshield.alert_queue_entries SET transaction_id = ? WHERE id = ?",
        transaction,
        id);
    return id;
  }

  private static UUID autoBlock(UUID bank, UUID transaction, Instant at) {
    final UUID id = UUID.randomUUID();
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.auto_block_events (id, institution_id, transaction_id,
          fraud_score_id, account_token, blocked_at, block_reason)
        VALUES (?, ?, ?, gen_random_uuid(), ?, ?, 'HIGH risk')
        """,
        id,
        bank,
        transaction,
        token(),
        Timestamp.from(at));
    return id;
  }

  private static UUID verification(UUID bank, UUID autoBlock) {
    final UUID id = UUID.randomUUID();
    byte[] hash = new byte[32];
    RANDOM.nextBytes(hash);
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.customer_verifications (id, institution_id, auto_block_event_id,
          verification_token_hash, verification_channel, expires_at)
        VALUES (?, ?, ?, ?, 'SMS_LINK', now() + interval '1 day')
        """,
        id,
        bank,
        autoBlock,
        hash);
    return id;
  }

  private static void respond(UUID bank, UUID verification, String answer) {
    tenantWrite(
        bank,
        """
        INSERT INTO fraudshield.customer_verification_responses (verification_id, institution_id,
          answer, self_service_allowed, responded_at)
        VALUES (?, ?, ?, false, clock_timestamp())
        """,
        verification,
        bank,
        answer);
  }

  /** A DECLINE first decision today, its transaction made today, with a latest label. */
  private static Tx declined(UUID bank, String label) {
    final Tx tx = new Tx(bank);
    tx.at = Instant.now().minusSeconds(2);
    tx.riskTier = "HIGH";
    tx.score = 0.93;
    tx.decision = "DECLINE";
    tx.amountRwf = new java.math.BigDecimal("42000");
    tx.write();
    label(tx, label, tx.at.plusSeconds(1));
    autoBlock(bank, tx.id, tx.at);
    return tx;
  }

  /**
   * A review dated in the past. V81's timing trigger stamps every decision with DB time, so the row
   * is written with the triggers off, and with the time to decision V82 would have stored.
   */
  private static void backdated(
      UUID bank, UUID entry, UUID analyst, Instant createdAt, int durationSeconds) {
    try (Connection admin = DB.superuser()) {
      admin.setAutoCommit(false);
      try (PreparedStatement replica =
          admin.prepareStatement("SET LOCAL session_replication_role = replica")) {
        replica.execute();
      }
      try (PreparedStatement insert =
          admin.prepareStatement(
              """
              INSERT INTO fraudshield.alert_decisions (institution_id, alert_queue_entry_id,
                analyst_id, decision, analyst_comment, idempotency_key, created_at,
                decision_duration_seconds)
              VALUES (?, ?, ?, 'CONFIRM_FRAUD', 'Reviewed the account history', gen_random_uuid(),
                ?, ?)
              """)) {
        insert.setObject(1, bank);
        insert.setObject(2, entry);
        insert.setObject(3, analyst);
        insert.setTimestamp(4, Timestamp.from(createdAt));
        insert.setInt(5, durationSeconds);
        insert.execute();
      }
      admin.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
