package io.github.mariusbayizere.fraudshield.decision.adapter.events;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.AlertTier;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes the events of one decision as one spool record (a JSON array of typed facts) and decodes
 * it again. Both spool consumers read the same facts, so the Kafka events and the PostgreSQL rows
 * are derived from identical data.
 */
public final class FactCodec {

  /** Shared mapper; thread-safe once configured. */
  static final ObjectMapper JSON = JsonMapper.builder().build();

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
      new TypeReference<>() {};

  private FactCodec() {}

  /**
   * Encodes events.
   *
   * @param events events of one decision
   * @return UTF-8 JSON
   */
  public static byte[] encode(List<DecisionEvent> events) {
    ArrayNode facts = JSON.createArrayNode();
    for (DecisionEvent event : events) {
      facts.add(encodeFact(event));
    }
    return JSON.writeValueAsBytes(facts);
  }

  /**
   * Decodes a spool record.
   *
   * @param bytes UTF-8 JSON written by {@link #encode(List)}
   * @return the events, in order
   */
  public static List<DecisionEvent> decode(byte[] bytes) {
    JsonNode facts = JSON.readTree(bytes);
    List<DecisionEvent> events = new ArrayList<>(facts.size());
    for (JsonNode fact : facts) {
      events.add(decodeFact(fact));
    }
    return events;
  }

  /**
   * Serialises a JSON-ready value (maps, lists, numbers, strings, nulls).
   *
   * @param value the value
   * @return UTF-8 JSON
   */
  public static byte[] toJson(Object value) {
    return JSON.writeValueAsBytes(value);
  }

  private static ObjectNode encodeFact(DecisionEvent event) {
    ObjectNode node = JSON.createObjectNode();
    switch (event) {
      case DecisionEvent.TransactionDecided e -> {
        node.put("type", "transaction_decided");
        node.set("transaction", transaction(e.transaction()));
        node.set("scoring", JSON.valueToTree(scoringMap(e.scoring())));
        node.set("outcome", outcome(e.outcome()));
        node.set("state", state(e.state()));
        node.put("thresholds_version", e.thresholdsVersion());
        node.put("fingerprint", HexFormat.of().formatHex(e.requestFingerprint()));
        node.put("latency_ms", e.decisionLatencyMs());
        node.put("first_seen", e.firstSeenForAccount());
      }
      case DecisionEvent.AlertRaised e -> {
        node.put("type", "alert_raised");
        node.put("alert_id", e.alertId().toString());
        node.set("transaction", transaction(e.transaction()));
        node.put("tier", e.tier().name());
        node.put("fraud_probability", e.fraudProbability());
        if (e.anomalyScore() != null) {
          node.put("anomaly_score", e.anomalyScore());
        }
        node.put("scoring_result_id", e.scoringResultId().toString());
        putInstant(node, "review_deadline_at", e.reviewDeadlineAt());
        node.set("reason_codes", JSON.valueToTree(e.reasonCodes()));
        putInstant(node, "created_at", e.createdAt());
      }
      case DecisionEvent.AutoBlocked e -> {
        node.put("type", "auto_blocked");
        node.put("auto_block_event_id", e.autoBlockEventId().toString());
        node.put("institution_id", e.institutionId().toString());
        node.put("transaction_id", e.transactionId().toString());
        node.put("scoring_result_id", e.scoringResultId().toString());
        node.put("account_token", e.accountToken());
        putInstant(node, "blocked_at", e.blockedAt());
        node.put("reason", e.reason());
      }
      case DecisionEvent.AccountFrozen e -> {
        node.put("type", "account_frozen");
        node.put("institution_id", e.institutionId().toString());
        node.put("account_token", e.accountToken());
        node.put("auto_block_event_id", e.autoBlockEventId().toString());
        node.put("high_decisions", e.highDecisions());
        putInstant(node, "frozen_at", e.frozenAt());
      }
      case DecisionEvent.CustomerNotificationRequested e -> {
        node.put("type", "customer_notification");
        node.put("notification_id", e.notificationId().toString());
        node.put("institution_id", e.institutionId().toString());
        node.put("account_token", e.accountToken());
        node.put("auto_block_event_id", e.autoBlockEventId().toString());
        node.put("template_key", e.templateKey());
        node.put("locale", e.locale());
        node.put("verification_link_allowed", e.verificationLinkAllowed());
        node.put("masked_account", e.maskedAccount());
        node.set("amount", money(e.amount()));
        node.put("local_time", e.localTime());
        node.put("reference_code", e.referenceCode());
        putInstant(node, "requested_at", e.requestedAt());
      }
      case DecisionEvent.DecisionChanged e -> {
        node.put("type", "decision_changed");
        node.set("state", state(e.state()));
        node.put("channel", e.channel());
        node.put("actor_user_id", e.actorUserId() == null ? null : e.actorUserId().toString());
      }
      case DecisionEvent.LabelRecorded e -> {
        node.put("type", "label_recorded");
        node.put("label_id", e.labelId().toString());
        node.put("institution_id", e.institutionId().toString());
        node.put("transaction_id", e.transactionId().toString());
        node.put("fraud", e.fraud());
        node.put("source", e.source());
        putInstant(node, "transaction_timestamp", e.transactionTimestamp());
        putInstant(node, "available_at", e.availableAt());
      }
      case DecisionEvent.CircuitBreakerChanged e -> {
        node.put("type", "circuit_breaker_changed");
        node.put("institution_id", e.institutionId().toString());
        node.put("merchant_category_code", e.merchantCategoryCode());
        node.put("change", e.change().name());
        node.put("transactions", e.counts().transactions());
        node.put("fraud", e.counts().fraud());
        node.put("settings_version", e.settingsVersion());
        putInstant(node, "at", e.at());
      }
    }
    return node;
  }

