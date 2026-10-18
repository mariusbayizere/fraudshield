package io.github.mariusbayizere.fraudshield.ingest.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyRepository;
import io.github.mariusbayizere.fraudshield.auth.persistence.ApiKeyJpaRepository;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyStoreUnavailableException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ingestion port over M7's authenticator translates "store unavailable" into its own type and
 * passes every other error through (api-key-cache-contract.md §3, P28).
 */
class StaffIdentityWiringTest {

  private static final String KEY = "fsk_test_aaaaaaaaaaaa_" + "k".repeat(43);

  private static <T> T unused(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException(method.getName());
            }));
  }

  private static ApiKeyAuthenticator portFailingWith(String state) {
    DataSource failing =
        new AbstractDataSource() {
          @Override
          public java.sql.Connection getConnection() throws SQLException {
            throw new SQLException("key store error", state);
          }

          @Override
          public java.sql.Connection getConnection(String username, String password)
              throws SQLException {
            return getConnection();
          }
        };
    JdbcTemplate jdbc = new JdbcTemplate(failing);
    Clock clock = Clock.systemUTC();
    byte[] pepper = new byte[32];
    new SecureRandom().nextBytes(pepper);
    return new StaffIdentityWiring()
        .ingestApiKeys(
            new io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator(
                new ApiKeyRepository(
                    jdbc, unused(ApiKeyJpaRepository.class), unused(EntityManager.class), clock),
                new TenantTransactions(new TransactionTemplate(), jdbc, 1),
                new SafeRedis(null),
                Map.of(1, pepper),
                "test",
                Duration.ofSeconds(2),
                clock,
                System::nanoTime,
                Runnable::run));
  }

  @Test
  void unavailableKeyStoreBecomesThePortsOwnSignal() {
    assertThatThrownBy(() -> portFailingWith("08001").authenticate(KEY))
        .isInstanceOf(ApiKeyStoreUnavailableException.class);
  }

  @Test
  void otherErrorsPassThroughUnchanged() {
    assertThatThrownBy(() -> portFailingWith("42601").authenticate(KEY))
        .isInstanceOf(CannotGetJdbcConnectionException.class);
  }
}
