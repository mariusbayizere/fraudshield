package io.github.mariusbayizere.fraudshield.decision.adapter.events;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.AlertTier;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders decision facts as Kafka envelopes for the topics in {@code contracts/kafka/topics.yaml}
 * (C.3). Envelope event ids are derived from the fact's own id and the topic, so re-publishing a
 * spool record after a crash produces the same ids and consumers deduplicate them.
 */
public final class KafkaMessages {

  /** Topic names (C.3). */
  public static final String RAW = "fs.transactions.raw";

  /** Scored transactions. */
  public static final String SCORED = "fs.transactions.scored";

  /** Final decisions (D-14). */
  public static final String DECISIONS = "fs.decisions.final";

  /** Audit events (D-32). */
  public static final String AUDIT = "fs.audit.events";

  /** Customer SMS intents. */
  public static final String CUSTOMER = "fs.notifications.customer";

  /** Staff notification intents. */
  public static final String STAFF = "fs.notifications.staff";

  /** Labels. */
  public static final String LABELS = "fs.labels";

  private final String producer;
  private final int writerPartition;

  /**
   * Creates the renderer.
   *
   * @param producer envelope producer, for example {@code fraudshield-api}
   * @param writerPartition this instance's audit writer partition (0–63, D-32)
   */
  public KafkaMessages(String producer, int writerPartition) {
    if (!producer.matches("^[a-z][a-z0-9-]{2,40}$")) {
      throw new IllegalArgumentException("producer name does not match the envelope schema");
    }
    if (writerPartition < 0 || writerPartition > 63) {
      throw new IllegalArgumentException("audit writer partitions are 0 to 63");
    }
    this.producer = producer;
    this.writerPartition = writerPartition;
  }

