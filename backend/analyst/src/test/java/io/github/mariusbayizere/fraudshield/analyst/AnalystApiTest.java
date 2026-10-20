package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** P1 of the analyst API against the real application and database (M8B, ADR 0110, ADR 0112). */
class AnalystApiTest extends AnalystIntegrationTest {

  private static final UUID BANK = DB.createInstitution("analyst-reads");

  private Session analyst() {
    return issueSession(createAccount(BANK, "ANALYST", "ACTIVE"));
  }

  private Session as(String role) {
    return issueSession(createAccount(BANK, role, "ACTIVE"));
  }

  private JsonNode get(Session session, String path, String schema) {
    Http.Response response = http.get(path, session.bearer());
    assertThat(response.status()).as(path + " " + response.body()).isEqualTo(200);
    JsonNode body = response.json();
    assertThat(OpenApiSchemas.errors(schema, body)).as(path).isEmpty();
    return body;
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    for (JsonNode item : page.get("items")) {
      ids.add(item.get("alert_id").asString());
    }
    return ids;
  }

  private static Tx tx(String account, double score) {
    Tx tx = new Tx(BANK);
    tx.account = account;
    tx.score = score;
    return tx;
  }

  @Test
  @Tag("FR-04-02")
  void theStandardQueueListsOpenEntriesHighFirstThenByProbability() {
    String account = token();
    Alert high = new Alert(tx(account, 0.95).write());
    high.tier = "HIGH";
    high.write();
    Alert medium80 = new Alert(tx(account, 0.80).write()).write();
    Alert medium71 = new Alert(tx(account, 0.71).write()).write();
    Alert decided = new Alert(tx(account, 0.99).write());
    decided.status = "CONFIRMED_FRAUD";
    decided.write();
    Alert anomaly = new Alert(tx(account, 0.20).write());
    anomaly.tier = "ANOMALY";
    anomaly.write();

    JsonNode page = get(analyst(), "/api/v1/alerts?account_id=" + account, "AlertPage");

    assertThat(ids(page))
        .containsExactly(high.id.toString(), medium80.id.toString(), medium71.id.toString());
    JsonNode card = page.get("items").get(0);
    assertThat(card.get("masked_account").isNull())
        .as("no masked account until the vault offers one (ADR 0110 point 3)")
        .isTrue();
    assertThat(card.get("top_reasons")).hasSize(2);
    assertThat(card.get("amount").get("amount").asString()).isEqualTo("15000");
  }

  @Test
  @Tag("FR-04-09")
  void searchFindsDecidedAlertsOnlyWhenOutcomeAsksForThem() {
    String account = token();
    Alert decided = new Alert(tx(account, 0.9).write());
    decided.status = "CONFIRMED_FRAUD";
    decided.write();
    Session session = analyst();

    assertThat(ids(get(session, "/api/v1/alerts?account_id=" + account, "AlertPage"))).isEmpty();
    assertThat(
            ids(
                get(
                    session,
                    "/api/v1/alerts?queue=all&outcome=CONFIRMED_FRAUD,MARKED_LEGITIMATE&account_id="
                        + account,
                    "AlertPage")))
        .containsExactly(decided.id.toString());
  }

  @Test
  @Tag("FR-04-09")
  void filtersCombine() {
    String account = token();
    Tx small = tx(account, 0.7);
    small.amountRwf = new BigDecimal("900");
    Alert smallAlert = new Alert(small.write()).write();
    Tx card = tx(account, 0.7);
    card.channel = "CARD";
    card.amountRwf = new BigDecimal("50000");
    Alert cardAlert = new Alert(card.write()).write();
    Session session = analyst();

    assertThat(
            ids(
                get(
                    session,
                    "/api/v1/alerts?account_id=" + account + "&amount_min=1000",
                    "AlertPage")))
        .containsExactly(cardAlert.id.toString());
    assertThat(
            ids(
                get(
                    session,
                    "/api/v1/alerts?account_id=" + account + "&channel=MOBILE_MONEY",
                    "AlertPage")))
        .containsExactly(smallAlert.id.toString());
    assertThat(ids(get(session, "/api/v1/alerts?transaction_id=" + card.id, "AlertPage")))
        .containsExactly(cardAlert.id.toString());
    assertThat(http.get("/api/v1/alerts?channel=CARD,CARD", session.bearer()).problemType())
        .endsWith("validation");
    assertThat(http.get("/api/v1/alerts?queue=Standard", session.bearer()).status()).isEqualTo(422);
  }

