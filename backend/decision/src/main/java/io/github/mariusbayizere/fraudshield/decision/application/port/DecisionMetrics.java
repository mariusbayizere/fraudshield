package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;

/** Metrics the decision path reports (E.10); the adapter names them {@code fs_*}. */
public interface DecisionMetrics {

  /**
   * One decision ({@code fs_decisions_total}, {@code fs_decision_latency_seconds}).
   *
   * @param transaction the transaction
   * @param outcome the decision
   * @param latencyNanos server processing time
   */
  void decided(Transaction transaction, DecisionOutcome outcome, long latencyNanos);

  /**
   * A hold released at its deadline ({@code fs_alert_timeout_release_total}, D-10).
   *
   * @param channel channel
   */
  void timeoutRelease(String channel);

  /**
   * A hold processed later than its deadline plus tolerance (D-18: ±500 ms).
   *
   * @param lateMillis how late
   */
  void holdLateness(long lateMillis);

  /** Metrics that record nothing, for callers that have none. */
  DecisionMetrics NONE =
      new DecisionMetrics() {
        @Override
        public void decided(Transaction transaction, DecisionOutcome outcome, long latencyNanos) {}

        @Override
        public void timeoutRelease(String channel) {}

        @Override
        public void holdLateness(long lateMillis) {}
      };
}