  private static DecisionEvent decodeFact(JsonNode node) {
    String type = node.get("type").asString();
    return switch (type) {
      case "transaction_decided" ->
          new DecisionEvent.TransactionDecided(
              transaction(node.get("transaction")),
              scoring(node.get("scoring")),
              outcome(node.get("outcome")),
              state(node.get("state")),
              node.get("thresholds_version").asLong(),
              HexFormat.of().parseHex(node.get("fingerprint").asString()),
              node.get("latency_ms").asLong(),
              node.get("first_seen").asBoolean());
      case "alert_raised" ->
          new DecisionEvent.AlertRaised(
              uuid(node, "alert_id"),
              transaction(node.get("transaction")).institutionId(),
              transaction(node.get("transaction")),
              AlertTier.valueOf(node.get("tier").asString()),
              node.get("fraud_probability").asDouble(),
              node.has("anomaly_score") ? node.get("anomaly_score").asDouble() : null,
              uuid(node, "scoring_result_id"),
              instant(node, "review_deadline_at"),
              strings(node.get("reason_codes")),
              instant(node, "created_at"));
      case "auto_blocked" ->
          new DecisionEvent.AutoBlocked(
              uuid(node, "auto_block_event_id"),
              uuid(node, "institution_id"),
              uuid(node, "transaction_id"),
              uuid(node, "scoring_result_id"),
              node.get("account_token").asString(),
              instant(node, "blocked_at"),
              node.get("reason").asString());
      case "account_frozen" ->
          new DecisionEvent.AccountFrozen(
              uuid(node, "institution_id"),
              node.get("account_token").asString(),
              uuid(node, "auto_block_event_id"),
              node.get("high_decisions").asInt(),
              instant(node, "frozen_at"));
      case "customer_notification" ->
          new DecisionEvent.CustomerNotificationRequested(
              uuid(node, "notification_id"),
              uuid(node, "institution_id"),
              node.get("account_token").asString(),
              uuid(node, "auto_block_event_id"),
              node.get("template_key").asString(),
              node.get("locale").asString(),
              node.get("verification_link_allowed").asBoolean(),
              node.get("masked_account").asString(),
              money(node.get("amount")),
              node.get("local_time").asString(),
              node.get("reference_code").asString(),
              instant(node, "requested_at"));
      case "decision_changed" ->
          new DecisionEvent.DecisionChanged(
              state(node.get("state")),
              text(node, "channel"),
              node.get("actor_user_id").isNull() ? null : uuid(node, "actor_user_id"));
      case "label_recorded" ->
          new DecisionEvent.LabelRecorded(
              uuid(node, "label_id"),
              uuid(node, "institution_id"),
              uuid(node, "transaction_id"),
              node.get("fraud").asBoolean(),
              node.get("source").asString(),
              instant(node, "transaction_timestamp"),
              instant(node, "available_at"));
      case "circuit_breaker_changed" ->
          new DecisionEvent.CircuitBreakerChanged(
              uuid(node, "institution_id"),
              node.get("merchant_category_code").asString(),
              MccCircuitBreaker.Change.valueOf(node.get("change").asString()),
              new MccCircuitBreaker.WindowCounts(
                  node.get("transactions").asLong(), node.get("fraud").asLong()),
              node.get("settings_version").asLong(),
              instant(node, "at"));
      default -> throw new IllegalArgumentException("unknown fact type " + type);
    };
  }