  @Test
  @Tag("FR-04-02")
  void theAnomalyQueueIsOrderedByAnomalyScoreWithFallbacksLast() {
    String account = token();
    Tx low = tx(account, 0.3);
    low.anomaly = 0.991;
    low.riskTier = "LOW";
    low.decision = "APPROVE";
    Alert lowAlert = new Alert(low.write());
    lowAlert.tier = "ANOMALY";
    lowAlert.write();
    Tx high = tx(account, 0.2);
    high.anomaly = 0.999;
    high.riskTier = "LOW";
    high.decision = "APPROVE";
    Alert highAlert = new Alert(high.write());
    highAlert.tier = "ANOMALY";
    highAlert.write();

    JsonNode page =
        get(analyst(), "/api/v1/alerts?queue=anomaly&account_id=" + account, "AlertPage");

    assertThat(ids(page)).containsExactly(highAlert.id.toString(), lowAlert.id.toString());
    assertThat(page.get("items").get(0).get("anomaly_score").asDouble()).isEqualTo(0.999);
  }

  @Test
  @Tag("FR-04-10")
  void anEscalationIsSeenOnlyAtOrAboveItsTargetRole() {
    String account = token();
    Tx tx = tx(account, 0.8).write();
    Alert original = new Alert(tx);
    original.status = "ESCALATED";
    original.write();
    Alert toRiskOfficer = new Alert(tx);
    toRiskOfficer.escalatedFrom = original.id;
    toRiskOfficer.targetRole = "RISK_OFFICER";
    toRiskOfficer.write();
    String path = "/api/v1/alerts?queue=escalated&account_id=" + account;

    assertThat(ids(get(analyst(), path, "AlertPage"))).isEmpty();
    assertThat(ids(get(as("SENIOR_ANALYST"), path, "AlertPage"))).isEmpty();
    assertThat(ids(get(as("RISK_OFFICER"), path, "AlertPage")))
        .containsExactly(toRiskOfficer.id.toString());
    assertThat(ids(get(analyst(), "/api/v1/alerts?account_id=" + account, "AlertPage")))
        .as("an escalated original leaves the standard queue")
        .isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void pagesFollowTheirCursorAndACursorBelongsToItsOrder() {
    String account = token();
    List<String> expected = new ArrayList<>();
    for (double score : new double[] {0.9, 0.8, 0.7}) {
      expected.add(new Alert(tx(account, score).write()).write().id.toString());
    }
    Session session = analyst();
    List<String> seen = new ArrayList<>();
    String path = "/api/v1/alerts?limit=1&account_id=" + account;
    JsonNode page = get(session, path, "AlertPage");
    seen.addAll(ids(page));
    while (!page.get("next_cursor").isNull()) {
      String cursor = page.get("next_cursor").asString();
      page = get(session, path + "&cursor=" + cursor, "AlertPage");
      seen.addAll(ids(page));
      if (!page.get("next_cursor").isNull()) {
        assertThat(
                http.get(
                        "/api/v1/alerts?sort=newest&account_id=" + account + "&cursor=" + cursor,
                        session.bearer())
                    .status())
            .as("a priority cursor is refused under another order")
            .isEqualTo(422);
      }
    }
    assertThat(seen).isEqualTo(expected);
  }

  @Test
  @Tag("FR-04-03")
  void anAlertsDetailCarriesItsTokensSummaryAndDecisions() {
    Tx tx = tx(token(), 0.71).write();
    Alert alert = new Alert(tx).write();
    Session session = analyst();
    superuser(
        """
        INSERT INTO fraudshield.alert_decisions (institution_id, alert_queue_entry_id, decision,
          idempotency_key) VALUES (?, ?, 'AUTO_RELEASED', ?)
        """,
        BANK,
        alert.id,
        UUID.randomUUID());

    JsonNode detail = get(session, "/api/v1/alerts/" + alert.id, "AlertDetail");

    assertThat(detail.get("account_token").asString()).isEqualTo(tx.account);
    assertThat(detail.get("counterparty_token").asString()).isEqualTo(tx.counterparty);
    assertThat(detail.get("summary_template_key").asString()).isEqualTo("summary.tx_count_60s");
    assertThat(detail.get("decisions"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.get("decision").asString()).isEqualTo("AUTO_RELEASED");
              assertThat(d.get("state").asString()).as("final when written").isEqualTo("COMMITTED");
              assertThat(d.get("analyst").isNull()).isTrue();
            });
    assertThat(http.get("/api/v1/alerts/" + UUID.randomUUID(), session.bearer()).status())
        .isEqualTo(404);
  }

