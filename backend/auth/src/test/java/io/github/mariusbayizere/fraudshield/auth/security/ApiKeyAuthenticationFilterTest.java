package io.github.mariusbayizere.fraudshield.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyRepository;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.persistence.ApiKeyJpaRepository;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.InMemoryRateLimiter;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a client is told when its key cannot be checked (docs/architecture/api-key-cache-contract.md
 * §3; threat model R-13).
 */
class ApiKeyAuthenticationFilterTest {

  private static <T> T unused(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException(method.getName());
            }));
  }

  private static final String FAILURES = "api-key-failures:ip:127.0.0.1";
  private static final Duration WINDOW = Duration.ofMinutes(15);

  private final Clock clock = Clock.systemUTC();
  private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
  private final AtomicInteger connections = new AtomicInteger();

  /** A key store whose every connection attempt fails with the given SQLSTATE. */
  private ApiKeyAuthenticator keysFailingWith(String state) {
    DataSource failing =
        new AbstractDataSource() {
          @Override
          public java.sql.Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            throw new SQLException("key store error", state);
          }

          @Override
          public java.sql.Connection getConnection(String username, String password)
              throws SQLException {
            return getConnection();
          }
        };
    JdbcTemplate jdbc = new JdbcTemplate(failing);
    return new ApiKeyAuthenticator(
        new ApiKeyRepository(
            jdbc, unused(ApiKeyJpaRepository.class), unused(EntityManager.class), clock),
        new TenantTransactions(new TransactionTemplate(), jdbc, 1),
        new SafeRedis(null),
        Map.of(1, Crypto.randomBytes(32)),
        "test",
        Duration.ofSeconds(2),
        clock,
        System::nanoTime,
        Runnable::run);
  }

  private MockHttpServletResponse send(ApiKeyAuthenticator keys, boolean withBearer)
      throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/transactions/ingest");
    request.addHeader(ApiKeyAuthenticationFilter.HEADER, "fsk_test_aaaaaaaaaaaa_" + "k".repeat(43));
    if (withBearer) {
      request.addHeader("Authorization", "Bearer x");
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean passedOn = new AtomicBoolean();
    new ApiKeyAuthenticationFilter(keys, limiter)
        .doFilter(request, response, (req, res) -> passedOn.set(true));
    assertThat(passedOn).isFalse();
    return response;
  }

  private boolean nothingCounted() {
    return limiter.check(FAILURES, 1, WINDOW).allowed();
  }

  @Test
  void keyStoreThatCannotAnswerIsServiceUnavailableNotUnauthorized() throws Exception {
    // PostgreSQL is down: every connection attempt fails, as in a paused container (P21).
    MockHttpServletResponse response = send(keysFailingWith("08001"), false);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString()).contains("service-unavailable");
    assertThat(nothingCounted()).as("an outage is not the client's failed key").isTrue();
  }

  @Test
  void keyStoreBugIsAnInternalErrorNotAnOutage() throws Exception {
    // Bad SQL is a defect: neither 503 (an outage) nor 401 (the client's key), not counted (P22).
    MockHttpServletResponse response = send(keysFailingWith("42601"), false);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString()).contains("urn:fraudshield:problem:internal");
    assertThat(response.getHeader("Retry-After")).isNull();
    assertThat(nothingCounted()).isTrue();
  }

  @Test
  void addressOverTheFailureLimitIsRefusedBeforeAnyRead() throws Exception {
    // R0(a), P23: the key store is never touched once the address is over its limit.
    for (int n = 0; n < 100; n++) {
      assertThat(limiter.attempt(FAILURES, 100, WINDOW).allowed()).isTrue();
    }
    MockHttpServletResponse response = send(keysFailingWith("08001"), false);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isNotNull();
    assertThat(connections).as("no read").hasValue(0);
  }

  @Test
  void keyAndBearerTogetherAreUnauthorisedAndNotCounted() throws Exception {
    // P29.
    MockHttpServletResponse response = send(keysFailingWith("08001"), true);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(connections).hasValue(0);
    assertThat(nothingCounted()).isTrue();
  }
}