  static ObjectNode transaction(Transaction t) {
    ObjectNode node = JSON.createObjectNode();
    node.put("institution_id", t.institutionId().toString());
    node.put("transaction_id", t.transactionId().toString());
    node.put("account_token", t.accountToken());
    node.put("counterparty_token", t.counterpartyToken());
    node.set("amount", money(t.amount()));
    node.put("amount_rwf", decimal(t.amountRwf()));
    node.put("channel", t.channel().name());
    node.put("merchant_category_code", t.merchantCategoryCode());
    node.put("latitude", t.latitude());
    node.put("longitude", t.longitude());
    node.put("device_token", t.deviceToken());
    node.put("agent_token", t.agentToken());
    node.put("counterparty_country", t.counterpartyCountry());
    putInstant(node, "transaction_timestamp", t.transactionTimestamp());
    putInstant(node, "received_at", t.receivedAt());
    return node;
  }

  static Transaction transaction(JsonNode node) {
    return new Transaction(
        uuid(node, "institution_id"),
        uuid(node, "transaction_id"),
        node.get("account_token").asString(),
        node.get("counterparty_token").asString(),
        money(node.get("amount")),
        new BigDecimal(node.get("amount_rwf").asString()),
        Channel.valueOf(node.get("channel").asString()),
        node.get("merchant_category_code").asString(),
        node.get("latitude").asDouble(),
        node.get("longitude").asDouble(),
        text(node, "device_token"),
        text(node, "agent_token"),
        text(node, "counterparty_country"),
        instant(node, "transaction_timestamp"),
        instant(node, "received_at"));
  }

  static Map<String, Object> scoringMap(DecisionEvent.ScoringRecord s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("scoring_result_id", s.scoringResultId().toString());
    map.put("ensemble_score", s.ensembleScore());
    map.put("xgboost_score", s.xgboostScore());
    map.put("lightgbm_score", s.lightgbmScore());
    map.put("anomaly_score", s.anomalyScore());
    map.put("anomaly_raw", s.anomalyRaw());
    map.put("risk_tier", s.tier().name());
    map.put("shap_top5", s.shapTop5());
    map.put("shap_all", s.shapAll());
    map.put("feature_vector", s.featureVector());
    map.put("model_version", s.modelVersion());
    map.put("feature_registry_version", s.featureRegistryVersion());
    map.put("scoring_duration_ms", s.scoringDurationMs());
    map.put("ml_unavailable_fallback", s.fallback());
    map.put("trace_id", s.traceId());
    map.put("requires_analyst_review", s.requiresAnalystReview());
    return map;
  }

  private static DecisionEvent.ScoringRecord scoring(JsonNode node) {
    return new DecisionEvent.ScoringRecord(
        uuid(node, "scoring_result_id"),
        node.get("ensemble_score").asDouble(),
        node.get("xgboost_score").asDouble(),
        node.get("lightgbm_score").asDouble(),
        node.get("anomaly_score").asDouble(),
        node.get("anomaly_raw").asDouble(),
        RiskTier.valueOf(node.get("risk_tier").asString()),
        node.get("shap_top5").isNull()
            ? null
            : JSON.convertValue(node.get("shap_top5"), LIST_OF_MAPS),
        node.get("shap_all").isNull()
            ? null
            : JSON.convertValue(node.get("shap_all"), LIST_OF_MAPS),
        JSON.convertValue(node.get("feature_vector"), MAP),
        node.get("model_version").asString(),
        node.get("feature_registry_version").asString(),
        node.get("scoring_duration_ms").asInt(),
        node.get("ml_unavailable_fallback").asBoolean(),
        text(node, "trace_id"),
        node.get("requires_analyst_review").asBoolean());
  }

