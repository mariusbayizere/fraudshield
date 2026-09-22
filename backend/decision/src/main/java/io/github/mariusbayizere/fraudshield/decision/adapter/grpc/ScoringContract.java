package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import com.google.protobuf.Timestamp;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.Channel;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.FeatureValue;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.GeoPoint;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreRequest;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoringResult;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ShapContribution;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
import io.github.mariusbayizere.fraudshield.decision.domain.FeatureContribution;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue.Category;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps between the domain and the frozen scoring contract ({@code fraudshield.scoring.v1}, ADR
 * 0012), and validates the scorer's answer: a result that breaks the contract is treated as the
 * scorer being unavailable, never decided on.
 */
public final class ScoringContract {

  /** Features in every vector (D-03). */
  public static final int FEATURES = 44;

  private ScoringContract() {}

  /**
   * The request for one transaction.
   *
   * @param t the transaction; the scorer reads the account's context from the feature store itself
   *     (ADR 0033), so none is sent
   * @param limits configured limits for {@code just_below_limit_flag}
   * @return the request
   */
  public static ScoreRequest request(Transaction t, List<Money> limits) {
    io.github.mariusbayizere.fraudshield.contracts.scoring.v1.Transaction.Builder tx =
        io.github.mariusbayizere.fraudshield.contracts.scoring.v1.Transaction.newBuilder()
            .setTransactionId(t.transactionId().toString())
            .setInstitutionId(t.institutionId().toString())
            .setAccountToken(t.accountToken())
            .setCounterpartyToken(t.counterpartyToken())
            .setAmount(money(t.amount()))
            .setAmountRwf(FactCodec.decimal(t.amountRwf()))
            .setChannel(Channel.valueOf("CHANNEL_" + t.channel().name()))
            .setMerchantCategoryCode(t.merchantCategoryCode())
            .setLocation(geo(t.latitude(), t.longitude()))
            .setTransactionTimestamp(timestamp(t.transactionTimestamp()))
            .setReceivedAt(timestamp(t.receivedAt()));
    if (t.deviceToken() != null) {
      tx.setDeviceToken(t.deviceToken());
    }
    if (t.agentToken() != null) {
      tx.setAgentToken(t.agentToken());
    }
    if (t.counterpartyCountry() != null) {
      tx.setCounterpartyCountry(t.counterpartyCountry());
    }
    ScoreRequest.Builder request = ScoreRequest.newBuilder().setTransaction(tx);
    for (Money limit : limits) {
      request.addConfiguredLimits(money(limit));
    }
    return request.build();
  }

  /**
   * Validates and maps a scoring result.
   *
   * @param transaction the scored transaction
   * @param r the result
   * @return domain scoring and the persisted record
   * @throws IllegalArgumentException when the result breaks the contract
   */
  public static ScoringPort.Scored result(Transaction transaction, ScoringResult r) {
    for (double probability :
        new double[] {
          r.getEnsembleScore(), r.getXgboostScore(), r.getLightgbmScore(), r.getAnomalyScore()
        }) {
      if (!(probability >= 0 && probability <= 1)) {
        throw new IllegalArgumentException("a score is not a probability");
      }
    }
    if (r.getModelVersion().isBlank() || r.getModelVersion().length() > 100) {
      throw new IllegalArgumentException("every result carries a model version (FR-02-10)");
    }
    if (r.getFeatureVectorCount() != FEATURES) {
      throw new IllegalArgumentException(
          "the feature vector has " + r.getFeatureVectorCount() + " entries, not 44");
    }
    if (!r.getTransactionId().equals(transaction.transactionId().toString())) {
      throw new IllegalArgumentException("the result is for another transaction");
    }
    if (r.getShapTop5Count() > 5 || (r.getShapAllCount() != 0 && r.getShapAllCount() != FEATURES)) {
      throw new IllegalArgumentException("SHAP contributions have the wrong size");
    }
    UUID id = UUID.fromString(r.getScoringResultId());
    Map<String, io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue> features =
        new LinkedHashMap<>();
    Map<String, Object> vector = new LinkedHashMap<>();
    for (Map.Entry<String, FeatureValue> entry : r.getFeatureVectorMap().entrySet()) {
      FeatureValue value = entry.getValue();
      switch (value.getValueCase()) {
        case NUMBER -> {
          features.put(
              entry.getKey(),
              io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue.of(value.getNumber()));
          vector.put(entry.getKey(), Double.isFinite(value.getNumber()) ? value.getNumber() : null);
        }
        case CATEGORY -> {
          features.put(entry.getKey(), new Category(value.getCategory()));
          vector.put(entry.getKey(), value.getCategory());
        }
        default -> {
          features.put(
              entry.getKey(), io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue.MISSING);
          vector.put(entry.getKey(), null);
        }
      }
    }
    List<FeatureContribution> top = new ArrayList<>();
    for (ShapContribution c : r.getShapTop5List()) {
      top.add(new FeatureContribution(c.getFeature(), c.getShap(), c.getIncreasesRisk()));
    }
    Scoring.Model model =
        new Scoring.Model(
            id, r.getModelVersion(), r.getEnsembleScore(), r.getAnomalyScore(), top, features);
    DecisionEvent.ScoringRecord record =
        new DecisionEvent.ScoringRecord(
            id,
            r.getEnsembleScore(),
            r.getXgboostScore(),
            r.getLightgbmScore(),
            r.getAnomalyScore(),
            r.getAnomalyRaw(),
            RiskTier.LOW,
            contributions(r.getShapTop5List()),
            r.getShapAllCount() == 0 ? null : contributions(r.getShapAllList()),
            vector,
            r.getModelVersion(),
            r.getFeatureRegistryVersion().isBlank() ? "unknown" : r.getFeatureRegistryVersion(),
            (int) r.getScoringDurationMs(),
            false,
            null,
            false,
            r.getFeatureStoreDegraded());
    return new ScoringPort.Scored(model, record);
  }

  private static List<Map<String, Object>> contributions(List<ShapContribution> list) {
    List<Map<String, Object>> rows = new ArrayList<>(list.size());
    for (ShapContribution c : list) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("feature", c.getFeature());
      FeatureValue value = c.getValue();
      row.put(
          "value",
          switch (value.getValueCase()) {
            case NUMBER -> Double.isFinite(value.getNumber()) ? value.getNumber() : null;
            case CATEGORY -> value.getCategory();
            default -> null;
          });
      row.put("shap", c.getShap());
      row.put("direction", c.getIncreasesRisk() ? "INCREASES_RISK" : "DECREASES_RISK");
      row.put("template_key", c.getTemplateKey());
      rows.add(row);
    }
    return rows;
  }

  private static io.github.mariusbayizere.fraudshield.contracts.scoring.v1.Money money(Money m) {
    return io.github.mariusbayizere.fraudshield.contracts.scoring.v1.Money.newBuilder()
        .setAmount(FactCodec.decimal(m.amount()))
        .setCurrency(m.currency().name())
        .build();
  }

  private static GeoPoint geo(double latitude, double longitude) {
    return GeoPoint.newBuilder().setLatitude(latitude).setLongitude(longitude).build();
  }

  static Timestamp timestamp(Instant at) {
    return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
  }
}
