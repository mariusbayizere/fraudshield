package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderOutcomeUnknownException;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Records a decision's events as one fsynced spool record (D-15). */
public final class SpoolingEventRecorder implements EventRecorder {

  private final DurableSpool spool;
  private final Duration timeout;

  /**
   * Creates the recorder.
   *
   * @param spool the spool
   * @param timeout how long a decision waits for its fsync before the request fails
   */
  public SpoolingEventRecorder(DurableSpool spool, Duration timeout) {
    this.spool = Objects.requireNonNull(spool, "spool");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public void record(List<DecisionEvent> events) {
    if (events.isEmpty()) {
      return;
    }
    try {
      spool.appendAndWait(FactCodec.encode(events), timeout);
    } catch (SpoolFullException e) {
      throw new RecorderUnavailableException(e.getMessage(), e);
    } catch (SpoolOutcomeUnknownException e) {
      throw new RecorderOutcomeUnknownException(e.getMessage(), e);
    }
  }
}
