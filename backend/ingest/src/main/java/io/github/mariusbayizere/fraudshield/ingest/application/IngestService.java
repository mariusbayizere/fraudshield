package io.github.mariusbayizere.fraudshield.ingest.application;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.IngestDecision;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.FxRatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderOutcomeUnknownException;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.idempotency.IdempotencyStore;
import io.github.mariusbayizere.fraudshield.ingest.request.Fingerprints;
import io.github.mariusbayizere.fraudshield.ingest.request.IngestRequest;
import io.github.mariusbayizere.fraudshield.ingest.request.RequestValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.JsonNode;

/**
 * One ingest submission (FR-01-01..03, C.2): validate, normalise to RWF, claim the transaction id,
 * decide, and cache the exact response bytes for 24 hours. A duplicate while the first is still
 * being decided waits for that result up to a bound and then follows the replay or conflict rule;
 * if the first failed, the duplicate is decided as a first submission (ADR 0011 section 10).
 */
public final class IngestService {

  /** The outcome of one submission. */
  public sealed interface Outcome {}

  /**
   * Decided, now or earlier.
   *
   * @param body the exact response bytes
   * @param replayed whether this is a cached replay
   */
  public record Decided(byte[] body, boolean replayed) implements Outcome {
    /** Copies the body. */
    public Decided {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Decided that
          && replayed == that.replayed
          && java.util.Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(body);
    }

    @Override
    public String toString() {
      return "Decided[replayed=" + replayed + "]";
    }
  }

  /**
   * The request is invalid.
   *
   * @param result the validation errors and status
   */
  public record Invalid(RequestValidator.Result result) implements Outcome {}

  /** The transaction id was already submitted with a different request. */
  public record Conflict() implements Outcome {}

  /**
   * The request could not be decided now; the client retries.
   *
   * @param reason internal reason, never shown in detail to clients
   */
  public record Unavailable(String reason) implements Outcome {}

  private final DecisionService decisions;
  private final IdempotencyStore idempotency;
  private final FxRatePort rates;
  private final EventRecorder recorder;
  private final Clock clock;
  private final Duration duplicateWait;
  private final DecisionMetrics metrics;

  /**
   * Creates the service.
   *
   * @param decisions the decision path
   * @param idempotency idempotency records
   * @param rates FX rates to RWF
   * @param recorder durable events, for the idempotency-conflict audit
   * @param clock clock
   * @param duplicateWait how long a duplicate waits for the first submission's result
   */
  public IngestService(
      DecisionService decisions,
      IdempotencyStore idempotency,
      FxRatePort rates,
      EventRecorder recorder,
      Clock clock,
      Duration duplicateWait) {
    this(decisions, idempotency, rates, recorder, clock, duplicateWait, DecisionMetrics.NONE);
  }

