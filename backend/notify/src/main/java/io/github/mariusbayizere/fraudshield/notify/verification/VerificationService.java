package io.github.mariusbayizere.fraudshield.notify.verification;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionTransitionService;
import io.github.mariusbayizere.fraudshield.notify.sms.LocalTimes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Customer verification of an auto-block (FR-03-04, FR-03-05, E.7, D-25).
 *
 * <p>A link is issued only when self-service is allowed, once per block, valid for exactly 10
 * minutes. The page never reveals why a token is unusable beyond "expired or already used". "Yes,
 * this was me" lifts the block (a new decision state, delivered by webhook) and records a
 * LEGITIMATE label for retraining; "No" records a FRAUD label and keeps the block. The database
 * enforces single use (one response per verification) and expiry (V5 triggers).
 */
public final class VerificationService {

  /** Link lifetime (FR-03-04: expires exactly at 10 minutes). */
  public static final Duration LIFETIME = Duration.ofMinutes(10);

  private static final String ISSUE =
      """
      INSERT INTO customer_verifications (institution_id, auto_block_event_id,
      verification_token_hash, verification_channel, created_at, expires_at)
      VALUES (?, ?, ?, 'SMS_LINK', ?, ?) ON CONFLICT (auto_block_event_id) DO NOTHING
      """;
  private static final String FIND =
      "SELECT verification_id, institution_id, expires_at FROM verification_find_by_token(?)";
  private static final String DETAILS =
      """
      SELECT b.id, b.transaction_id, t.transaction_timestamp, t.amount, t.currency,
      t.account_token, t.longitude,
      EXISTS (SELECT 1 FROM customer_verification_responses r WHERE r.verification_id = v.id)
      FROM customer_verifications v JOIN auto_block_events b ON b.id = v.auto_block_event_id
      JOIN v_transactions t ON t.transaction_id = b.transaction_id WHERE v.id = ?
      """;
  private static final String RESPOND =
      """
      INSERT INTO customer_verification_responses (verification_id, institution_id, answer,
      self_service_allowed, responded_at) VALUES (?, ?, ?, true, ?) ON CONFLICT DO NOTHING
      """;
  private static final String UNBLOCK =
      """
      INSERT INTO unblock_events (institution_id, auto_block_event_id, cause, verification_id,
      unblocked_at) VALUES (?, ?, 'CUSTOMER_VERIFICATION', ?, ?) ON CONFLICT DO NOTHING
      """;

  /** Why a token cannot be used; shown to the customer only as "expired or already used". */
  public enum Unusable {
    /** No such token. */
    UNKNOWN,
    /** Older than 10 minutes. */
    EXPIRED,
    /** Already answered. */
    USED
  }

  /**
   * What the page shows.
   *
   * @param maskedAccount masked account reference
   * @param amount amount and currency
   * @param localTime local time with zone
   */
  public record View(String maskedAccount, Money amount, String localTime) {}

  /**
   * A token's state: a view, or why it cannot be used.
   *
   * @param view the view when usable
   * @param unusable the reason otherwise
   */
  public record Lookup(Optional<View> view, Optional<Unusable> unusable) {}

  private record Found(
      UUID verification,
      UUID institution,
      UUID block,
      UUID transaction,
      Instant transactionAt,
      View view,
      boolean used,
      Instant expiresAt) {}

  private final DataSource dataSource;
  private final DecisionTransitionService transitions;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param dataSource connections as {@code fs_app}
   * @param transitions decision transitions
   * @param clock clock
   */
  public VerificationService(
      DataSource dataSource, DecisionTransitionService transitions, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Issues the single link for a block.
   *
   * @param institutionId institution
   * @param autoBlockEventId the block
   * @return the token to put in the link, or empty if a link was already issued for this block
   * @throws SQLException when the database is unavailable
   */
  public Optional<String> issue(UUID institutionId, UUID autoBlockEventId) throws SQLException {
    VerificationTokens.Minted minted = VerificationTokens.mint();
    Instant now = clock.instant();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      tenant(c, institutionId);
      try (PreparedStatement s = c.prepareStatement(ISSUE)) {
        s.setObject(1, institutionId);
        s.setObject(2, autoBlockEventId);
        s.setBytes(3, minted.hash());
        s.setObject(4, utc(now));
        s.setObject(5, utc(now.plus(LIFETIME)));
        boolean issued = s.executeUpdate() == 1;
        c.commit();
        return issued ? Optional.of(minted.token()) : Optional.empty();
      }
    }
  }