  @Test
  @Tag("FR-04-05")
  void theExplanationRanksAll44ContributionsAndIsAbsentWithoutShap() {
    Alert alert = new Alert(tx(token(), 0.71).write()).write();
    Tx fallback = tx(token(), 0.7);
    fallback.fallback = true;
    Alert fallbackAlert = new Alert(fallback.write()).write();
    Session session = analyst();

    JsonNode explanation =
        get(session, "/api/v1/alerts/" + alert.id + "/explanation", "Explanation");

    assertThat(explanation.get("contributions")).hasSize(44);
    assertThat(explanation.get("contributions").get(0).get("feature").asString())
        .isEqualTo("tx_count_60s");
    double previous = Double.MAX_VALUE;
    for (JsonNode contribution : explanation.get("contributions")) {
      double size = Math.abs(contribution.get("shap").asDouble());
      assertThat(size).isLessThanOrEqualTo(previous);
      previous = size;
    }
    assertThat(explanation.get("base_value").asDouble()).isEqualTo(-4.25);
    assertThat(
            http.get("/api/v1/alerts/" + fallbackAlert.id + "/explanation", session.bearer())
                .status())
        .as("no model ran, so there is nothing to explain")
        .isEqualTo(404);
  }

  @Test
  @Tag("FR-02-01")
  void aFallbackScoringResultCarriesAll44FeaturesAsMissing() {
    Tx fallback = tx(token(), 0.7);
    fallback.fallback = true;
    fallback.write();
    Session session = analyst();

    JsonNode result =
        get(session, "/api/v1/transactions/" + fallback.id + "/scoring-result", "ScoringResult");

    assertThat(result.get("ml_unavailable_fallback").asBoolean()).isTrue();
    assertThat(result.get("feature_vector").size()).isEqualTo(44);
    assertThat(result.get("feature_vector").get("tx_count_60s").isNull()).isTrue();
  }

