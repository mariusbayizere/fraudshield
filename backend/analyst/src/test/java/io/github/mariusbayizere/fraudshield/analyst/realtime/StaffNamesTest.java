package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Staff names for the frames: cached, resolved off the poll thread, or dropped (point 6.2). */
class StaffNamesTest {

  private static final UUID BANK = UUID.randomUUID();

  private final TenantTransactions tenants = mock(TenantTransactions.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AtomicLong nanos = new AtomicLong(1);

  @SuppressWarnings("unchecked")
  private StaffNames names() {
    when(tenants.inTenant(any(), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
    return new StaffNames(tenants, jdbc, RealtimeSettings.defaults(), nanos::get);
  }

  @Test
  @Tag("FR-04-02")
  void missIsResolvedCachedAndExpires() {
    UUID user = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), eq(user)))
        .thenReturn(
            List.of(
                Map.of(
                    "id", user, "first_name", "Amani", "last_name", "Uwase", "role", "ANALYST")));
    List<Map<String, Object>> resolved = new CopyOnWriteArrayList<>();
    try (StaffNames names = names()) {
      assertThat(names.cached(BANK, user)).isEmpty();
      names.resolve(BANK, user, resolved::add);
      await().atMost(Duration.ofSeconds(5)).until(() -> resolved.size() == 1);
      assertThat(resolved.getFirst())
          .containsEntry("user_id", user.toString())
          .containsEntry("first_name", "Amani")
          .containsEntry("role", "ANALYST")
          .doesNotContainKey("email");
      assertThat(names.cached(BANK, user)).contains(resolved.getFirst());
      assertThat(names.cached(UUID.randomUUID(), user)).as("per institution").isEmpty();
      nanos.addAndGet(Duration.ofSeconds(61).toNanos());
      assertThat(names.cached(BANK, user)).as("60 s").isEmpty();
      assertThat(names.misses()).isEqualTo(1);
    }
  }

  @Test
  @Tag("FR-04-02")
  void anUnresolvableNameDropsTheEvent() {
    UUID unknown = UUID.randomUUID();
    UUID failing = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), eq(unknown))).thenReturn(List.of());
    when(jdbc.queryForList(anyString(), eq(failing)))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));
    List<Map<String, Object>> resolved = new CopyOnWriteArrayList<>();
    try (StaffNames names = names()) {
      names.resolve(BANK, unknown, resolved::add);
      names.resolve(BANK, failing, resolved::add);
      await().atMost(Duration.ofSeconds(5)).until(() -> names.unresolved() == 2);
      assertThat(resolved).isEmpty();
    }
  }
}
