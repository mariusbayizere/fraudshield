package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The instance's sockets and the limits on them (ADR 0115 point 8). Reads made on every routed
 * record ({@link #sessionsOf}) are lock-free; changes, which happen at the rate of handshakes and
 * CONNECTs, are serialised.
 */
final class SessionRegistry {

  private static final long MINUTE_NANOS = 60_000_000_000L;

  private final RealtimeSettings settings;
  private final InstanceBudget budget;
  private final LongSupplier nanoTime;
  private final Map<String, RealtimeSession> sockets = new ConcurrentHashMap<>();
  private final Map<UUID, Set<RealtimeSession>> byInstitution = new ConcurrentHashMap<>();
  private final Map<UUID, Deque<RealtimeSession>> byUser = new HashMap<>();
  private final Map<Binding.Pair, Integer> pairs = new HashMap<>();
  private final Map<UUID, Deque<Long>> connects = new HashMap<>();
  private final Map<String, Deque<Long>> handshakes = new HashMap<>();
  private final Map<String, Deque<Long>> reserved = new HashMap<>();
  private int authenticated;

  SessionRegistry(RealtimeSettings settings, InstanceBudget budget, LongSupplier nanoTime) {
    this.settings = settings;
    this.budget = budget;
    this.nanoTime = nanoTime;
  }

  /** Why a handshake is refused before the upgrade (point 8), or null to accept it. */
  enum Refusal {
    INSTANCE_FULL,
    PENDING_FULL,
    ADDRESS_PENDING,
    ADDRESS_RATE
  }

  /**
   * Counts a handshake from {@code address} and says whether it may proceed.
   *
   * @param address the client address
   * @return the refusal, or empty to accept
   */
  synchronized Optional<Refusal> admit(String address) {
    if (authenticated >= settings.sessionsPerInstance()) {
      return Optional.of(Refusal.INSTANCE_FULL);
    }
    // A slot is reserved at admission and released when the socket is counted (opened), so
    // concurrent handshakes cannot all pass before any is counted; an unused reservation lapses
    // after the time allowed to CONNECT.
    long now = nanoTime.getAsLong();
    long lapse = settings.connectTimeout().toNanos();
    reserved.values().forEach(r -> r.removeIf(at -> now - at >= lapse));
    reserved.values().removeIf(Deque::isEmpty);
    int pending = 0;
    int pendingHere = 0;
    for (RealtimeSession s : sockets.values()) {
      if (!s.authenticated()) {
        pending++;
        if (s.address() != null && s.address().equals(address)) {
          pendingHere++;
        }
      }
    }
    for (Map.Entry<String, Deque<Long>> r : reserved.entrySet()) {
      pending += r.getValue().size();
      if (r.getKey().equals(address)) {
        pendingHere += r.getValue().size();
      }
    }
    if (pending >= settings.pendingPerInstance()) {
      return Optional.of(Refusal.PENDING_FULL);
    }
    if (pendingHere >= settings.pendingPerAddress()) {
      return Optional.of(Refusal.ADDRESS_PENDING);
    }
    if (!allowed(handshakes, address, settings.handshakesPerAddressPerMinute())) {
      return Optional.of(Refusal.ADDRESS_RATE);
    }
    record(handshakes, address);
    reserved.computeIfAbsent(address, a -> new ArrayDeque<>()).addLast(now);
    return Optional.empty();
  }

  synchronized void opened(RealtimeSession session) {
    sockets.put(session.id(), session);
    Deque<Long> mine = reserved.get(session.address());
    if (mine != null) {
      mine.pollFirst();
      if (mine.isEmpty()) {
        reserved.remove(session.address());
      }
    }
  }

  /** Why a CONNECT is refused (point 8). */
  enum ConnectRefusal {
    /** The socket closed while its CONNECT was being authenticated: nothing to bind. */
    CLOSED,
    RATE_LIMITED,
    INSTANCE_FULL,
    PAIRS_FULL
  }

  /**
   * Binds an authenticated session, enforcing the per-user, per-instance and per-pair limits. A
   * user at their session limit loses their oldest session, which the caller closes.
   *
   * @param session the session
   * @param binding its binding
   * @param evicted receives the session the user's limit evicts
   * @return the refusal, or empty when bound
   */
  synchronized Optional<ConnectRefusal> bind(
      RealtimeSession session, Binding binding, List<RealtimeSession> evicted) {
    // The close callback may run on another thread while the CONNECT was being authenticated;
    // a socket it has already forgotten must never be counted (code review 2, MAJOR 1).
    if (!sockets.containsKey(session.id())) {
      return Optional.of(ConnectRefusal.CLOSED);
    }
    if (!allowed(connects, binding.userId(), settings.connectsPerUserPerMinute())) {
      return Optional.of(ConnectRefusal.RATE_LIMITED);
    }
    if (authenticated >= settings.sessionsPerInstance()) {
      return Optional.of(ConnectRefusal.INSTANCE_FULL);
    }
    Binding.Pair pair = binding.pair();
    if (!pairs.containsKey(pair) && pairs.size() >= settings.pairsPerInstance()) {
      return Optional.of(ConnectRefusal.PAIRS_FULL);
    }
    Deque<RealtimeSession> mine = byUser.computeIfAbsent(binding.userId(), u -> new ArrayDeque<>());
    if (mine.size() >= settings.sessionsPerUser()) {
      RealtimeSession oldest = mine.peekFirst();
      unbind(oldest);
      evicted.add(oldest);
    }
    session.bind(binding);
    mine.addLast(session);
    pairs.merge(pair, 1, Integer::sum);
    byInstitution
        .computeIfAbsent(binding.institutionId(), i -> ConcurrentHashMap.newKeySet())
        .add(session);
    authenticated++;
    // Point 8 counts accepted CONNECTs only.
    record(connects, binding.userId());
    return Optional.empty();
  }

  /**
   * Replaces a bound session's token version and expiry after re-authentication (point 3).
   *
   * @param session the session
   * @param binding the new binding, with the same user, institution, role and sid
   */
  synchronized void rebind(RealtimeSession session, Binding binding) {
    session.bind(binding);
  }

  /**
   * Forgets a closed socket.
   *
   * @param session the session
   */
  synchronized void closed(RealtimeSession session) {
    sockets.remove(session.id());
    unbind(session);
  }

  private void unbind(RealtimeSession session) {
    Binding binding = session.binding();
    if (binding == null) {
      return;
    }
    Deque<RealtimeSession> mine = byUser.get(binding.userId());
    if (mine == null || !mine.remove(session)) {
      return;
    }
    authenticated--;
    pairs.computeIfPresent(binding.pair(), (p, n) -> n == 1 ? null : n - 1);
    // An institution with no local session leaves the map, so the figures stop for it (6.3).
    byInstitution.computeIfPresent(
        binding.institutionId(),
        (id, sessions) -> {
          sessions.remove(session);
          return sessions.isEmpty() ? null : sessions;
        });
    if (mine.isEmpty()) {
      byUser.remove(binding.userId());
    }
  }

  /**
   * The bound sessions of one institution: the only sessions an event of that institution can reach
   * (point 4).
   *
   * @param institutionId the institution
   * @return its sessions
   */
  Collection<RealtimeSession> sessionsOf(UUID institutionId) {
    Set<RealtimeSession> found = byInstitution.get(institutionId);
    return found == null ? List.of() : found;
  }

  /**
   * Institutions with a local session.
   *
   * @return their ids
   */
  Set<UUID> institutions() {
    return Set.copyOf(byInstitution.keySet());
  }

  /**
   * Every socket, authenticated or not.
   *
   * @return a snapshot
   */
  List<RealtimeSession> all() {
    return List.copyOf(sockets.values());
  }

  /**
   * Authenticated sessions.
   *
   * @return the count
   */
  synchronized int authenticated() {
    return authenticated;
  }

  /**
   * Distinct (user, sid) pairs.
   *
   * @return the count
   */
  synchronized int pairs() {
    return pairs.size();
  }

  /**
   * The sessions to close, largest queue first, until the instance is below 75 % of its budget
   * (point 8).
   *
   * @return the sessions to shed
   */
  List<RealtimeSession> toShed() {
    if (!budget.exceeded()) {
      return List.of();
    }
    List<RealtimeSession> largest = new ArrayList<>(sockets.values());
    largest.sort(Comparator.comparingLong(RealtimeSession::queuedBytes).reversed());
    List<RealtimeSession> shed = new ArrayList<>();
    long remaining = budget.queued();
    long lowWater = budget.lowWater();
    for (RealtimeSession s : largest) {
      if (remaining < lowWater) {
        break;
      }
      remaining -= s.queuedBytes();
      shed.add(s);
    }
    return shed;
  }

  /** Whether one more event fits the key's one-minute window; prunes, records nothing. */
  private <K> boolean allowed(Map<K, Deque<Long>> windows, K key, int perMinute) {
    long now = nanoTime.getAsLong();
    Deque<Long> window = windows.get(key);
    if (window == null) {
      return perMinute > 0;
    }
    while (!window.isEmpty() && now - window.peekFirst() >= MINUTE_NANOS) {
      window.pollFirst();
    }
    return window.size() < perMinute;
  }

  private <K> void record(Map<K, Deque<Long>> windows, K key) {
    long now = nanoTime.getAsLong();
    windows.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
    if (windows.size() > 10_000) {
      windows.values().removeIf(w -> w.isEmpty() || now - w.peekLast() >= MINUTE_NANOS);
    }
  }
}
