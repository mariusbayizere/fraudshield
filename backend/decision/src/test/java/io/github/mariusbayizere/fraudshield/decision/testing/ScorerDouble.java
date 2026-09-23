package io.github.mariusbayizere.fraudshield.decision.testing;

import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.FeatureValue;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.GetModelStatusRequest;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.GetModelStatusResponse;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.RiskTier;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreRequest;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreResponse;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoringResult;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoringServiceGrpc;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ShapContribution;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldCatalogue;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

/**
 * A test double of the ML scorer's gRPC service (tests only; the real scorer is M5's). It answers
 * with a contract-shaped result whose score a test chooses, and can fail, stall or break the
 * contract on demand.
 */
public final class ScorerDouble extends ScoringServiceGrpc.ScoringServiceImplBase {

  /** Requests received, in order. */
  public final List<ScoreRequest> requests = new CopyOnWriteArrayList<>();

  /** Calls received. */
  public final AtomicInteger calls = new AtomicInteger();

  /** The ensemble score for a request. */
  public volatile ToDoubleFunction<ScoreRequest> score = r -> 0.1;

  /** When set, every call fails with this status. */
  public volatile Status failure;

  /** Delay before answering. */
  public volatile long delayMillis;

  /** Applied to each result before it is sent, to break the contract on purpose. */
  public volatile UnaryOperator<ScoringResult.Builder> tamper = b -> b;

  @Override
  public void getModelStatus(
      GetModelStatusRequest request, StreamObserver<GetModelStatusResponse> response) {
    response.onNext(
        GetModelStatusResponse.newBuilder()
            .setStatus(GetModelStatusResponse.Status.STATUS_SERVING)
            .setProductionModelVersion("fs-ensemble-test-double")
            .setFeatureRegistryVersion("registry-test-double")
            .build());
    response.onCompleted();
  }

  @Override
  public void score(ScoreRequest request, StreamObserver<ScoreResponse> response) {
    calls.incrementAndGet();
    requests.add(request);
    if (delayMillis > 0) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failure != null) {
      response.onError(failure.asRuntimeException());
      return;
    }
    double ensemble = score.applyAsDouble(request);
    ScoringResult.Builder result =
        ScoringResult.newBuilder()
            .setScoringResultId(UUID.randomUUID().toString())
            .setTransactionId(request.getTransaction().getTransactionId())
            .setEnsembleScore(ensemble)
            .setXgboostScore(ensemble)
            .setLightgbmScore(ensemble)
            .setAnomalyScore(0.2)
            .setAnomalyRaw(-0.45)
            .setModelRiskTier(RiskTier.RISK_TIER_LOW)
            .setModelVersion("fs-ensemble-test-double")
            .setFeatureRegistryVersion("registry-test-double")
            .setScoringDurationMs(3);
    for (String feature : FieldCatalogue.featureNames()) {
      result.putFeatureVector(
          feature,
          switch (feature) {
            case "channel" ->
                FeatureValue.newBuilder()
                    .setCategory(
                        request.getTransaction().getChannel().name().substring("CHANNEL_".length()))
                    .build();
            case "corridor_class" -> FeatureValue.newBuilder().setCategory("DOMESTIC").build();
            case "device_age_days" ->
                FeatureValue.newBuilder()
                    .setMissing(FeatureValue.Missing.getDefaultInstance())
                    .build();
            default -> FeatureValue.newBuilder().setNumber(1.0).build();
          });
    }
    if (ensemble >= 0.60) {
      ShapContribution velocity =
          ShapContribution.newBuilder()
              .setFeature("tx_count_60s")
              .setValue(FeatureValue.newBuilder().setNumber(9))
              .setShap(1.4)
              .setIncreasesRisk(true)
              .setTemplateKey("feature.tx_count_60s.high")
              .build();
      result.addShapTop5(velocity);
      for (String feature : FieldCatalogue.featureNames()) {
        result.addShapAll(velocity.toBuilder().setFeature(feature).setShap(0.01));
      }
    }
    response.onNext(ScoreResponse.newBuilder().setResult(tamper.apply(result)).build());
    response.onCompleted();
  }
}
