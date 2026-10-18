package io.github.mariusbayizere.fraudshield.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Which key-store errors are an outage (503, stale use) and which are bugs (500): rule R8 of
 * docs/architecture/api-key-cache-contract.md, properties P19 and P20.
 */
class KeyStoreUnavailabilityTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "08001", "08003", "08004", "08006", "08P01", "53000", "53100", "53200", "53300", "53400",
        "58000", "58030", "57P01", "57P02", "57P03", "57P05", "57014"
      })
  void unavailableStatesAreFailuresBareAndWrapped(String state) {
    SQLException sql = new SQLException("down", state);
    assertThat(KeyStoreUnavailability.isUnavailable(sql)).as("bare %s", state).isTrue();
    assertThat(KeyStoreUnavailability.isUnavailable(new UncategorizedSQLException("q", "s", sql)))
        .as("wrapped %s", state)
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"42601", "42P01", "22001", "23505", "25006", "40001", "57P04", "XX001"})
  void otherStatesAreNotFailures(String state) {
    SQLException sql = new SQLException("bug", state);
    assertThat(KeyStoreUnavailability.isUnavailable(sql)).as("bare %s", state).isFalse();
    assertThat(KeyStoreUnavailability.isUnavailable(new UncategorizedSQLException("q", "s", sql)))
        .as("wrapped %s", state)
        .isFalse();
  }

  @Test
  void corruptedIndexAndBadSqlAreNotFailures() {
    assertThat(KeyStoreUnavailability.isUnavailable(new SQLException("index", "XX002"))).isFalse();
    assertThat(
            KeyStoreUnavailability.isUnavailable(
                new BadSqlGrammarException("q", "select", new SQLException("syntax", "42601"))))
        .isFalse();
  }

  @Test
  void connectionAndTimeoutTypesAreFailuresWhereverTheyAreInTheChain() {
    assertThat(KeyStoreUnavailability.isUnavailable(new SQLTransientConnectionException("pool")))
        .isTrue();
    assertThat(KeyStoreUnavailability.isUnavailable(new SQLRecoverableException("gone"))).isTrue();
    assertThat(KeyStoreUnavailability.isUnavailable(new SQLTimeoutException("slow"))).isTrue();
    assertThat(
            KeyStoreUnavailability.isUnavailable(
                new SQLException("io", null, new SocketTimeoutException("read timed out"))))
        .isTrue();
    assertThat(
            KeyStoreUnavailability.isUnavailable(
                new DataAccessResourceFailureException("x", new ConnectException("refused"))))
        .isTrue();
  }

  @Test
  void hikariWrappingBadPasswordIsFailureByDesign() {
    // Hikari reports every failure to create a connection this way, misconfiguration included.
    SQLTransientConnectionException pool =
        new SQLTransientConnectionException(
            "Connection is not available", null, new SQLException("auth", "28P01"));
    assertThat(
            KeyStoreUnavailability.isUnavailable(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", pool)))
        .isTrue();
  }

  @Test
  void nonDatabaseErrorsAreNotFailures() {
    assertThat(KeyStoreUnavailability.isUnavailable(new IllegalStateException("bug"))).isFalse();
    assertThat(
            KeyStoreUnavailability.isUnavailable(
                new DataAccessResourceFailureException("no cause at all")))
        .as("a Spring resource failure with no unavailable cause")
        .isFalse();
  }
}
