package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes the header figures to {@code /user/queue/figures} (ADR 0115 point 6.3). The figures are
 * institution-wide and the same for every role, so they are computed once per institution, at most
 * every 5 s, and only while the institution has a local session, when an alert event has passed, a
 * session connected or ended, 30 s have passed since a local disconnect (the departed user's
 * presence has expired), or 30 s have passed since the last computation.
 */
final class FiguresPusher implements StompEndpoint.Subscribed {

  private static final Logger LOG = LoggerFactory.getLogger(FiguresPusher.class);
  private static final long MIN_GAP_NANOS = 5_000_000_000L;
  private static final long QUIET_NANOS = 30_000_000_000L;

  private static final class State {
    volatile boolean dirty = true;
    volatile long computedNanos = Long.MIN_VALUE / 2;
    volatile long disconnectDueNanos = Long.MAX_VALUE;
  }

  private final SessionRegistry registry;
  private final Function<UUID, Map<String, Object>> stats;
  private final LongSupplier nanoTime;
  private final Clock clock;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();

  FiguresPusher(
      SessionRegistry registry,
      Function<UUID, Map<String, Object>> stats,
      LongSupplier nanoTime,
      Clock clock) {
    this.registry = registry;
    this.stats = stats;
    this.nanoTime = nanoTime;
    this.clock = clock;
  }

  /** An alert event of the institution passed through the instance. */
  void alertSeen(UUID institutionId) {
    state(institutionId).dirty = true;
  }

  @Override
  public void subscribed(RealtimeSession session, String destination) {
    if (StompEndpoint.FIGURES.equals(destination) && session.binding() != null) {
      state(session.binding().institutionId()).dirty = true;
    }
  }

  @Override
  public void ended(Binding binding) {
    State state = state(binding.institutionId());
    state.dirty = true;
    state.disconnectDueNanos = nanoTime.getAsLong() + QUIET_NANOS;
  }

  /** Recomputes and pushes where point 6.3 says to; runs every second on its own thread. */
  void tick() {
    long now = nanoTime.getAsLong();
    for (UUID institution : registry.institutions()) {
      State state = state(institution);
      if (now - state.computedNanos < MIN_GAP_NANOS) {
        continue;
      }
      boolean due =
          state.dirty
              || now - state.disconnectDueNanos >= 0
              || now - state.computedNanos >= QUIET_NANOS;
      if (!due) {
        continue;
      }
      state.dirty = false;
      if (now - state.disconnectDueNanos >= 0) {
        state.disconnectDueNanos = Long.MAX_VALUE;
      }
      state.computedNanos = now;
      push(institution);
    }
    states.keySet().retainAll(registry.institutions());
  }

  private void push(UUID institution) {
    Map<String, Object> figures;
    try {
      figures = stats.apply(institution);
    } catch (RuntimeException unavailable) {
      // The console's re-read of getDashboardStats repairs it (point 7).
      LOG.debug("the figures could not be computed; skipped", unavailable);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("as_of", clock.instant().toString());
    body.put("stats", figures);
    Outbound.Frame frame = StompFrames.event(StompEndpoint.FIGURES, AlertFrames.json(body), 0);
    for (RealtimeSession session : registry.sessionsOf(institution)) {
      if (session.subscribed(StompEndpoint.FIGURES) && !session.deliver(frame)) {
        session.fail(StompFrames.Problem.OVERFLOW, "too far behind", null);
      }
    }
  }

  private State state(UUID institutionId) {
    return states.computeIfAbsent(institutionId, i -> new State());
  }
}
