package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.GetModelStatusRequest;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreRequest;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreResponse;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoringServiceGrpc;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScorerUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The ML scorer over gRPC (C.2 steps 4–8): one persistent HTTP/2 channel, a per-call deadline, and
 * a Resilience4j circuit breaker over a 5-second time window, so an unavailable or slow scorer
 * opens the circuit within 5 s and every call then goes straight to the rule-based fallback (C.4)
 * until a half-open probe succeeds. A result that breaks the contract counts as a failure.
 */
public final class GrpcScorer implements ScoringPort, AutoCloseable {

  private final ManagedChannel channel;
  private final ScoringServiceGrpc.ScoringServiceBlockingStub stub;
  private final Duration deadline;
  private final CircuitBreaker breaker;

  /**
   * Creates the client.
   *
   * @param channel channel to the scorer (mTLS in production, see {@link ScorerChannels})
   * @param deadline per-call deadline, inside the hot-path budget
   */
  public GrpcScorer(ManagedChannel channel, Duration deadline) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.stub = ScoringServiceGrpc.newBlockingStub(channel);
    this.deadline = Objects.requireNonNull(deadline, "deadline");
    this.breaker = CircuitBreaker.of("ml-scorer", breakerConfig(deadline));
  }

  /**
   * The circuit breaker settings: a 5-second time window, at least 10 calls, open at a 50% failure
   * or slow-call rate, 5 seconds open before 5 half-open probes.
   *
   * @param deadline slow-call threshold
   * @return the configuration
   */
  static CircuitBreakerConfig breakerConfig(Duration deadline) {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
        .slidingWindowSize(5)
        .minimumNumberOfCalls(10)
        .failureRateThreshold(50)
        .slowCallDurationThreshold(deadline)
        .slowCallRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(5))
        .permittedNumberOfCallsInHalfOpenState(5)
        .recordExceptions(StatusRuntimeException.class, IllegalArgumentException.class)
        .build();
  }

  /**
   * The circuit's current state, for {@code /actuator/health/ml} and metrics.
   *
   * @return CLOSED, OPEN or HALF_OPEN (and the forced states)
   */
  public CircuitBreaker.State state() {
    return breaker.getState();
  }

  @Override
  public Scored score(Transaction transaction, List<Money> limits) {
    // Built before the deadline starts, so request construction never spends the scorer's budget
    // (the first call pays protobuf class initialisation).
    ScoreRequest request = ScoringContract.request(transaction, limits);
    try {
      return breaker.executeSupplier(
          () -> {
            ScoreResponse response =
                stub.withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS).score(request);
            return ScoringContract.result(transaction, response.getResult());
          });
    } catch (CallNotPermittedException open) {
      throw new ScorerUnavailableException("the scorer circuit is open", open);
    } catch (StatusRuntimeException | IllegalArgumentException failed) {
      throw new ScorerUnavailableException(
          "the scorer failed: " + failed.getClass().getSimpleName(), failed);
    }
  }

  /**
   * Warms the channel and the contract classes before the first transaction: asks the scorer for
   * its model status and builds (without sending) a request, so the first real decision does not
   * pay connection set-up and class initialisation inside its deadline.
   *
   * @param timeout how long the status call may take
   * @return the scorer's production model version, if it answered
   */
  public java.util.Optional<String> warmUp(Duration timeout) {
    ScoringContract.request(WARM_UP, List.of());
    try {
      return java.util.Optional.of(
          stub.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS)
              .getModelStatus(GetModelStatusRequest.getDefaultInstance())
              .getProductionModelVersion());
    } catch (StatusRuntimeException unavailable) {
      return java.util.Optional.empty();
    }
  }

  private static final Transaction WARM_UP =
      new Transaction(
          new java.util.UUID(0, 0),
          new java.util.UUID(0, 1),
          "tok_WarmUpAccountAaaaBbbbCccc",
          "tok_WarmUpCounterpartyAaaaBbb",
          Money.of("1", io.github.mariusbayizere.fraudshield.common.money.CurrencyCode.RWF),
          java.math.BigDecimal.ONE,
          io.github.mariusbayizere.fraudshield.common.transaction.Channel.MOBILE_MONEY,
          "0000",
          0,
          0,
          null,
          null,
          null,
          java.time.Instant.EPOCH,
          java.time.Instant.EPOCH);

  @Override
  public void close() {
    channel.shutdown();
    try {
      if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      channel.shutdownNow();
    }
  }
}
