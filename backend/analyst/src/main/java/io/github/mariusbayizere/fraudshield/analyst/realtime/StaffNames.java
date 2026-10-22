package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Staff names for the frames (ADR 0115 point 6.2): Kafka carries ids only, and the fan-out turns an
 * assignee's id into the console's {@code StaffReference} from a per-institution cache. A miss is
 * resolved off the poll thread by a small executor with a bounded queue, in a tenant transaction
 * with a short timeout; a name that cannot be resolved drops the event, so a frame is never sent
 * with a wrong or missing assignee.
 */
final class StaffNames implements AutoCloseable {

  private static final String NAME =
      "SELECT id, first_name, last_name, role FROM users WHERE id = ?";

  private record Cached(Map<String, Object> reference, long expiresNanos) {}

  private record Key(UUID institutionId, UUID userId) {}

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final long ttlNanos;
  private final long timeoutMillis;
  private final LongSupplier nanoTime;
  private final ThreadPoolExecutor resolver;
  private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong unresolved = new AtomicLong();

  StaffNames(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      RealtimeSettings settings,
      LongSupplier nanoTime) {
    this.tenants = tenants;
    this.jdbc = jdbc;
    this.ttlNanos = settings.nameCacheTtl().toNanos();
    this.timeoutMillis = settings.resolverTimeout().toMillis();
    this.nanoTime = nanoTime;
    this.resolver =
        new ThreadPoolExecutor(
            settings.resolverThreads(),
            settings.resolverThreads(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(settings.resolverQueue()),
            Thread.ofPlatform().name("fs-realtime-names-", 0).daemon().factory());
  }

  /**
   * The cached reference, if fresh.
   *
   * @param institutionId the institution
   * @param userId the user
   * @return the reference
   */
  Optional<Map<String, Object>> cached(UUID institutionId, UUID userId) {
    Cached hit = cache.get(new Key(institutionId, userId));
    if (hit == null || nanoTime.getAsLong() - hit.expiresNanos() >= 0) {
      return Optional.empty();
    }
    return Optional.of(hit.reference());
  }

  /**
   * Resolves a miss off the calling thread and hands the reference to {@code then}; drops it,
   * counted, when it cannot.
   *
   * @param institutionId the institution
   * @param userId the user
   * @param then told the reference once resolved
   */
  void resolve(UUID institutionId, UUID userId, Consumer<Map<String, Object>> then) {
    misses.incrementAndGet();
    try {
      resolver.execute(
          () -> {
            Optional<Map<String, Object>> found;
            try {
              found = tenants.inTenant(institutionId, () -> load(userId));
            } catch (RuntimeException unavailable) {
              found = Optional.empty();
            }
            if (found.isEmpty()) {
              unresolved.incrementAndGet();
              return;
            }
            cache.put(
                new Key(institutionId, userId),
                new Cached(found.get(), nanoTime.getAsLong() + ttlNanos));
            then.accept(found.get());
          });
    } catch (RejectedExecutionException full) {
      unresolved.incrementAndGet();
    }
  }

  private Optional<Map<String, Object>> load(UUID userId) {
    jdbc.queryForList("SELECT set_config('statement_timeout', ?, true)", timeoutMillis + "ms");
    List<Map<String, Object>> rows = jdbc.queryForList(NAME, userId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> row = rows.getFirst();
    Map<String, Object> reference = new LinkedHashMap<>();
    reference.put("user_id", row.get("id").toString());
    reference.put("first_name", row.get("first_name"));
    reference.put("last_name", row.get("last_name"));
    reference.put("role", row.get("role"));
    return Optional.of(reference);
  }

  long misses() {
    return misses.get();
  }

  long unresolved() {
    return unresolved.get();
  }

  @Override
  public void close() {
    resolver.shutdownNow();
  }
}