  private static ObjectNode outcome(DecisionOutcome o) {
    ObjectNode node = JSON.createObjectNode();
    node.put("decision", o.decision().name());
    node.put("tier", o.tier().name());
    node.set("reason_codes", JSON.valueToTree(o.reasonCodes()));
    putInstant(node, "review_deadline_at", o.reviewDeadlineAt());
    node.put("alert", o.alert().map(Enum::name).orElse(null));
    node.put("auto_block", o.autoBlock());
    node.put("fallback", o.fallback());
    return node;
  }

  private static DecisionOutcome outcome(JsonNode node) {
    String alert = text(node, "alert");
    return new DecisionOutcome(
        Decision.valueOf(node.get("decision").asString()),
        RiskTier.valueOf(node.get("tier").asString()),
        strings(node.get("reason_codes")),
        instant(node, "review_deadline_at"),
        alert == null ? Optional.empty() : Optional.of(AlertTier.valueOf(alert)),
        node.get("auto_block").asBoolean(),
        node.get("fallback").asBoolean());
  }

  /**
   * The OpenAPI {@code FinalDecision} body of a state.
   *
   * @param s the state
   * @return JSON object with exactly the schema's properties
   */
  public static ObjectNode finalDecision(DecisionState s) {
    ObjectNode node = JSON.createObjectNode();
    node.put("event_id", s.eventId().toString());
    node.put("transaction_id", s.transactionId().toString());
    node.put("decision_sequence", s.sequence());
    node.put("decision", s.decision().name());
    node.put("final", s.isFinal());
    putInstant(node, "decided_at", s.decidedAt());
    node.put("decided_by", s.decidedBy().name());
    node.set("reason_codes", JSON.valueToTree(s.reasonCodes()));
    putInstant(node, "review_deadline_at", s.reviewDeadlineAt());
    node.put("supersedes_decision", s.supersedes() == null ? null : s.supersedes().name());
    return node;
  }

  private static ObjectNode state(DecisionState s) {
    ObjectNode node = finalDecision(s);
    node.put("institution_id", s.institutionId().toString());
    return node;
  }

  /**
   * Reads a state written by {@link #finalDecision(DecisionState)} plus {@code institution_id}.
   *
   * @param node the JSON
   * @return the state
   */
  public static DecisionState state(JsonNode node) {
    String supersedes = text(node, "supersedes_decision");
    return new DecisionState(
        uuid(node, "event_id"),
        uuid(node, "institution_id"),
        uuid(node, "transaction_id"),
        node.get("decision_sequence").asInt(),
        DecisionValue.valueOf(node.get("decision").asString()),
        instant(node, "decided_at"),
        DecidedBy.valueOf(node.get("decided_by").asString()),
        strings(node.get("reason_codes")),
        instant(node, "review_deadline_at"),
        supersedes == null ? null : DecisionValue.valueOf(supersedes));
  }

  static ObjectNode money(Money money) {
    ObjectNode node = JSON.createObjectNode();
    node.put("amount", decimal(money.amount()));
    node.put("currency", money.currency().name());
    return node;
  }

  private static Money money(JsonNode node) {
    return Money.of(
        node.get("amount").asString(), CurrencyCode.valueOf(node.get("currency").asString()));
  }

  /**
   * A decimal as the contract's {@code DecimalAmount}: plain notation, no trailing zeros.
   *
   * @param value the decimal
   * @return for example {@code 15000} or {@code 0.5}
   */
  public static String decimal(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
  }

  private static void putInstant(ObjectNode node, String name, Instant value) {
    node.put(name, value == null ? null : value.toString());
  }

  private static Instant instant(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? null : Instant.parse(value.asString());
  }

  private static UUID uuid(JsonNode node, String name) {
    return UUID.fromString(node.get(name).asString());
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode item : array) {
      values.add(item.asString());
    }
    return values;
  }
}
