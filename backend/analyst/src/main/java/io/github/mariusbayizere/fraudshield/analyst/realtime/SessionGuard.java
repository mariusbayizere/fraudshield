package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.analyst.performance.Presence;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps every session honest (ADR 0115 points 2, 3, 5 and 8), on one thread of its own.
 *
 * <p>It covers:
 *
 * <ul>
 *   <li>revocation by push: {@link SessionStateCache}'s change listener hands it user or session
 *       ids after the local entry is removed, and it re-checks the matching sessions;
 *   <li>the revocation backstop: every distinct (user, sid) pair is re-checked each period, with
 *       database loads limited by a gate; a pair unanswered for two periods is closed;
 *   <li>Redis: probed every second; while unavailable every session is closed and handshakes are
 *       refused, until two probes in a row succeed;
 *   <li>the 5 s to CONNECT, token expiry, client heart-beat silence, the consumer's stall and the
 *       instance budget;
 *   <li>presence, for the figures' {@code analysts_online} (ADR 0117 point 4).
 * </ul>
 */
final class SessionGuard implements SessionStateCache.ChangeListener, AutoCloseable {

  /** The roles counted as analysts online (ADR 0117 point 4). */
  static final Set<StaffRole> PRESENT =
      EnumSet.of(StaffRole.ANALYST, StaffRole.SENIOR_ANALYST, StaffRole.RISK_OFFICER);

  private static final Logger LOG = LoggerFactory.getLogger(SessionGuard.class);
  private static final SecureRandom JITTER = new SecureRandom();

  private final SessionRegistry registry;
  private final SessionStateCache sessions;
  private final SafeRedis redis;
  private final RealtimeSettings settings;
  private final BooleanSupplier consumerHealthy;
  private final ScheduledExecutorService thread;
  private final ScheduledExecutorService checks;
  private final LongSupplier nanoTime;
  private final Clock clock;
  private final Map<StaffClaims, Long> unknownSince = new HashMap<>();
  private final LoadGate gate;
  private volatile boolean redisOpen = true;
  private int consecutivePings;
  private volatile boolean shuttingDown;

  SessionGuard(
      SessionRegistry registry,
      SessionStateCache sessions,
      SafeRedis redis,
      RealtimeSettings settings,
      BooleanSupplier consumerHealthy,
      ScheduledExecutorService thread,
      ScheduledExecutorService checks,
      LongSupplier nanoTime,
      Clock clock) {
    this.registry = registry;
    this.sessions = sessions;
    this.redis = redis;
    this.settings = settings;
    this.consumerHealthy = consumerHealthy;
    this.thread = thread;
    this.checks = checks;
    this.nanoTime = nanoTime;
    this.clock = clock;
    this.gate = new LoadGate(settings.loadsPerSecond(), nanoTime);
  }

  /** Registers the change listener and starts the schedules. */
  void start() {
    sessions.addChangeListener(this);
    thread.scheduleWithFixedDelay(this::sweep, 250, 250, TimeUnit.MILLISECONDS);
    long ping = settings.redisPing().toMillis();
    thread.scheduleWithFixedDelay(this::probeRedis, ping, ping, TimeUnit.MILLISECONDS);
    long refresh = settings.presenceRefresh().toMillis();
    thread.scheduleWithFixedDelay(this::refreshPresence, refresh, refresh, TimeUnit.MILLISECONDS);
    scheduleBackstop();
  }

  // --------------------------------------------------------------------- revocation by push

  @Override
  public void versionChanged(UUID userId) {
    // Never blocks the pub/sub thread: the re-check runs on the guard's own thread (point 3).
    hand(() -> recheck(s -> s.binding().userId().equals(userId)));
  }

  @Override
  public void sessionEnded(UUID sessionId) {
    hand(() -> recheck(s -> s.binding().sessionId().equals(sessionId)));
  }

  /** Hands a re-check to the checks thread; during shutdown there is nothing left to close. */
  private void hand(Runnable check) {
    try {
      checks.execute(check);
    } catch (java.util.concurrent.RejectedExecutionException stopping) {
      LOG.debug("a revocation arrived during shutdown", stopping);
    }
  }

  private void recheck(java.util.function.Predicate<RealtimeSession> matching) {
    Map<StaffClaims, List<RealtimeSession>> grouped = new HashMap<>();
    for (RealtimeSession s : registry.all()) {
      if (s.authenticated() && matching.test(s)) {
        grouped.computeIfAbsent(s.binding().claims(), c -> new ArrayList<>()).add(s);
      }
    }
    grouped.forEach(this::check);
  }

  // ---------------------------------------------------------------------------- the backstop

  private void scheduleBackstop() {
    if (checks.isShutdown()) {
      return;
    }
    long period = settings.backstopPeriod().toMillis();
    long jitter = settings.backstopJitter().toMillis();
    long delay = period + JITTER.nextLong(-jitter, jitter + 1);
    checks.schedule(
        () -> {
          try {
            backstop();
          } finally {
            scheduleBackstop();
          }
        },
        delay,
        TimeUnit.MILLISECONDS);
  }

  /** Re-checks each distinct (user, sid) pair once (point 3). */
  void backstop() {
    Map<StaffClaims, List<RealtimeSession>> grouped = new HashMap<>();
    for (RealtimeSession s : registry.all()) {
      if (s.authenticated()) {
        grouped.computeIfAbsent(s.binding().claims(), c -> new ArrayList<>()).add(s);
      }
    }
    unknownSince.keySet().retainAll(grouped.keySet());
    grouped.forEach(this::check);
  }