  /**
   * Creates the service with stage metrics.
   *
   * @param decisions the decision path
   * @param idempotency idempotency records
   * @param rates FX rates to RWF
   * @param recorder durable events, for the idempotency-conflict audit
   * @param clock clock
   * @param duplicateWait how long a duplicate waits for the first submission's result
   * @param metrics stage metrics
   */
  public IngestService(
      DecisionService decisions,
      IdempotencyStore idempotency,
      FxRatePort rates,
      EventRecorder recorder,
      Clock clock,
      Duration duplicateWait,
      DecisionMetrics metrics) {
    this.decisions = Objects.requireNonNull(decisions, "decisions");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.rates = Objects.requireNonNull(rates, "rates");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.duplicateWait = Objects.requireNonNull(duplicateWait, "duplicateWait");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  /**
   * Handles one submission.
   *
   * @param principal the authenticated key (with {@code ingest:write})
   * @param body the parsed body
   * @param receivedNanos {@link System#nanoTime()} at receipt
   * @return the outcome
   */
  public Outcome ingest(ApiPrincipal principal, JsonNode body, long receivedNanos) {
    Instant received = clock.instant();
    RequestValidator.Result validation = RequestValidator.validate(body, received);
    if (validation.request() == null) {
      return new Invalid(validation);
    }
    return ingest(principal, validation.request(), received, receivedNanos);
  }

  /**
   * Handles one already-validated request (batch items are validated one by one).
   *
   * @param principal the authenticated key
   * @param request the validated request
   * @param received receipt time
   * @param receivedNanos {@link System#nanoTime()} at receipt
   * @return the outcome
   */
  public Outcome ingest(
      ApiPrincipal principal, IngestRequest request, Instant received, long receivedNanos) {
    UUID institution = principal.institutionId();
    Optional<BigDecimal> rate =
        rates.rwfPerUnit(
            request.amount().currency(),
            request.transactionTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
    if (rate.isEmpty()) {
      return new Unavailable("no FX rate for " + request.amount().currency());
    }
    byte[] fingerprint = Fingerprints.of(request);
    long deadline = System.nanoTime() + duplicateWait.toNanos();
    while (true) {
      long mark = System.nanoTime();
      IdempotencyStore.Claim claim =
          idempotency.claim(institution, request.transactionId(), fingerprint);
      metrics.stage("idempotency_claim", System.nanoTime() - mark);
      switch (claim) {
        case IdempotencyStore.Replay replay -> {
          return new Decided(replay.response(), true);
        }
        case IdempotencyStore.Conflict conflict -> {
          recorder.record(
              List.of(
                  new DecisionEvent.IdempotencyConflict(
                      UUID.randomUUID(),
                      institution,
                      request.transactionId(),
                      principal.apiKeyId(),
                      clock.instant())));
          return new Conflict();
        }
        case IdempotencyStore.InFlight inFlight -> {
          if (System.nanoTime() > deadline) {
            return new Unavailable("the same submission is still being decided");
          }
          LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        }
        case IdempotencyStore.Claimed claimed -> {
          return decide(
              institution, request, rate.get(), fingerprint, claimed, received, receivedNanos);
        }
      }
    }
  }

  private Outcome decide(
      UUID institution,
      IngestRequest r,
      BigDecimal rate,
      byte[] fingerprint,
      IdempotencyStore.Claimed claim,
      Instant received,
      long receivedNanos) {
    IngestDecision decision;
    try {
      Transaction transaction =
          new Transaction(
              institution,
              r.transactionId(),
              r.accountToken(),
              r.counterpartyToken(),
              r.amount(),
              rwf(r.amount(), rate),
              r.channel(),
              r.merchantCategoryCode(),
              r.latitude(),
              r.longitude(),
              r.deviceToken(),
              r.agentToken(),
              r.counterpartyCountry(),
              r.transactionTimestamp(),
              received);
      decision = decisions.decide(transaction, fingerprint, receivedNanos);
    } catch (RecorderOutcomeUnknownException unknown) {
      // The decision may still become durable: a retry must consult it, never decide afresh.
      idempotency.uncertain(institution, r.transactionId(), claim);
      return new Unavailable(unknown.getClass().getSimpleName());
    } catch (RuntimeException notRecorded) {
      // DecisionService records nothing unless it returns (or throws the exception above).
      idempotency.release(institution, r.transactionId(), claim);
      return new Unavailable(notRecorded.getClass().getSimpleName());
    }
    // The decision is durable and stands. Completing never fails the request: without Redis the
    // store falls back and later verifies new claims against PostgreSQL (ADR 0067).
    long mark = System.nanoTime();
    byte[] standing =
        idempotency.complete(
            institution, r.transactionId(), claim, fingerprint, DecisionResponses.render(decision));
    metrics.stage("idempotency_complete", System.nanoTime() - mark);
    return new Decided(standing, false);
  }

  /**
   * The RWF amount, four decimals, at least the smallest storable amount.
   *
   * @param amount amount in its currency
   * @param rate RWF per unit
   * @return the RWF amount
   */
  static BigDecimal rwf(Money amount, BigDecimal rate) {
    BigDecimal rwf =
        amount.amount().multiply(rate).setScale(Money.STORAGE_SCALE, RoundingMode.HALF_EVEN);
    return rwf.signum() > 0 ? rwf : Money.SMALLEST_STORABLE_MAGNITUDE;
  }
}
