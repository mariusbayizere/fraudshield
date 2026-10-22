package io.github.mariusbayizere.fraudshield.analyst;

import io.github.mariusbayizere.fraudshield.auth.testing.AuthIntegrationTest;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldCatalogue;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Base of the analyst API's integration tests: the real application over HTTP (M7's harness), with
 * alerts, scores and transactions written as the decision path's sink writes them, as the owner, so
 * that the API reads them through row-level security exactly as in production.
 */
abstract class AnalystIntegrationTest extends AuthIntegrationTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  /** Today, as the seeded transactions are dated relative to it. */
  protected static final Instant NOW = Instant.now();

  /**
   * A fresh token in the contract's form.
   *
   * @return the token
   */
  protected static String token() {
    StringBuilder token = new StringBuilder("tok_");
    for (int i = 0; i < 28; i++) {
      token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return token.toString();
  }

  /** One transaction to seed, with its score and its first decision. */
  protected static final class Tx {
    final UUID institution;
    final UUID id = UUID.randomUUID();
    final UUID scoreId = UUID.randomUUID();
    String account = token();
    String counterparty = token();
    Instant at = NOW.minusSeconds(3600);
    BigDecimal amount = new BigDecimal("15000");
    String currency = "RWF";
    BigDecimal amountRwf = new BigDecimal("15000");
    String channel = "MOBILE_MONEY";
    String merchant;
    String device;
    String counterpartyCountry = "RW";
    double score = 0.71;
    double anomaly = 0.42;
    String riskTier = "MEDIUM";
    boolean fallback;
    boolean shap = true;
    String decision = "HOLD";

    Tx(UUID institution) {
      this.institution = institution;
    }

    /** Writes the transaction, its score and its first decision state. */
    Tx write() {
      byte[] fingerprint = new byte[32];
      RANDOM.nextBytes(fingerprint);
      tenantWrite(
          institution,
          "INSERT INTO fraudshield.transaction_ids (institution_id, transaction_id,"
              + " request_fingerprint, transaction_timestamp) VALUES (?, ?, ?, ?)",
          institution,
          id,
          fingerprint,
          Timestamp.from(at));
      tenantWrite(
          institution,
          """
          INSERT INTO fraudshield.transactions (institution_id, transaction_id, account_token,
            counterparty_token, amount, currency, amount_rwf, channel, merchant_category_code,
            merchant_name, latitude, longitude, device_token, agent_token, counterparty_country,
            transaction_timestamp, received_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, '5411', ?, -1.95, 30.06, ?, ?, ?, ?, ?)
          """,
          institution,
          id,
          account,
          counterparty,
          amount,
          currency,
          amountRwf,
          channel,
          merchant,
          device,
          "AGENT_BANKING".equals(channel) ? token() : null,
          counterpartyCountry,
          Timestamp.from(at),
          Timestamp.from(at));
      boolean withShap = shap && !fallback;
      tenantWrite(
          institution,
          """
          INSERT INTO fraudshield.fraud_scores (id, institution_id, transaction_id, scored_at,
            ensemble_score, xgboost_score, lightgbm_score, anomaly_score, anomaly_raw, risk_tier,
            shap_top5, shap_all, feature_vector, model_version, feature_registry_version,
            scoring_duration_ms, requires_analyst_review, ml_unavailable_fallback, shap_base_value,
            final_margin)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, -0.5, ?, ?::jsonb, ?::jsonb, ?::jsonb, 'fs-test', 'r1',
            12, true, ?, ?, ?)
          """,
          scoreId,
          institution,
          id,
          Timestamp.from(at),
          score,
          score,
          score,
          fallback ? 0.0 : anomaly,
          riskTier,
          withShap || !"LOW".equals(riskTier) && !fallback ? top5() : null,
          withShap ? all() : null,
          fallback ? "{}" : vector(),
          fallback,
          withShap ? -4.25 : null,
          withShap ? 0.9 : null);
      tenantWrite(
          institution,
          """
          INSERT INTO fraudshield.decision_states (event_id, institution_id, transaction_id,
            decision_sequence, decision, final, decided_at, decided_by, review_deadline_at)
          VALUES (?, ?, ?, 1, ?, ?, ?, 'MODEL', ?)
          """,
          UUID.randomUUID(),
          institution,
          id,
          decision,
          !"HOLD".equals(decision),
          Timestamp.from(at),
          "HOLD".equals(decision) ? Timestamp.from(at.plusSeconds(30)) : null);
      return this;
    }

    /**
     * A later decision state, as the transaction's owner records it (decision-owner contract
     * §4.4): an owner row, then an APPLIED request row at sequence 2.
     */
    Tx then(String next, String decidedBy) {
      tenantWrite(
          institution,
          """
          INSERT INTO fraudshield.decision_owners (institution_id, transaction_id, first_event_id,
            first_decision, first_deadline, sequence, decision, decided_by, deadline)
          SELECT institution_id, transaction_id, event_id, decision, review_deadline_at, 1,
            decision, 'MODEL', review_deadline_at FROM fraudshield.decision_states
          WHERE transaction_id = ?
          """,
          id);
      tenantWrite(
          institution,
          """
          INSERT INTO fraudshield.transaction_requests (institution_id, transaction_id,
            request_id, kind, outcome, sequence, decision, supersedes_decision, reason_codes,
            event_id, decided_by, decided_at, event_body)
          VALUES (?, ?, gen_random_uuid(), 'TIMEOUT', 'APPLIED', 2, ?, ?, '{}', gen_random_uuid(),
            ?, ?, '[]'::jsonb)
          """,
          institution,
          id,
          next,
          decision,
          decidedBy,
          Timestamp.from(at.plusSeconds(40)));
      tenantWrite(
          institution,
          "UPDATE fraudshield.decision_owners SET sequence = 2, decision = ?, decided_by = ?,"
              + " deadline = NULL WHERE transaction_id = ?",
          next,
          decidedBy,
          id);
      return this;
    }

    private static String top5() {
      return "[" + contribution("tx_count_60s", 1.4) + "," + contribution("tx_count_1h", 0.9) + "]";
    }

    private static String all() {
      List<String> items = new ArrayList<>();
      double shap = 0.01;
      for (String feature : FieldCatalogue.featureNames()) {
        items.add(contribution(feature, "tx_count_60s".equals(feature) ? 1.4 : shap));
        shap = -shap * 1.05;
      }
      return "[" + String.join(",", items) + "]";
    }

    private static String contribution(String feature, double shap) {
      return String.format(
          Locale.ROOT,
          "{\"feature\":\"%s\",\"value\":1.0,\"shap\":%s,\"direction\":\"%s\","
              + "\"template_key\":\"feature.%s.high\"}",
          feature,
          shap,
          shap >= 0 ? "INCREASES_RISK" : "DECREASES_RISK",
          feature);
    }

    private static String vector() {
      List<String> items = new ArrayList<>();
      for (String feature : FieldCatalogue.featureNames()) {
        items.add(
            "\"" + feature + "\":" + ("channel".equals(feature) ? "\"MOBILE_MONEY\"" : "1.0"));
      }
      return "{" + String.join(",", items) + "}";
    }
  }

  /** One alert entry to seed for a written transaction. */
  protected static final class Alert {
    final UUID id = UUID.randomUUID();
    final Tx tx;
    String tier = "MEDIUM";
    String status = "PENDING";
    BigDecimal expectedLoss = new BigDecimal("10650");
    UUID escalatedFrom;
    String targetRole;
    Instant createdAt = NOW;

    Alert(Tx tx) {
      this.tx = tx;
    }

    /** Writes the entry. */
    Alert write() {
      boolean escalated = escalatedFrom != null;
      tenantWrite(
          tx.institution,
          """
          INSERT INTO fraudshield.alert_queue_entries (id, institution_id, transaction_id,
            fraud_score_id, tier, status, fraud_probability, expected_loss_rwf, review_deadline_at,
            escalated_from_alert_id, escalation_target_role, escalation_reason, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          id,
          tx.institution,
          tx.id,
          tx.scoreId,
          tier,
          status,
          tx.score,
          expectedLoss,
          "MEDIUM".equals(tier) && !escalated ? Timestamp.from(tx.at.plusSeconds(30)) : null,
          escalatedFrom,
          escalated ? targetRole : null,
          escalated ? "Needs a senior view" : null,
          Timestamp.from(createdAt));
      return this;
    }
  }

  /**
   * Runs one statement as the owner inside a transaction of one institution. The hypertables'
   * insert guard (V1) refuses a row whose institution is not the transaction's, for every role, so
   * seeding sets the tenant exactly as the application's own writers do.
   *
   * @param institution the institution
   * @param sql the statement
   * @param args its arguments
   */
  protected static void tenantWrite(UUID institution, String sql, Object... args) {
    try (Connection admin = DB.superuser()) {
      admin.setAutoCommit(false);
      try (PreparedStatement tenant =
          admin.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
        tenant.setString(1, institution.toString());
        tenant.execute();
      }
      try (PreparedStatement statement = admin.prepareStatement(sql)) {
        for (int i = 0; i < args.length; i++) {
          statement.setObject(i + 1, args[i]);
        }
        statement.execute();
      }
      admin.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Records a label for a transaction.
   *
   * @param tx the transaction
   * @param label FRAUD or LEGITIMATE
   * @param availableAt when the label became available
   */
  protected static void label(Tx tx, String label, Instant availableAt) {
    tenantWrite(
        tx.institution,
        """
        INSERT INTO fraudshield.label_events (institution_id, transaction_id, label, source,
          transaction_timestamp, label_available_at)
        VALUES (?, ?, ?, 'ANALYST', ?, ?)
        """,
        tx.institution,
        tx.id,
        label,
        Timestamp.from(tx.at),
        Timestamp.from(availableAt));
  }
}
