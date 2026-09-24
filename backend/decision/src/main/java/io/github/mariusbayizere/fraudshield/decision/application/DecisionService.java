package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThreshold;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatusPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.ConfigurationPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.CustomerNotificationPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.FreezePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderOutcomeUnknownException;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScorerUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionEngine;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionInputs;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The synchronous decision path (C.2 steps 3–10): freeze flag, score or fallback, rules, circuit
 * breaker, decision, then side effects recorded durably before the response (D-13, D-15).
 *
 * <p>The account's behaviour reaches this class only as the feature values the scorer returns; the
 * scorer reads the feature store and is its only writer (ADR 0033). Nothing here computes or writes
 * a model feature (ADR 0061).
 *
 * <p>Events are recorded to the fsynced spool <em>before</em> the fast-path Redis state is written,
 * so every decision a client can see is durable. If the process dies between the two, the Redis
 * state is missing and a retry is decided again; the PostgreSQL writer keeps the first decision
 * (unique constraints) and Kafka consumers order by decision sequence (ADR 0011).
 */
public final class DecisionService {

  private final ConfigurationPort configuration;
  private final AccountStatusPort accounts;
  private final ScoringPort scorer;
  private final FreezePort freezes;
  private final CircuitBreakerPort breakers;
  private final HoldSchedulePort holds;
  private final DecisionStatePort states;
  private final EventRecorder recorder;
  private final CustomerNotificationPolicy notifications;
  private final DecisionMetrics metrics;
  private final DecisionSettings settings;
  private final Clock clock;
  private final java.util.concurrent.Executor afterResponse;

  /**
   * Creates the service.
   *
   * @param configuration thresholds, rules and breaker settings
   * @param accounts account freeze flags
   * @param scorer ML scorer
   * @param freezes account freeze counter
   * @param breakers MCC circuit breakers
   * @param holds hold deadline schedule
   * @param states latest decision state per transaction
   * @param recorder durable event spool
   * @param notifications customer SMS composer
   * @param metrics metrics
   * @param settings decision settings
   * @param clock clock (injectable for deterministic timer tests)
   */
  public DecisionService(
      ConfigurationPort configuration,
      AccountStatusPort accounts,
      ScoringPort scorer,
      FreezePort freezes,
      CircuitBreakerPort breakers,
      HoldSchedulePort holds,
      DecisionStatePort states,
      EventRecorder recorder,
      CustomerNotificationPolicy notifications,
      DecisionMetrics metrics,
      DecisionSettings settings,
      Clock clock) {
    this(
        configuration,
        accounts,
        scorer,
        freezes,
        breakers,
        holds,
        states,
        recorder,
        notifications,
        metrics,
        settings,
        clock,
        Runnable::run);
  }

