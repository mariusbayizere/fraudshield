package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.notify.vault.KmsUnavailableException;
import io.github.mariusbayizere.fraudshield.notify.vault.VaultException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * I24: every failure of an attempt is in exactly one class of docs/architecture/
 * decision-fact-ordering.md, 12.2, the cause chain walked, and the class decides the action.
 */
@Tag("NFR-REL-01")
class FailureClassTest {

  @ParameterizedTest
  @CsvSource({
    // F1: shared outages.
    "08006, OUTAGE",
    "08001, OUTAGE",
    "08003, OUTAGE",
    "28000, OUTAGE",
    "28P01, OUTAGE",
    "3D000, OUTAGE",
    "25006, OUTAGE",
    "40001, OUTAGE",
    "40P01, OUTAGE",
    "53100, OUTAGE",
    "53200, OUTAGE",
    "53300, OUTAGE",
    "57P01, OUTAGE",
    "57P02, OUTAGE",
    "57P03, OUTAGE",
    // F3: data and integrity only.
    "22001, PERMANENT",
    "22P02, PERMANENT",
    "23505, PERMANENT",
    "23503, PERMANENT",
    "23514, PERMANENT",
    // F4: everything else, the default.
    "08P01, MAY_BE_OWN",
    "42P01, MAY_BE_OWN",
    "42703, MAY_BE_OWN",
    "42501, MAY_BE_OWN",
    "53400, MAY_BE_OWN",
    "57014, MAY_BE_OWN",
    "58030, MAY_BE_OWN",
    "58P01, MAY_BE_OWN",
    "XX000, MAY_BE_OWN",
    "XX001, MAY_BE_OWN",
    "0A000, MAY_BE_OWN"
  })
  void sqlStatesFallInTheirClass(String state, FailureClass expected) {
    assertThat(FailureClass.of(new SQLException("test", state))).isEqualTo(expected);
  }

  @Test
  void nullSqlStatesAreTheRecordsOwnUnlessTheTypeSaysOutage() {
    assertThat(FailureClass.of(new SQLException("no state"))).isEqualTo(FailureClass.MAY_BE_OWN);
    // A connection-pool timeout carries no SQLSTATE: its type decides.
    assertThat(FailureClass.of(new SQLTransientConnectionException("pool timed out")))
        .isEqualTo(FailureClass.OUTAGE);
    assertThat(FailureClass.of(new SQLRecoverableException("connection lost")))
        .isEqualTo(FailureClass.OUTAGE);
  }

  @Test
  void theCauseChainIsWalked() {
    // The vault database down: a non-permanent VaultException wrapping an 08 SQLException.
    assertThat(
            FailureClass.of(
                new VaultException("vault unavailable", new SQLException("down", "08006"))))
        .isEqualTo(FailureClass.OUTAGE);
    // A key this instance does not hold yet: not permanent, not caused by an outage.
    assertThat(FailureClass.of(new VaultException("key not held yet")))
        .isEqualTo(FailureClass.MAY_BE_OWN);
    // A unique violation reached through a wrapper.
    assertThat(
            FailureClass.of(
                new IllegalStateException("could not record", new SQLException("dup", "23505"))))
        .isEqualTo(FailureClass.PERMANENT);
  }

  @Test
  void anExceptionThatCarriesItsOwnClassDecidesBeforeItsCauses() {
    // A permanent VaultException decides as permanent even over an outage underneath.
    assertThat(
            FailureClass.of(
                VaultException.permanent("does not verify", new SQLException("down", "08006"))))
        .isEqualTo(FailureClass.PERMANENT);
    assertThat(FailureClass.of(new KmsUnavailableException("throttled", null)))
        .isEqualTo(FailureClass.OUTAGE);
    assertThat(
            FailureClass.of(
                new RuntimeException(new MalformedPayloadException("unreadable (test)", null))))
        .isEqualTo(FailureClass.MALFORMED);
    assertThat(FailureClass.of(new MalformedHeaderException("S3m (test)")))
        .isEqualTo(FailureClass.MALFORMED);
  }

  @Test
  void anyOtherRuntimeExceptionIsTheRecordsOwn() {
    assertThat(FailureClass.of(new NullPointerException("parameters")))
        .isEqualTo(FailureClass.MAY_BE_OWN);
    assertThat(FailureClass.of(new IllegalStateException("adapter")))
        .isEqualTo(FailureClass.MAY_BE_OWN);
  }

  @Test
  void deadLetterSendFailuresAreOutagesExceptRecordsThatAreTooLarge() {
    assertThat(FailureClass.ofDeadLetterSend(new TimeoutException("send timed out")))
        .isEqualTo(FailureClass.OUTAGE);
    assertThat(FailureClass.ofDeadLetterSend(new java.util.concurrent.TimeoutException("future")))
        .isEqualTo(FailureClass.OUTAGE);
    assertThat(FailureClass.ofDeadLetterSend(new TopicAuthorizationException("acl")))
        .isEqualTo(FailureClass.OUTAGE);
    assertThat(FailureClass.ofDeadLetterSend(new InvalidTopicException("dlq")))
        .isEqualTo(FailureClass.OUTAGE);
    assertThat(
            FailureClass.ofDeadLetterSend(
                new ExecutionException(new RecordTooLargeException("too large"))))
        .isEqualTo(FailureClass.MAY_BE_OWN);
  }
}
