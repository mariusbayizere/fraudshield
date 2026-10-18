package io.github.mariusbayizere.fraudshield.auth.apikey;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Decides whether an error from the key store means the database is unavailable
 * (docs/architecture/api-key-cache-contract.md R8), from the whole cause chain, including causes
 * wrapped in Spring's {@code DataAccessException}. Anything else is a bug or a data problem and is
 * answered 500, never 503.
 */
public final class KeyStoreUnavailability {

  private static final Set<String> STATES = Set.of("57P01", "57P02", "57P03", "57P05", "57014");

  private KeyStoreUnavailability() {}

  /**
   * Whether an error means the database is unavailable.
   *
   * @param error the error raised by a read
   * @return true for a connection failure, timeout, shutdown, recovery or exhausted resources
   */
  public static boolean isUnavailable(Throwable error) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
      // Hikari wraps every failure to create a connection in SQLTransientConnectionException,
      // a bad password or missing database included: deliberately counted (R8).
      if (cause instanceof SQLTransientConnectionException
          || cause instanceof SQLRecoverableException
          || cause instanceof SQLTimeoutException
          || cause instanceof SocketTimeoutException
          || cause instanceof ConnectException) {
        return true;
      }
      if (cause instanceof SQLException sql && unavailableState(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static boolean unavailableState(String state) {
    return state != null
        && (state.startsWith("08")
            || state.startsWith("53")
            || state.startsWith("58")
            || STATES.contains(state));
  }
}