  /**
   * Creates the service with an executor for the work done after the response.
   *
   * @param configuration thresholds, rules and breaker settings
   * @param accounts account freeze flags
   * @param scorer ML scorer
   * @param freezes account freeze counter
   * @param breakers MCC circuit breakers
   * @param holds hold deadline schedule
   * @param states latest decision state per transaction
   * @param recorder durable event spool
   * @param notifications customer SMS composer
   * @param metrics metrics
   * @param settings decision settings
   * @param clock clock
   * @param afterResponse runs the MCC counting off the request path
   */
  public DecisionService(
      ConfigurationPort configuration,
      AccountStatusPort accounts,
      ScoringPort scorer,
      FreezePort freezes,
      CircuitBreakerPort breakers,
      HoldSchedulePort holds,
      DecisionStatePort states,
      EventRecorder recorder,
      CustomerNotificationPolicy notifications,
      DecisionMetrics metrics,
      DecisionSettings settings,
      Clock clock,
      java.util.concurrent.Executor afterResponse) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.scorer = Objects.requireNonNull(scorer, "scorer");
    this.freezes = Objects.requireNonNull(freezes, "freezes");
    this.breakers = Objects.requireNonNull(breakers, "breakers");
    this.holds = Objects.requireNonNull(holds, "holds");
    this.states = Objects.requireNonNull(states, "states");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.afterResponse = Objects.requireNonNull(afterResponse, "afterResponse");
  }

  /**
   * Decides a validated transaction.
   *
   * @param transaction the transaction
   * @param requestFingerprint SHA-256 of the canonical request (FR-01-03)
   * @param receivedNanos {@link System#nanoTime()} when the request arrived
   * @return the machine response
   * @throws RecorderUnavailableException when the decision could not be made durable; nothing was
   *     recorded, as with every exception from this method other than the next
   * @throws RecorderOutcomeUnknownException when the spool took the events but did not confirm them
   *     in time; the decision may still become durable
   */
  public IngestDecision decide(
      Transaction transaction, byte[] requestFingerprint, long receivedNanos) {
    UUID institution = transaction.institutionId();
    long mark = System.nanoTime();
    ConfigurationPort.Thresholds thresholds = configuration.thresholds(institution);
    ChannelThreshold threshold = thresholds.thresholds().byChannel().get(transaction.channel());
    mark = stage("configuration", mark);
    boolean frozen = accounts.frozen(institution, transaction.accountToken());
    mark = stage("account_status", mark);

    Scores scores = score(transaction, threshold);
    final Scoring scoring = scores.scoring();
    final DecisionEvent.ScoringRecord record = scores.record();

    mark = stage("score", mark);
    RuleSet.Evaluation rules =
        configuration.rules(institution).evaluate(new FeatureSubject(transaction, scoring));
    boolean breakerOpen = breakers.isOpen(institution, transaction.merchantCategoryCode());
    Instant decidedAt = clock.instant();
    DecisionOutcome outcome =
        DecisionEngine.decide(
            new DecisionInputs(
                transaction,
                scoring,
                threshold,
                frozen,
                breakerOpen,
                rules,
                settings.anomalyReviewThreshold(),
                decidedAt,
                settings.reviewWindow()));
    DecisionState state =
        DecisionState.initial(
            institution,
            transaction.transactionId(),
            outcome.decision(),
            decidedAt,
            outcome.reasonCodes(),
            outcome.reviewDeadlineAt());
    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - receivedNanos);

    List<DecisionEvent> events = new ArrayList<>();
    events.add(
        new DecisionEvent.TransactionDecided(
            transaction,
            record.withTier(outcome.tier(), outcome.alert().isPresent()),
            outcome,
            state,
            thresholds.version(),
            requestFingerprint,
            latencyMs));
    outcome
        .alert()
        .ifPresent(
            tier ->
                events.add(
                    new DecisionEvent.AlertRaised(
                        UUID.randomUUID(),
                        institution,
                        transaction,
                        tier,
                        record.ensembleScore(),
                        record.fallback() ? null : record.anomalyScore(),
                        record.scoringResultId(),
                        outcome.reviewDeadlineAt(),
                        outcome.reasonCodes(),
                        decidedAt)));
    if (outcome.autoBlock()) {
      addAutoBlock(events, transaction, record, outcome, scoring, decidedAt);
    }

    mark = stage("decide", mark);
    recorder.record(events);
    mark = stage("spool", mark);

    // The decision is durable from here on and stands: a failed fast-state write must not turn it
    // into an error the client would retry (ADR 0067). PostgreSQL covers both writes: decision
    // states are read from it on a miss, and overdue holds are reconciled from it (V62).
    try {
      states.save(state);
    } catch (RuntimeException failed) {
      metrics.stateWriteFailed("decision_state");
    }
    if (outcome.decision() == Decision.HOLD) {
      try {
        holds.schedule(
            new HoldSchedulePort.DueHold(
                institution,
                transaction.transactionId(),
                transaction.channel().name(),
                threshold.timeoutPolicy(),
                outcome.reviewDeadlineAt()));
      } catch (RuntimeException failed) {
        metrics.stateWriteFailed("hold_schedule");
      }
    }
    stage("fast_state_writes", mark);
    // After the response (C.2): the MCC counts are not needed to answer this request. The feature
    // store is updated by the scorer, not here (ADR 0033).
    afterResponse.execute(
        () -> {
          long started = System.nanoTime();
          try {
            breakers.count(
                institution, transaction.merchantCategoryCode(), decidedAt, outcome.autoBlock());
          } finally {
            metrics.stage("breaker_count", System.nanoTime() - started);
          }
        });
    if (record.featureStoreDegraded()) {
      metrics.featureStoreDegraded();
    }
    metrics.decided(transaction, outcome, System.nanoTime() - receivedNanos);

    return new IngestDecision(
        transaction.transactionId(),
        outcome.decision(),
        outcome.tier(),
        outcome.reasonCodes(),
        record.scoringResultId(),
        record.modelVersion(),
        latencyMs,
        outcome.reviewDeadlineAt(),
        outcome.fallback());
  }

  private long stage(String name, long since) {
    long now = System.nanoTime();
    metrics.stage(name, now - since);
    return now;
  }

  private record Scores(Scoring scoring, DecisionEvent.ScoringRecord record) {}

  private Scores score(Transaction transaction, ChannelThreshold threshold) {
    try {
      ScoringPort.Scored scored = scorer.score(transaction, List.of());
      return new Scores(scored.scoring(), scored.record());
    } catch (ScorerUnavailableException unavailable) {
      Scoring.Fallback fallback = settings.fallbackRules().score(transaction);
      return new Scores(fallback, fallbackRecord(fallback, threshold));
    }
  }

  private void addAutoBlock(
      List<DecisionEvent> events,
      Transaction transaction,
      DecisionEvent.ScoringRecord record,
      DecisionOutcome outcome,
      Scoring scoring,
      Instant decidedAt) {
    UUID block = UUID.randomUUID();
    events.add(
        new DecisionEvent.AutoBlocked(
            block,
            transaction.institutionId(),
            transaction.transactionId(),
            record.scoringResultId(),
            transaction.accountToken(),
            decidedAt,
            String.join(",", outcome.reasonCodes())));
    events.add(notifications.compose(transaction, block, scoring, decidedAt));
    FreezePort.FreezeCheck check =
        freezes.recordHigh(
            transaction.institutionId(),
            transaction.accountToken(),
            transaction.transactionId(),
            decidedAt);
    if (check.frozeNow()) {
      events.add(
          new DecisionEvent.AccountFrozen(
              transaction.institutionId(),
              transaction.accountToken(),
              block,
              check.highDecisionsInWindow(),
              decidedAt));
    }
  }

  /**
   * The persisted record of a fallback decision. The fallback has no probability; the record
   * carries the lower edge of its tier's interval as a nominal score (0 for LOW), so expected-loss
   * ordering and tier stay consistent, and {@code ml_unavailable_fallback} marks it as nominal (ADR
   * 0061).
   */
  static DecisionEvent.ScoringRecord fallbackRecord(
      Scoring.Fallback fallback, ChannelThreshold threshold) {
    double nominal = nominalScore(fallback.tier(), threshold);
    return new DecisionEvent.ScoringRecord(
        fallback.scoringResultId(),
        nominal,
        nominal,
        nominal,
        0,
        0,
        fallback.tier(),
        null,
        null,
        Map.of(),
        fallback.modelVersion(),
        fallback.modelVersion(),
        0,
        true,
        null,
        fallback.tier() != RiskTier.LOW,
        false,
        null);
  }

  static double nominalScore(RiskTier tier, ChannelThreshold threshold) {
    return switch (tier) {
      case LOW -> 0;
      case MEDIUM -> threshold.medium().doubleValue();
      case HIGH -> threshold.high().doubleValue();
    };
  }
}
