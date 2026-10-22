package io.github.mariusbayizere.fraudshield.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The hooks ADR 0115 point 3 adds to the session cache, without Redis or a database. */
class SessionStateCacheHooksTest {

  private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final SafeRedis redis = mock(SafeRedis.class);
  private final TenantTransactions tenants = mock(TenantTransactions.class);
  private final StaffAccountRepository accounts = mock(StaffAccountRepository.class);
  private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
  private final StaffClaims claims =
      new StaffClaims(
          UUID.randomUUID(),
          UUID.randomUUID(),
          StaffRole.ANALYST,
          "Hook",
          "hook@bank.rw",
          3,
          UUID.randomUUID());

  private SessionStateCache cache() {
    when(redis.get(any())).thenReturn(Optional.empty());
    return new SessionStateCache(
        redis,
        tenants,
        accounts,
        tokens,
        Duration.ofSeconds(2),
        Duration.ofSeconds(1),
        System::nanoTime,
        CLOCK);
  }

  @SuppressWarnings("unchecked")
  private void databaseAnswers(long version, boolean active) {
    when(tenants.inTenant(any(), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
    when(accounts.tokenVersion(claims.userId())).thenReturn(Optional.of(version));
    when(tokens.familyActive(claims.sessionId())).thenReturn(active);
  }

  @Test
  @Tag("FR-04-02")
  void refusedGateNeverReachesTheDatabase() {
    SessionStateCache cache = cache();
    assertThat(cache.isCurrent(claims, () -> false)).isEqualTo(SessionStateCache.Currency.UNKNOWN);
    verifyNoInteractions(tenants, accounts, tokens);
  }

  @Test
  @Tag("FR-04-02")
  void anOpenGateLoadsAndAnswers() {
    SessionStateCache cache = cache();
    databaseAnswers(3, true);
    assertThat(cache.isCurrent(claims, () -> true)).isEqualTo(SessionStateCache.Currency.CURRENT);
    databaseAnswers(4, true);
    SessionStateCache fresh = cache();
    assertThat(fresh.isCurrent(claims, () -> true))
        .isEqualTo(SessionStateCache.Currency.NOT_CURRENT);
  }

  @Test
  @Tag("FR-04-02")
  void redisAnsweredCheckNeverConsultsTheGate() {
    SessionStateCache cache = cache();
    long deadline = NOW.toEpochMilli() + 2_000;
    when(redis.get("fs:auth:tv:" + claims.userId())).thenReturn(Optional.of("3|" + deadline));
    when(redis.get("fs:auth:sid:" + claims.sessionId())).thenReturn(Optional.of("1|" + deadline));
    AtomicInteger consulted = new AtomicInteger();
    assertThat(
            cache.isCurrent(
                claims,
                () -> {
                  consulted.incrementAndGet();
                  return false;
                }))
        .isEqualTo(SessionStateCache.Currency.CURRENT);
    assertThat(consulted).hasValue(0);
    verifyNoInteractions(tenants);
  }

  @Test
  @Tag("FR-04-02")
  void listenersRunAfterTheLocalEntryIsRemoved() {
    SessionStateCache cache = cache();
    databaseAnswers(3, true);
    assertThat(cache.isCurrent(claims)).as("cached locally now").isTrue();
    databaseAnswers(4, true);
    List<Boolean> seenByListener = new ArrayList<>();
    cache.addChangeListener(
        new SessionStateCache.ChangeListener() {
          @Override
          public void versionChanged(UUID userId) {
            // The re-check a listener triggers must not be answered by the stale local entry.
            seenByListener.add(cache.isCurrent(claims));
          }

          @Override
          public void sessionEnded(UUID sessionId) {
            seenByListener.add(cache.isCurrent(claims));
          }
        });
    cache.onMessage("tv:" + claims.userId());
    assertThat(seenByListener).containsExactly(false);
  }
}
