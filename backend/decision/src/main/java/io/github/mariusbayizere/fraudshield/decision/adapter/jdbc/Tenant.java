package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** JDBC helpers shared by the adapters: tenant scoping (ADR 0017) and UTC timestamps. */
final class Tenant {

  private Tenant() {}

  /**
   * Scopes the current transaction to an institution; row-level security and the hypertable guards
   * then admit only its rows. Transaction-local, so a pooled connection never carries it over.
   *
   * @param connection a connection inside a transaction
   * @param institutionId the institution
   * @throws SQLException on failure
   */
  static void use(Connection connection, UUID institutionId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
      statement.setString(1, institutionId.toString());
      statement.execute();
    }
  }

  static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /**
   * Whether an error is worth retrying as-is (connection, serialisation, resources, shutdown), as
   * opposed to a row the database will never accept.
   *
   * @param e the error
   * @return true for transient SQLSTATE classes 08, 40, 53, 57 and 58
   */
  static boolean transientError(SQLException e) {
    String state = e.getSQLState();
    return state == null
        || state.startsWith("08")
        || state.startsWith("40")
        || state.startsWith("53")
        || state.startsWith("57")
        || state.startsWith("58");
  }
}