  /**
   * The alert topic of a tier.
   *
   * @param tier alert tier
   * @return {@code fs.alerts.high}, {@code .medium} or {@code .anomaly}
   */
  public static String alertTopic(AlertTier tier) {
    return "fs.alerts." + tier.name().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Renders every fact of a spool record.
   *
   * @param events the facts
   * @return the Kafka records, in order
   */
  public List<KafkaMessage> render(List<DecisionEvent> events) {
    List<KafkaMessage> messages = new ArrayList<>();
    for (DecisionEvent event : events) {
      render(event, messages);
    }
    return messages;
  }

  private void render(DecisionEvent event, List<KafkaMessage> out) {
    switch (event) {
      case DecisionEvent.TransactionDecided e -> {
        Transaction t = e.transaction();
        String base = e.state().eventId().toString();
        out.add(
            message(
                RAW,
                "transaction.received",
                t.transactionId().toString(),
                base,
                t.institutionId(),
                t.receivedAt(),
                raw(t)));
        out.add(
            message(
                SCORED,
                "transaction.scored",
                t.accountToken(),
                base,
                t.institutionId(),
                e.state().decidedAt(),
                scored(e)));
        out.add(decision(e.state()));
        if (e.outcome().decision() == Decision.HOLD) {
          out.add(
              audit(
                  "TRANSACTION_DECISION",
                  "HELD",
                  "transaction",
                  t.transactionId(),
                  base,
                  t.institutionId(),
                  e.state().decidedAt(),
                  null));
        } else if (e.outcome().reasonCodes().contains("ACCOUNT_FROZEN")) {
          out.add(
              audit(
                  "TRANSACTION_DECISION",
                  "DECLINED_ACCOUNT_FROZEN",
                  "transaction",
                  t.transactionId(),
                  base,
                  t.institutionId(),
                  e.state().decidedAt(),
                  null));
        }
      }
      case DecisionEvent.AlertRaised e ->
          out.add(
              message(
                  alertTopic(e.tier()),
                  "alert.created",
                  e.transaction().accountToken(),
                  e.alertId().toString(),
                  e.institutionId(),
                  e.createdAt(),
                  alert(e)));
      case DecisionEvent.AutoBlocked e ->
          out.add(
              audit(
                  "AUTO_BLOCK",
                  "AUTO_BLOCKED",
                  "auto_block_event",
                  e.autoBlockEventId(),
                  e.autoBlockEventId().toString(),
                  e.institutionId(),
                  e.blockedAt(),
                  null));
      case DecisionEvent.AccountFrozen e -> {
        String base = e.autoBlockEventId() + ":frozen";
        ObjectNode after = FactCodec.JSON.createObjectNode();
        after.put("account_token", e.accountToken());
        after.put("high_risk_count", e.highDecisions());
        out.add(
            audit(
                "AUTO_BLOCK",
                "ACCOUNT_FROZEN",
                "account",
                null,
                base,
                e.institutionId(),
                e.frozenAt(),
                after,
                e.accountToken()));
        out.add(
            message(
                STAFF,
                "notification.requested",
                "RISK_OFFICER",
                base,
                e.institutionId(),
                e.frozenAt(),
                frozen(e, base)));
      }
      case DecisionEvent.CustomerNotificationRequested e ->
          out.add(
              message(
                  CUSTOMER,
                  "notification.requested",
                  e.accountToken(),
                  e.notificationId().toString(),
                  e.institutionId(),
                  e.requestedAt(),
                  customer(e)));
      case DecisionEvent.DecisionChanged e -> {
        DecisionState s = e.state();
        out.add(decision(s));
        String action = changeAction(s);
        String type =
            switch (s.decidedBy()) {
              case ANALYST -> "ANALYST_DECISION";
              case CUSTOMER_VERIFICATION -> "CUSTOMER_VERIFICATION";
              case SENIOR_OVERRIDE -> "SENIOR_OVERRIDE";
              default -> "TRANSACTION_DECISION";
            };
        ObjectNode after = FactCodec.finalDecision(s);
        if (s.decidedBy() == DecidedBy.CUSTOMER_VERIFICATION) {
          after.put("false_positive_confirmed", true);
        }
        out.add(
            audit(
                type,
                action,
                "transaction",
                s.transactionId(),
                s.eventId().toString(),
                s.institutionId(),
                s.decidedAt(),
                after,
                null,
                e.actorUserId()));
      }
      case DecisionEvent.LabelRecorded e ->
          out.add(
              message(
                  LABELS,
                  "label.recorded",
                  e.transactionId().toString(),
                  e.labelId().toString(),
                  e.institutionId(),
                  e.availableAt(),
                  label(e)));
      case DecisionEvent.CircuitBreakerChanged e -> {
        ObjectNode after = FactCodec.JSON.createObjectNode();
        after.put("merchant_category_code", e.merchantCategoryCode());
        after.put("window_transactions", e.counts().transactions());
        after.put("window_fraud_rate", e.counts().rate().toPlainString());
        after.put("settings_version", e.settingsVersion());
        String base =
            e.institutionId() + ":" + e.merchantCategoryCode() + ":" + e.change() + ":" + e.at();
        out.add(
            audit(
                "THRESHOLD_CHANGE",
                "CIRCUIT_BREAKER_" + e.change().name(),
                "merchant_category",
                null,
                base,
                e.institutionId(),
                e.at(),
                after,
                e.merchantCategoryCode()));
      }
    }
  }

  private static String changeAction(DecisionState s) {
    return switch (s.decidedBy()) {
      case TIMEOUT_POLICY ->
          s.decision() == DecisionValue.TIMEOUT_RELEASE ? "TIMEOUT_RELEASE" : "TIMEOUT_DECLINE";
      case CUSTOMER_VERIFICATION -> "BLOCK_LIFTED";
      case ANALYST -> s.decision() == DecisionValue.APPROVE ? "HOLD_APPROVED" : "HOLD_DECLINED";
      default -> "DECISION_" + s.decision().name();
    };
  }

  private KafkaMessage decision(DecisionState s) {
    return message(
        DECISIONS,
        "decision.final",
        s.transactionId().toString(),
        s.eventId().toString(),
        s.institutionId(),
        s.decidedAt(),
        FactCodec.finalDecision(s),
        s.eventId());
  }

  private KafkaMessage audit(
      String type,
      String action,
      String entityType,
      UUID entityId,
      String base,
      UUID institution,
      Instant at,
      ObjectNode after) {
    return audit(type, action, entityType, entityId, base, institution, at, after, null, null);
  }

  private KafkaMessage audit(
      String type,
      String action,
      String entityType,
      UUID entityId,
      String base,
      UUID institution,
      Instant at,
      ObjectNode after,
      String entityText) {
    return audit(
        type, action, entityType, entityId, base, institution, at, after, entityText, null);
  }

  private KafkaMessage audit(
      String type,
      String action,
      String entityType,
      UUID entityId,
      String base,
      UUID institution,
      Instant at,
      ObjectNode after,
      String entityText,
      UUID actor) {
    ObjectNode payload = FactCodec.JSON.createObjectNode();
    payload.put("event_type", type);
    payload.put("action", action);
    payload.put("entity_type", entityType);
    payload.put("entity_id", entityText != null ? entityText : entityId.toString());
    payload.put("event_at", at.toString());
    payload.put("writer_partition", writerPartition);
    payload.put("actor_user_id", actor == null ? null : actor.toString());
    payload.putNull("actor_role");
    payload.putNull("before_value");
    payload.set("after_value", after);
    return message(
        AUDIT,
        "audit.appended",
        Integer.toString(writerPartition),
        base + ":" + action,
        institution,
        at,
        payload);
  }

  private KafkaMessage message(
      String topic,
      String eventType,
      String key,
      String base,
      UUID institution,
      Instant at,
      ObjectNode payload) {
    return message(
        topic,
        eventType,
        key,
        base,
        institution,
        at,
        payload,
        UUID.nameUUIDFromBytes((topic + "|" + base).getBytes(StandardCharsets.UTF_8)));
  }

  private KafkaMessage message(
      String topic,
      String eventType,
      String key,
      String base,
      UUID institution,
      Instant at,
      ObjectNode payload,
      UUID eventId) {
    ObjectNode envelope = FactCodec.JSON.createObjectNode();
    envelope.put("event_id", eventId.toString());
    envelope.put("event_type", eventType);
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", at.toString());
    envelope.put("institution_id", institution.toString());
    envelope.put("producer", producer);
    envelope.set("payload", payload);
    return new KafkaMessage(
        topic, key, eventId.toString(), FactCodec.JSON.writeValueAsBytes(envelope));
  }

  private static ObjectNode raw(Transaction t) {
    ObjectNode p = FactCodec.JSON.createObjectNode();
    p.put("transaction_id", t.transactionId().toString());
    p.put("account_id", t.accountToken());
    p.put("counterparty_id", t.counterpartyToken());
    p.put("amount", FactCodec.decimal(t.amount().amount()));
    p.put("currency", t.amount().currency().name());
    p.put("channel", t.channel().name());
    p.put("merchant_category_code", t.merchantCategoryCode());
    p.put("latitude", t.latitude());
    p.put("longitude", t.longitude());
    p.put("device_fingerprint", t.deviceToken());
    if (t.agentToken() != null) {
      p.put("agent_id", t.agentToken());
    }
    if (t.counterpartyCountry() != null) {
      p.put("counterparty_country", t.counterpartyCountry());
    }
    p.put("transaction_timestamp", t.transactionTimestamp().toString());
    return p;
  }

  private static ObjectNode scored(DecisionEvent.TransactionDecided e) {
    Transaction t = e.transaction();
    ObjectNode p = FactCodec.JSON.createObjectNode();
    p.put("transaction_id", t.transactionId().toString());
    p.put("account_token", t.accountToken());
    p.put("counterparty_token", t.counterpartyToken());
    p.put("device_token", t.deviceToken());
    p.put("agent_token", t.agentToken());
    p.set("amount", FactCodec.money(t.amount()));
    p.put("amount_rwf", FactCodec.decimal(t.amountRwf()));
    p.put("channel", t.channel().name());
    p.put("merchant_category_code", t.merchantCategoryCode());
    p.put("latitude", t.latitude());
    p.put("longitude", t.longitude());
    p.put("transaction_timestamp", t.transactionTimestamp().toString());
    p.put("received_at", t.receivedAt().toString());
    p.put("decision", e.outcome().decision().name());
    p.put("risk_tier", e.outcome().tier().name());
    p.put("ensemble_score", e.scoring().ensembleScore());
    p.put("anomaly_score", e.scoring().anomalyScore());
    p.put("scoring_result_id", e.scoring().scoringResultId().toString());
    p.put("model_version", e.scoring().modelVersion());
    p.put("ml_unavailable_fallback", e.scoring().fallback());
    p.put("decision_latency_ms", e.decisionLatencyMs());
    p.put("thresholds_version", Math.max(1, e.thresholdsVersion()));
    p.set("reason_codes", FactCodec.JSON.valueToTree(e.outcome().reasonCodes()));
    return p;
  }

  private static ObjectNode alert(DecisionEvent.AlertRaised e) {
    ObjectNode p = FactCodec.JSON.createObjectNode();
    p.put("alert_id", e.alertId().toString());
    p.put("version", 0);
    p.put("transaction_id", e.transaction().transactionId().toString());
    p.put("account_token", e.transaction().accountToken());
    p.put("tier", e.tier().name());
    p.put("fraud_probability", e.fraudProbability());
    if (e.anomalyScore() != null) {
      p.put("anomaly_score", e.anomalyScore());
    }
    p.set("amount", FactCodec.money(e.transaction().amount()));
    p.put("expected_loss_rwf", FactCodec.decimal(expectedLoss(e)));
    p.put("channel", e.transaction().channel().name());
    p.put("transaction_timestamp", e.transaction().transactionTimestamp().toString());
    p.put("scoring_result_id", e.scoringResultId().toString());
    p.put(
        "review_deadline_at",
        e.reviewDeadlineAt() == null ? null : e.reviewDeadlineAt().toString());
    p.put("holds_transaction", e.tier() == AlertTier.MEDIUM);
    p.set("reason_codes", FactCodec.JSON.valueToTree(e.reasonCodes()));
    return p;
  }

  /**
   * Expected loss for the "sort by expected loss" view (D-10): probability × amount in RWF.
   *
   * @param e the alert
   * @return the expected loss, four decimals
   */
  public static BigDecimal expectedLoss(DecisionEvent.AlertRaised e) {
    return BigDecimal.valueOf(e.fraudProbability())
        .multiply(e.transaction().amountRwf())
        .setScale(4, RoundingMode.HALF_EVEN);
  }

  private static ObjectNode frozen(DecisionEvent.AccountFrozen e, String base) {
    ObjectNode p = FactCodec.JSON.createObjectNode();
    p.put(
        "notification_id",
        UUID.nameUUIDFromBytes(base.getBytes(StandardCharsets.UTF_8)).toString());
    p.putNull("recipient_user_id");
    p.put("recipient_role", "RISK_OFFICER");
    p.put("kind", "ACCOUNT_FROZEN");
    p.put("template_key", "email.account_frozen");
    ObjectNode parameters = p.putObject("parameters");
    parameters.put("account_token", e.accountToken());
    parameters.put("high_risk_count", e.highDecisions());
    return p;
  }

  private static ObjectNode customer(DecisionEvent.CustomerNotificationRequested e) {
    ObjectNode p = FactCodec.JSON.createObjectNode();
    p.put("notification_id", e.notificationId().toString());
    p.put("account_token", e.accountToken());
    p.put("channel", "SMS");
    p.put("template_key", e.templateKey());
    p.put("locale", e.locale());
    p.put("auto_block_event_id", e.autoBlockEventId().toString());
    p.put("verification_link_allowed", e.verificationLinkAllowed());
    ObjectNode parameters = p.putObject("parameters");
    parameters.put("masked_account", e.maskedAccount());
    parameters.set("amount", FactCodec.money(e.amount()));
    parameters.put("local_time", e.localTime());
    parameters.put("reference_code", e.referenceCode());
    return p;
  }

  private static ObjectNode label(DecisionEvent.LabelRecorded e) {
    ObjectNode p = FactCodec.JSON.createObjectNode();
    p.put("label_id", e.labelId().toString());
    p.put("transaction_id", e.transactionId().toString());
    p.put("label", e.fraud() ? "FRAUD" : "LEGITIMATE");
    p.put("source", e.source().equals("CUSTOMER") ? "CUSTOMER_VERIFICATION" : e.source());
    p.put("label_available_at", e.availableAt().toString());
    p.put("transaction_timestamp", e.transactionTimestamp().toString());
    return p;
  }
}
