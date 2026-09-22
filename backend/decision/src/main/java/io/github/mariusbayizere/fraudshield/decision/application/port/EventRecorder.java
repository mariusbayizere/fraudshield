package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import java.util.List;

/**
 * Durable recording of decision events (D-15): returns only when the events are on local disk
 * (fsynced), from where they are published to Kafka and written to PostgreSQL asynchronously.
 */
public interface EventRecorder {

  /**
   * Records events as one unit.
   *
   * @param events the events of one decision
   * @throws RecorderUnavailableException when the spool is full or failing; nothing was recorded
   */
  void record(List<DecisionEvent> events);
}