  @Test
  @Tag("FR-04-06")
  void theHistoryIsMostRecentFirstWithEachOutcome() {
    String account = token();
    Tx released = tx(account, 0.7);
    released.at = NOW.minusSeconds(7200);
    released.write().then("TIMEOUT_RELEASE", "TIMEOUT_POLICY");
    Tx blocked = tx(account, 0.9);
    blocked.at = NOW.minusSeconds(3600);
    blocked.decision = "DECLINE";
    blocked.riskTier = "HIGH";
    blocked.write();
    Tx held = tx(account, 0.7);
    held.at = NOW.minusSeconds(60);
    held.write();
    Session session = analyst();

    JsonNode page =
        get(
            session,
            "/api/v1/accounts/" + account + "/transactions?limit=2",
            "AccountTransactionPage");

    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("items").get(0).get("outcome").asString()).isEqualTo("PENDING");
    assertThat(page.get("items").get(1).get("outcome").asString()).isEqualTo("BLOCKED");
    JsonNode next =
        get(
            session,
            "/api/v1/accounts/"
                + account
                + "/transactions?limit=2&cursor="
                + page.get("next_cursor").asString(),
            "AccountTransactionPage");
    assertThat(next.get("items"))
        .singleElement()
        .satisfies(t -> assertThat(t.get("outcome").asString()).isEqualTo("APPROVED"));
    assertThat(http.get("/api/v1/accounts/" + token() + "/transactions", session.bearer()).status())
        .isEqualTo(404);
    assertThat(http.get("/api/v1/accounts/not-a-token/transactions", session.bearer()).status())
        .isEqualTo(400);
  }

  @Test
  @Tag("FR-04-07")
  void theProfileCountsHoursInEachTransactionsOwnLocalTime() {
    String account = token();
    Tx compared = tx(account, 0.7);
    compared.at = java.time.Instant.parse("2026-10-01T10:00:00Z");
    for (int i = 0; i < 12; i++) {
      Tx earlier = tx(account, 0.1);
      earlier.decision = "APPROVE";
      earlier.riskTier = "LOW";
      earlier.at = java.time.Instant.parse("2026-09-20T06:00:00Z").plusSeconds(86_400L * i);
      if (i == 0) {
        earlier.currency = "KES";
      }
      earlier.write();
    }
    compared.write();

    JsonNode profile =
        get(
            analyst(),
            "/api/v1/accounts/" + account + "/behaviour-profile?transaction_id=" + compared.id,
            "BehaviourProfile");

    JsonNode hours = profile.get("hourly_counts");
    assertThat(hours.get(8).asInt()).as("06:00 UTC in Kigali (+2)").isEqualTo(11);
    assertThat(hours.get(9).asInt()).as("06:00 UTC in Nairobi (+3), from KES").isEqualTo(1);
    assertThat(profile.get("comparison")).hasSize(6);
    assertThat(profile.get("comparison").get(0).get("this_transaction").asInt())
        .as("10:00 UTC in Kigali")
        .isEqualTo(12);
    assertThat(profile.get("comparison").get(0).get("anomalous").asBoolean()).isTrue();
  }

  @Test
  @Tag("FR-04-08")
  void theNetworkPutsTheAccountFirstAndMarksConfirmedFraud() {
    String account = token();
    String partner = token();
    String other = token();
    for (int i = 0; i < 2; i++) {
      Tx often = tx(account, 0.1);
      often.counterparty = partner;
      often.decision = "APPROVE";
      often.riskTier = "LOW";
      often.write();
    }
    Tx once = tx(account, 0.1);
    once.counterparty = other;
    once.decision = "APPROVE";
    once.riskTier = "LOW";
    once.write();
    Tx partnersFraud = tx(partner, 0.9);
    partnersFraud.decision = "DECLINE";
    partnersFraud.riskTier = "HIGH";
    partnersFraud.write();
    label(partnersFraud, "FRAUD", NOW.minusSeconds(60));

    JsonNode network = get(analyst(), "/api/v1/accounts/" + account + "/network", "AccountNetwork");

    JsonNode nodes = network.get("nodes");
    assertThat(nodes.get(0).get("token").asString()).isEqualTo(account);
    assertThat(nodes.get(0).get("degree").asInt()).isZero();
    assertThat(nodes.get(1).get("token").asString())
        .as("more transactions first")
        .isEqualTo(partner);
    assertThat(nodes.get(1).get("confirmed_fraud").asBoolean()).isTrue();
    assertThat(nodes.get(2).get("token").asString()).isEqualTo(other);
    assertThat(network.get("edges")).isNotEmpty();
  }

  @Test
  @Tag("TEST-04")
  void anotherInstitutionsAlertIsNotFoundAndAdministratorsAreRefused() {
    Tx foreign = new Tx(BANK_B);
    foreign.write();
    Alert alert = new Alert(foreign).write();

    assertThat(http.get("/api/v1/alerts/" + alert.id, analyst().bearer()).status()).isEqualTo(404);
    assertThat(http.get("/api/v1/alerts", as("ADMIN").bearer()).status()).isEqualTo(403);
  }
}