  /**
   * Looks a token up for the page.
   *
   * @param token token from the link
   * @return the view, or why it cannot be used
   * @throws SQLException when the database is unavailable
   */
  public Lookup open(String token) throws SQLException {
    Optional<Found> found = find(token);
    if (found.isEmpty()) {
      return new Lookup(Optional.empty(), Optional.of(Unusable.UNKNOWN));
    }
    Optional<Unusable> unusable = unusable(found.get());
    return unusable.isPresent()
        ? new Lookup(Optional.empty(), unusable)
        : new Lookup(Optional.of(found.get().view()), Optional.empty());
  }

  /**
   * Records the customer's answer.
   *
   * @param token token from the link
   * @param wasMe true for "Yes, this was me"
   * @return empty when accepted, otherwise why the token cannot be used
   * @throws SQLException when the database is unavailable
   */
  public Optional<Unusable> answer(String token, boolean wasMe) throws SQLException {
    Optional<Found> found = find(token);
    if (found.isEmpty()) {
      return Optional.of(Unusable.UNKNOWN);
    }
    Found f = found.get();
    Optional<Unusable> unusable = unusable(f);
    if (unusable.isPresent()) {
      return unusable;
    }
    Instant now = clock.instant();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        tenant(c, f.institution());
        try (PreparedStatement s = c.prepareStatement(RESPOND)) {
          s.setObject(1, f.verification());
          s.setObject(2, f.institution());
          s.setString(3, wasMe ? "WAS_ME" : "NOT_ME");
          s.setObject(4, utc(now));
          if (s.executeUpdate() == 0) {
            c.rollback();
            return Optional.of(Unusable.USED);
          }
        }
        if (wasMe) {
          try (PreparedStatement s = c.prepareStatement(UNBLOCK)) {
            s.setObject(1, f.institution());
            s.setObject(2, f.block());
            s.setObject(3, f.verification());
            s.setObject(4, utc(now));
            s.executeUpdate();
          }
        }
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        if ("P0001".equals(e.getSQLState()) || "23514".equals(e.getSQLState())) {
          return Optional.of(Unusable.EXPIRED);
        }
        throw e;
      }
    }
    try {
      transitions.customerAnswered(f.institution(), f.transaction(), wasMe, f.transactionAt());
    } catch (DecisionTransitionService.TransitionRefusedException alreadyResolved) {
      // The answer is recorded; the decision had already moved on (for example a senior
      // override), so there is nothing to lift.
    }
    return Optional.empty();
  }

  private Optional<Unusable> unusable(Found f) {
    if (!clock.instant().isBefore(f.expiresAt())) {
      return Optional.of(Unusable.EXPIRED);
    }
    return f.used() ? Optional.of(Unusable.USED) : Optional.empty();
  }

  private Optional<Found> find(String token) throws SQLException {
    if (token == null || !token.matches("^[A-Za-z0-9_-]{22}$")) {
      return Optional.empty();
    }
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      UUID verification;
      UUID institution;
      Instant expires;
      try (PreparedStatement s = c.prepareStatement(FIND)) {
        s.setBytes(1, VerificationTokens.hash(token));
        try (ResultSet row = s.executeQuery()) {
          if (!row.next()) {
            c.rollback();
            return Optional.empty();
          }
          verification = row.getObject(1, UUID.class);
          institution = row.getObject(2, UUID.class);
          expires = row.getObject(3, OffsetDateTime.class).toInstant();
        }
      }
      tenant(c, institution);
      try (PreparedStatement s = c.prepareStatement(DETAILS)) {
        s.setObject(1, verification);
        try (ResultSet row = s.executeQuery()) {
          if (!row.next()) {
            c.rollback();
            return Optional.empty();
          }
          Instant at = row.getObject(3, OffsetDateTime.class).toInstant();
          CurrencyCode currency = CurrencyCode.valueOf(row.getString(5).strip());
          String account = row.getString(6);
          View view =
              new View(
                  "***" + account.substring(account.length() - 4),
                  new Money(row.getBigDecimal(4), currency),
                  LocalTimes.format(at, LocalTimes.zone(currency, row.getDouble(7))));
          Found found =
              new Found(
                  verification,
                  institution,
                  row.getObject(1, UUID.class),
                  row.getObject(2, UUID.class),
                  at,
                  view,
                  row.getBoolean(8),
                  expires);
          c.commit();
          return Optional.of(found);
        }
      }
    }
  }

  private static void tenant(Connection c, UUID institutionId) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
      s.setString(1, institutionId.toString());
      s.execute();
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