  private void check(StaffClaims claims, List<RealtimeSession> bound) {
    SessionStateCache.Currency currency;
    try {
      currency = sessions.isCurrent(claims, gate);
    } catch (RuntimeException failed) {
      LOG.debug("a real-time session check failed; treated as unknown", failed);
      currency = SessionStateCache.Currency.UNKNOWN;
    }
    switch (currency) {
      case CURRENT -> unknownSince.remove(claims);
      case NOT_CURRENT -> {
        unknownSince.remove(claims);
        bound.forEach(s -> s.fail(StompFrames.Problem.UNAUTHORIZED, "session ended", null));
      }
      case UNKNOWN -> {
        long now = nanoTime.getAsLong();
        long since = unknownSince.computeIfAbsent(claims, c -> now);
        if (now - since >= 2 * settings.backstopPeriod().toNanos()) {
          // Fails closed, never open (point 3).
          unknownSince.remove(claims);
          bound.forEach(
              s ->
                  s.fail(
                      StompFrames.Problem.UNAVAILABLE, "session state could not be checked", null));
        }
      }
      default -> throw new IllegalStateException("unknown currency " + currency);
    }
  }

  // ---------------------------------------------------------------------------------- Redis

  /** Probes Redis; closes the channel when it is down, reopens after two good probes. */
  void probeRedis() {
    boolean answered = redis.ping();
    if (redisOpen) {
      if (!redis.available()) {
        redisOpen = false;
        consecutivePings = 0;
        closeAll(StompFrames.Problem.UNAVAILABLE, "real-time channel unavailable");
      }
      return;
    }
    consecutivePings = answered ? consecutivePings + 1 : 0;
    if (consecutivePings >= 2) {
      redisOpen = true;
    }
  }

  // ---------------------------------------------------------------------------------- sweeps

  /** The 5 s to CONNECT, expiry, heart-beat silence, the consumer and the budget. */
  void sweep() {
    long now = nanoTime.getAsLong();
    boolean consumerOk = consumerHealthy.getAsBoolean();
    for (RealtimeSession s : registry.all()) {
      if (!s.authenticated()) {
        if (now - s.openedNanos() >= settings.connectTimeout().toNanos()) {
          s.fail(StompFrames.Problem.UNAUTHORIZED, "no CONNECT within the time allowed", null);
        }
        continue;
      }
      if (!clock.instant().isBefore(s.binding().expiresAt())) {
        s.fail(StompFrames.Problem.UNAUTHORIZED, "access token expired", null);
        continue;
      }
      long expect = s.expectEveryMillis();
      if (expect > 0 && now - s.lastInboundNanos() > 2 * expect * 1_000_000L) {
        s.closeQuietly();
        continue;
      }
      if (!consumerOk) {
        s.fail(StompFrames.Problem.UNAVAILABLE, "real-time feed interrupted", null);
      }
    }
    for (RealtimeSession s : registry.toShed()) {
      s.fail(StompFrames.Problem.OVERFLOW, "the instance's outbound budget was exceeded", null);
    }
  }

  // -------------------------------------------------------------------------------- presence

  /**
   * Writes a user's presence now (point 2): only for the roles counted as analysts online.
   *
   * @param binding the session's binding
   */
  void present(Binding binding) {
    if (PRESENT.contains(binding.role())) {
      thread.execute(() -> mark(binding.institutionId(), binding.userId()));
    }
  }

  private void refreshPresence() {
    Map<UUID, Set<UUID>> users = new HashMap<>();
    for (RealtimeSession s : registry.all()) {
      Binding b = s.binding();
      if (b != null && PRESENT.contains(b.role())) {
        users.computeIfAbsent(b.institutionId(), i -> new java.util.HashSet<>()).add(b.userId());
      }
    }
    users.forEach((institution, ids) -> ids.forEach(user -> mark(institution, user)));
  }

  private void mark(UUID institutionId, UUID userId) {
    long expiry = clock.millis() + settings.presenceLifetime().toMillis();
    redis.addScored(
        Presence.key(institutionId),
        userId.toString(),
        expiry,
        settings.presenceLifetime().multipliedBy(2));
  }

  // ------------------------------------------------------------------------------- the gates

  /**
   * Whether handshakes may be accepted: Redis is up, the consumer is positioned and healthy, and
   * the instance is not stopping (points 3 and 5).
   *
   * @return true when open
   */
  boolean open() {
    return redisOpen && !shuttingDown && consumerHealthy.getAsBoolean();
  }

  /** Ends every session for shutdown (point 8). */
  void shutdown() {
    shuttingDown = true;
    closeAll(StompFrames.Problem.UNAVAILABLE, "server shutting down");
  }

  private void closeAll(StompFrames.Problem problem, String detail) {
    for (RealtimeSession s : registry.all()) {
      s.fail(problem, detail, null);
    }
  }

  @Override
  public void close() {
    thread.shutdownNow();
    checks.shutdownNow();
  }

  /** At most so many database loads a second (point 3); in-process and Redis answers are free. */
  static final class LoadGate implements SessionStateCache.LoadGate {
    private final int perSecond;
    private final LongSupplier nanoTime;
    private long windowStart;
    private int used;

    LoadGate(int perSecond, LongSupplier nanoTime) {
      this.perSecond = perSecond;
      this.nanoTime = nanoTime;
      this.windowStart = nanoTime.getAsLong();
    }

    @Override
    public synchronized boolean tryAcquire() {
      long now = nanoTime.getAsLong();
      if (now - windowStart >= Duration.ofSeconds(1).toNanos()) {
        windowStart = now;
        used = 0;
      }
      if (used >= perSecond) {
        return false;
      }
      used++;
      return true;
    }
  }
}
