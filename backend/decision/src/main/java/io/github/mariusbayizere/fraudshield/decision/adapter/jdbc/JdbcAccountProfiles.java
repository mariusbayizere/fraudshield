package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Durable per-account state (V60 {@code account_profiles}, PB-37): the first-seen time that
 * survives a cache flush and the opening date the institution supplies.
 */
public final class JdbcAccountProfiles {

  /**
   * An account's durable profile.
   *
   * @param firstSeenAt first transaction in FraudShield
   * @param openedAt opening date, if supplied
   */
  public record Profile(Instant firstSeenAt, Instant openedAt) {}

  private static final String SELECT_ACCOUNT_PROFILES =
      """
      SELECT first_seen_at, opened_at FROM account_profiles WHERE account_token = ?
      """;

  private final DataSource dataSource;

  /**
   * Creates the reader.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JdbcAccountProfiles(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Reads a profile.
   *
   * @param institutionId institution
   * @param accountToken account
   * @return the profile, if the account has transacted before
   * @throws SQLException when the database is unavailable
   */
  public Optional<Profile> find(UUID institutionId, String accountToken) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, institutionId);
        try (PreparedStatement s = c.prepareStatement(SELECT_ACCOUNT_PROFILES)) {
          s.setString(1, accountToken);
          try (ResultSet row = s.executeQuery()) {
            Optional<Profile> profile = Optional.empty();
            if (row.next()) {
              OffsetDateTime opened = row.getObject(2, OffsetDateTime.class);
              profile =
                  Optional.of(
                      new Profile(
                          row.getObject(1, OffsetDateTime.class).toInstant(),
                          opened == null ? null : opened.toInstant()));
            }
            c.commit();
            return profile;
          }
        }
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
