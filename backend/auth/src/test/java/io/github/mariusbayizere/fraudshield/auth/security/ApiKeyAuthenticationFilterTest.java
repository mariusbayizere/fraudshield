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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

/** What a client is told when its key cannot be checked at all (threat model R-13). */
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

  @Test
  void keyStoreThatCannotAnswerIsServiceUnavailableNotUnauthorized() throws Exception {
    // PostgreSQL is down: every connection attempt fails, as in a paused container.
    DataSource down =
        new AbstractDataSource() {
          @Override
          public java.sql.Connection getConnection() throws SQLException {
            throw new SQLException("connection refused", "08001");
          }

          @Override
          public java.sql.Connection getConnection(String username, String password)
              throws SQLException {
            return getConnection();
          }
        };
    JdbcTemplate jdbc = new JdbcTemplate(down);
    Clock clock = Clock.systemUTC();
    ApiKeyAuthenticator keys =
        new ApiKeyAuthenticator(
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
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/transactions/ingest");
    request.addHeader(ApiKeyAuthenticationFilter.HEADER, "fsk_test_aaaaaaaaaaaa_" + "k".repeat(43));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean passedOn = new AtomicBoolean();

    new ApiKeyAuthenticationFilter(keys, new InMemoryRateLimiter(clock))
        .doFilter(request, response, (req, res) -> passedOn.set(true));

    assertThat(passedOn).isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString()).contains("service-unavailable");
  }
}
