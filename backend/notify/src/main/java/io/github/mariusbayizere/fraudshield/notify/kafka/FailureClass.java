package io.github.mariusbayizere.fraudshield.notify.kafka;

import io.github.mariusbayizere.fraudshield.notify.vault.KmsUnavailableException;
import io.github.mariusbayizere.fraudshield.notify.vault.VaultException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import org.apache.kafka.common.errors.RecordTooLargeException;

/**
 * The class of a failed attempt (docs/architecture/decision-fact-ordering.md, 12.2). Every failure
 * is in exactly one class, and the class decides what the consumer does with the record.
 */
enum FailureClass {
  /** A shared dependency is unavailable: retried without bound, warned and counted. */
  OUTAGE,
  /** The record cannot be read: dead-lettered at once. */
  MALFORMED,
  /** A data or integrity error, or a permanent vault failure: dead-lettered at once. */
  PERMANENT,
  /** Anything else: retried up to the bound of 12.3, then dead-lettered. */
  MAY_BE_OWN;

  /** SQLSTATEs that are outages outside the whole classes listed in {@link #sql}. */
  private static final Set<String> OUTAGE_STATES =
      Set.of(
          "3D000", "25006", "40001", "40P01", "53100", "53200", "53300", "57P01", "57P02", "57P03");

  /**
   * Classifies a failure of the handler (12.2, rule 2): the cause chain is walked from the
   * outermost exception inward; an exception that carries its own class decides before its causes;
   * otherwise the first exception that matches a row decides; with no match the class is {@link
   * #MAY_BE_OWN}.
   *
   * @param failure what the handler threw
   * @return its class
   */
  static FailureClass of(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      FailureClass own = own(t);
      if (own != null) {
        return own;
      }
    }
    for (Throwable t = failure; t != null; t = t.getCause()) {
      FailureClass matched = matched(t);
      if (matched != null) {
        return matched;
      }
    }
    return MAY_BE_OWN;
  }

  /**
   * Classifies a failed dead-letter send (12.2, rule 1): every failure is an outage except a record
   * the broker refuses as too large.
   *
   * @param failure what the send threw, possibly wrapped
   * @return {@link #MAY_BE_OWN} for a record that is too large, {@link #OUTAGE} otherwise
   */
  static FailureClass ofDeadLetterSend(Throwable failure) {
    return tooLarge(failure) ? MAY_BE_OWN : OUTAGE;
  }

  /**
   * Whether a failed send was refused because the record is too large.
   *
   * @param failure what the send threw, possibly wrapped
   * @return true for {@link RecordTooLargeException} anywhere in the chain
   */
  static boolean tooLarge(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof RecordTooLargeException) {
        return true;
      }
    }
    return false;
  }

  private static FailureClass own(Throwable t) {
    if (t instanceof MalformedPayloadException || t instanceof MalformedHeaderException) {
      return MALFORMED;
    }
    if (t instanceof KmsUnavailableException) {
      return OUTAGE;
    }
    if (t instanceof VaultException vault && vault.permanent()) {
      return PERMANENT;
    }
    return null;
  }

  private static FailureClass matched(Throwable t) {
    if (t instanceof SQLTransientConnectionException || t instanceof SQLRecoverableException) {
      return OUTAGE;
    }
    if (t instanceof SQLException sql) {
      return sql(sql.getSQLState());
    }
    return null;
  }

  /**
   * The class of a SQLSTATE; null when it decides nothing (a null state), so the walk goes on.
   *
   * @param state the SQLSTATE, possibly null
   * @return its class, or null
   */
  static FailureClass sql(String state) {
    if (state == null || state.length() != 5) {
      return null;
    }
    if (state.startsWith("08")) {
      return "08P01".equals(state) ? MAY_BE_OWN : OUTAGE;
    }
    if (state.startsWith("28") || OUTAGE_STATES.contains(state)) {
      return OUTAGE;
    }
    if (state.startsWith("22") || state.startsWith("23")) {
      return PERMANENT;
    }
    return MAY_BE_OWN;
  }
}
