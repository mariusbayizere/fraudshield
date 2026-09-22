package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatePort;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.domain.history.Arrival;
import io.github.mariusbayizere.fraudshield.decision.domain.history.HistoryCalculator;
import io.github.mariusbayizere.fraudshield.decision.domain.history.HistoryInputs;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The account-state fallback while Redis is down (C.4): the account's own arrivals from the
 * transactions hypertable, through the same {@link HistoryCalculator}, so degraded mode cannot
 * compute a feature differently. Counterparty, device and agent sets are not reconstructed here
 * (they would scan other accounts' rows on the hot path) and read as empty, which the degraded-mode
 * metric makes visible. Recording is a no-op: the PostgreSQL writer persists every transaction.
 */
public final class JdbcAccountHistory implements AccountStatePort {

  private static final String SELECT_ARRIVALS =
      """
      SELECT transaction_id, transaction_timestamp, amount_rwf, counterparty_token, latitude,
      longitude, counterparty_country, device_token FROM v_transactions
      WHERE account_token = ? AND transaction_timestamp > ? AND transaction_timestamp < ?
      ORDER BY transaction_timestamp
      """;

  private final DataSource dataSource;
  private final JdbcAccountProfiles profiles;

  /**
   * Creates the fallback.
   *
   * @param dataSource connections as {@code fs_app}
   * @param profiles durable profiles
   */
  public JdbcAccountHistory(DataSource dataSource, JdbcAccountProfiles profiles) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
  }

  @Override
  public Snapshot read(Transaction t) {
    try {
      Optional<JdbcAccountProfiles.Profile> profile =
          profiles.find(t.institutionId(), t.accountToken());
      List<Arrival> arrivals = arrivals(t);
      HistoryInputs inputs =
          new HistoryInputs(
              arrivals,
              null,
              profile.map(JdbcAccountProfiles.Profile::firstSeenAt).orElse(null),
              profile.map(JdbcAccountProfiles.Profile::openedAt).orElse(null),
              0,
              0,
              0,
              null,
              List.of());
      return new Snapshot(
          HistoryCalculator.compute(t, inputs),
          profile.map(JdbcAccountProfiles.Profile::frozen).orElse(false),
          profile.isEmpty());
    } catch (SQLException e) {
      throw new IllegalStateException(
          "neither Redis nor PostgreSQL could provide account state", e);
    }
  }

  @Override
  public void record(Transaction transaction) {
    // Persisted by the PostgreSQL spool consumer.
  }

  private List<Arrival> arrivals(Transaction t) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, t.institutionId());
        List<Arrival> arrivals = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(SELECT_ARRIVALS)) {
          s.setString(1, t.accountToken());
          s.setObject(2, Tenant.utc(t.transactionTimestamp().minus(HistoryCalculator.HORIZON)));
          s.setObject(3, Tenant.utc(t.transactionTimestamp()));
          try (ResultSet row = s.executeQuery()) {
            while (row.next()) {
              arrivals.add(
                  new Arrival(
                      row.getObject(1, UUID.class),
                      row.getObject(2, OffsetDateTime.class).toInstant(),
                      row.getBigDecimal(3),
                      row.getString(4),
                      row.getDouble(5),
                      row.getDouble(6),
                      row.getString(7),
                      row.getString(8)));
            }
          }
        }
        c.commit();
        return arrivals;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
