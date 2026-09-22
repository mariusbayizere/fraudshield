package io.github.mariusbayizere.fraudshield.decision.testing;

import io.github.mariusbayizere.fraudshield.common.config.CircuitBreakerSettings;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatusPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.ConfigurationPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.CustomerNotificationPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionStatePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.FreezePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScorerUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.FeatureContribution;
import io.github.mariusbayizere.fraudshield.decision.domain.FreezePolicy;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** In-memory test doubles for every decision port. Test sources only, never production. */
public class InMemoryPorts
    implements AccountStatusPort,
        ScoringPort,
        FreezePort,
        CircuitBreakerPort,
        HoldSchedulePort,
        DecisionStatePort,
        EventRecorder,
        ConfigurationPort,
        CustomerNotificationPolicy {

  /** Events recorded, in order. */
  public final List<DecisionEvent> events = new ArrayList<>();

  /** Scheduled holds. */
  public final Map<UUID, DueHold> holds = new ConcurrentHashMap<>();

  /** Latest state per transaction. */
  public final Map<UUID, DecisionState> latest = new ConcurrentHashMap<>();

  /** Feature values the scorer double returns, as the real scorer reads them (ADR 0033). */
  public Map<String, FieldValue> features = Map.of();

  /** Whether the scorer double reports that it answered without the feature store. */
  public boolean featureStoreDegraded;

  /** Frozen accounts. */
  public final Set<String> frozen = new HashSet<>();

  /** Breaker state per MCC. */
  public final Map<Key, CircuitBreakerState> breakerStates = new HashMap<>();

  /** Window counts per MCC, returned as-is. */
  public final Map<Key, MccCircuitBreaker.WindowCounts> windowCounts = new HashMap<>();

  /** Fraud counts per MCC as counted by the decision path. */
  public final Map<Key, int[]> counted = new HashMap<>();

  /** The score to return per transaction; absent means the scorer is unavailable. */
  public Function<Transaction, Optional<Double>> scores = t -> Optional.of(0.1);

  /** Anomaly score returned with every model score. */
  public double anomaly = 0.1;

  /** When set, the recorder refuses. */
  public boolean recorderFull;

  /** Rules in force. */
  public RuleSet rules = RuleSet.EMPTY;

  /** Breaker settings in force. */
  public CircuitBreakerSettings breakerSettings =
      new CircuitBreakerSettings(
          new BigDecimal("0.05"), Duration.ofMinutes(15), 100, Duration.ofMinutes(60));

  /** Whether this instance wins leadership. */
  public boolean leader = true;

  private final Map<String, List<Instant>> highs = new HashMap<>();

  @Override
  public boolean frozen(UUID institutionId, String accountToken) {
    return frozen.contains(accountToken);
  }

  @Override
  public void record(List<DecisionEvent> batch) {
    if (recorderFull) {
      throw new RecorderUnavailableException("spool full (test double)", null);
    }
    events.addAll(batch);
  }

  @Override
  public Scored score(Transaction transaction, List<Money> limits) {
    Optional<Double> score = scores.apply(transaction);
    if (score.isEmpty()) {
      throw new ScorerUnavailableException("scorer down (test double)", null);
    }
    double value = score.get();
    List<FeatureContribution> top =
        value >= 0.6 ? List.of(new FeatureContribution("tx_count_60s", 1.0, true)) : List.of();
    Scoring.Model model =
        new Scoring.Model(UUID.randomUUID(), "fs-test-model", value, anomaly, top, features);
    return new Scored(
        model,
        new DecisionEvent.ScoringRecord(
            model.scoringResultId(),
            value,
            value,
            value,
            anomaly,
            -0.5,
            RiskTier.LOW,
            List.of(),
            null,
            Map.of(),
            "fs-test-model",
            "registry-test",
            3,
            false,
            null,
            false,
            featureStoreDegraded));
  }

  @Override
  public FreezeCheck recordHigh(UUID institutionId, String account, UUID tx, Instant at) {
    List<Instant> times = highs.computeIfAbsent(account, a -> new ArrayList<>());
    times.add(at);
    times.removeIf(t -> !t.isAfter(at.minus(FreezePolicy.WINDOW)));
    boolean freezes = FreezePolicy.freezes(times, at) && frozen.add(account);
    return new FreezeCheck(times.size(), freezes);
  }

  @Override
  public boolean isOpen(UUID institutionId, String mcc) {
    return breakerStates
        .getOrDefault(new Key(institutionId, mcc), CircuitBreakerState.CLOSED)
        .open();
  }

  @Override
  public void count(UUID institutionId, String mcc, Instant at, boolean fraud) {
    int[] c = counted.computeIfAbsent(new Key(institutionId, mcc), k -> new int[2]);
    c[0]++;
    c[1] += fraud ? 1 : 0;
  }

  @Override
  public void countConfirmedFraud(UUID institutionId, String mcc, Instant at) {
    counted.computeIfAbsent(new Key(institutionId, mcc), k -> new int[2])[1]++;
  }

  @Override
  public Collection<Key> active(Instant now) {
    return List.copyOf(windowCounts.keySet());
  }

  @Override
  public MccCircuitBreaker.WindowCounts counts(Key key, int window, Instant now) {
    return windowCounts.get(key);
  }

  @Override
  public CircuitBreakerState state(Key key) {
    return breakerStates.getOrDefault(key, CircuitBreakerState.CLOSED);
  }

  @Override
  public boolean compareAndSet(Key key, CircuitBreakerState expected, CircuitBreakerState next) {
    if (!state(key).equals(expected)) {
      return false;
    }
    breakerStates.put(key, next);
    return true;
  }

  @Override
  public void schedule(DueHold hold) {
    holds.put(hold.transactionId(), hold);
  }

  @Override
  public boolean cancel(UUID institutionId, UUID transactionId) {
    return holds.remove(transactionId) != null;
  }

  @Override
  public List<DueHold> claimDue(Instant now, int max) {
    List<DueHold> due = new ArrayList<>();
    for (DueHold hold : List.copyOf(holds.values())) {
      if (due.size() < max
          && !hold.deadline().isAfter(now)
          && holds.remove(hold.transactionId()) != null) {
        due.add(hold);
      }
    }
    return due;
  }

  @Override
  public boolean acquireLeadership(String instanceId) {
    return leader;
  }

  @Override
  public boolean save(DecisionState state) {
    DecisionState previous = latest.get(state.transactionId());
    int expected = previous == null ? 1 : previous.sequence() + 1;
    if (state.sequence() != expected) {
      return false;
    }
    latest.put(state.transactionId(), state);
    return true;
  }

  @Override
  public Optional<DecisionState> latest(UUID institutionId, UUID transactionId) {
    return Optional.ofNullable(latest.get(transactionId));
  }

  @Override
  public Thresholds thresholds(UUID institutionId) {
    return new Thresholds(1, Fixtures.defaultThresholds());
  }

  @Override
  public RuleSet rules(UUID institutionId) {
    return rules;
  }

  @Override
  public BreakerSettings breakerSettings(UUID institutionId) {
    return new BreakerSettings(1, breakerSettings);
  }

  @Override
  public DecisionEvent.CustomerNotificationRequested compose(
      Transaction transaction, UUID block, Scoring scoring, Instant at) {
    return new DecisionEvent.CustomerNotificationRequested(
        UUID.randomUUID(),
        transaction.institutionId(),
        transaction.accountToken(),
        block,
        "sms.auto_block",
        "en",
        false,
        "***dd01",
        transaction.amount(),
        "12:00 CAT",
        "REF12345",
        at);
  }

  /**
   * Events of one type.
   *
   * @param type event class
   * @param <T> event type
   * @return matching events in order
   */
  public <T extends DecisionEvent> List<T> eventsOf(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
