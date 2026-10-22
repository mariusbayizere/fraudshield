package io.github.mariusbayizere.fraudshield.notify.verification;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerRequest;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerTransactions;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
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
 *
 * <p>The answer is a request to the transaction's owner, made in the same transaction as the
 * response (decision-owner contract §6.5): the response and its outcome commit together, and an
 * {@code unblock_events} row exists only for an applied "yes". If the database is unavailable,
 * nothing is stored and the token is not spent. A "yes" waits while a human reviews the payment,
 * and is refused after a human declined it.
 */
public final class VerificationService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(VerificationService.class);

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
   * The result of an answer.
   *
   * @param unusable why the token could not be used, when it could not
   * @param pending kept for the page's contract; always false since the answer and its outcome
   *     commit together
   * @param deferred true when a human is reviewing the payment, so the answer waits for that
   *     decision and {@link UnblockReconciler} submits it afterwards (§6.5)
   * @param stillBlocked true when a human declined the payment, so a "yes" cannot lift it (§5)
   */
  public record Answered(
      Optional<Unusable> unusable, boolean pending, boolean deferred, boolean stillBlocked) {

    /** An accepted answer whose outcome is applied. */
    static final Answered APPLIED = new Answered(Optional.empty(), false, false, false);

    /** An accepted answer waiting for a human's decision. */
    static final Answered DEFERRED = new Answered(Optional.empty(), false, true, false);

    /** An accepted "yes" that a human's decline overrules. */
    static final Answered STILL_BLOCKED = new Answered(Optional.empty(), false, false, true);

    /**
     * A token that cannot be used.
     *
     * @param why the reason
     * @return the result
     */
    static Answered refused(Unusable why) {
      return new Answered(Optional.of(why), false, false, false);
    }
  }

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
  private final OwnerTransactions transactions;
  private final DecisionOwner owner;
  private final RequestRecorder recorder;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param dataSource connections as {@code fs_app}
   * @param transactions the owner's runner
   * @param owner the transaction's owner
   * @param recorder records an outcome after the commit
   * @param clock clock
   */
  public VerificationService(
      DataSource dataSource,
      OwnerTransactions transactions,
      DecisionOwner owner,
      RequestRecorder recorder,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
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
      // Issued from the SMS consumer: its statements are bounded below the socket timeout (12.3).
      try (PreparedStatement timeout =
          c.prepareStatement("SELECT set_config('statement_timeout', '5s', true)")) {
        timeout.execute();
      }
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
   * Records the customer's answer, and its owner request, in one transaction.
   *
   * @param token token from the link
   * @param wasMe true for "Yes, this was me"
   * @return whether the answer was accepted, and what it did
   * @throws SQLException when the database is unavailable; nothing is stored then
   */
  public Answered answer(String token, boolean wasMe) throws SQLException {
    Optional<Found> found = find(token);
    if (found.isEmpty()) {
      return Answered.refused(Unusable.UNKNOWN);
    }
    Found f = found.get();
    Optional<Unusable> unusable = unusable(f);
    if (unusable.isPresent()) {
      return Answered.refused(unusable.get());
    }
    Instant now = clock.instant();
    DecisionOwner.Result result;
    try {
      result =
          transactions.inTenant(
              f.institution(),
              c -> {
                try (PreparedStatement s = c.prepareStatement(RESPOND)) {
                  s.setObject(1, f.verification());
                  s.setObject(2, f.institution());
                  s.setString(3, wasMe ? "WAS_ME" : "NOT_ME");
                  s.setObject(4, utc(now));
                  if (s.executeUpdate() == 0) {
                    return null;
                  }
                }
                return owner.submit(
                    c,
                    f.institution(),
                    f.transaction(),
                    new OwnerRequest.Customer(f.verification(), wasMe));
              });
    } catch (SQLException e) {
      if ("P0001".equals(e.getSQLState()) || "23514".equals(e.getSQLState())) {
        return Answered.refused(Unusable.EXPIRED);
      }
      throw e;
    }
    if (result == null) {
      return Answered.refused(Unusable.USED);
    }
    recorder.record(f.institution(), result);
    return switch (result.outcome()) {
      case DEFERRED -> {
        LOG.info(
            "the customer's answer for transaction {} waits for a human's decision",
            f.transaction());
        yield Answered.DEFERRED;
      }
      case REFUSED -> Answered.STILL_BLOCKED;
      default -> Answered.APPLIED;
    };
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
