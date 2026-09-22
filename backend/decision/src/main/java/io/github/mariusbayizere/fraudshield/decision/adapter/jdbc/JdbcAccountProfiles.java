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
   * @param frozen whether the latest freeze event is FROZEN (V5 {@code account_freeze_events})
   */
  public record Profile(Instant firstSeenAt, Instant openedAt, boolean frozen) {}

  private static final String SELECT_ACCOUNT_PROFILES =
      """
      SELECT p.first_seen_at, p.opened_at, coalesce((SELECT e.event = 'FROZEN' FROM
      account_freeze_events e WHERE e.account_token = p.account_token ORDER BY e.occurred_at DESC
      LIMIT 1), false) FROM account_profiles p WHERE p.account_token = ?
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
                          opened == null ? null : opened.toInstant(),
                          row.getBoolean(3)));
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
