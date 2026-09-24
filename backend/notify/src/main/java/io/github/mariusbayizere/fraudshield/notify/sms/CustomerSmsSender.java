package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.notify.kafka.NotTheKeptDecisionException;
import io.github.mariusbayizere.fraudshield.notify.kafka.NotYetRecordedException;
import io.github.mariusbayizere.fraudshield.notify.verification.VerificationService;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Sends the auto-block SMS for one {@code fs.notifications.customer} intent (FR-03-04, D-25).
 * Contact details come from the vault only now; the single-use link is minted only now and only
 * when the intent allows it; the message is rendered in the customer's locale and must be one GSM-7
 * segment. The outcome is recorded as a {@code customer_notifications} fact (V5), SENT with the
 * provider's reference or FAILED.
 *
 * <p>The intent can arrive before, after or instead of the facts it refers to, so it is classified
 * against PostgreSQL first, by one statement (one snapshot), before anything is sent: the decision
 * table of docs/architecture/decision-fact-ordering.md, section 5.3. Link eligibility and the
 * locale recorded on a FAILED row come from the kept decision's REQUESTED row, never from the
 * intent (5.2).
 */
public final class CustomerSmsSender {

  private static final Logger LOG = LoggerFactory.getLogger(CustomerSmsSender.class);

  /**
   * The classification, in one statement so that it reads one snapshot (5.1): block and REQUESTED
   * row, which PostgresSink writes in one transaction; the recorded outcome; whether the block was
   * resolved; and whether the transaction's kept decision is written ({@code null} when the intent
   * carries no transaction).
   */
  private static final String CLASSIFY =
      """
      SELECT b.id IS NOT NULL, b.account_token, r.verification_link_allowed, r.locale,
      (SELECT n.event FROM customer_notifications n WHERE n.notification_id = ?
        AND n.institution_id = ? AND n.event IN ('SENT', 'FAILED') ORDER BY n.event DESC LIMIT 1),
      b.id IS NOT NULL AND (EXISTS (SELECT 1 FROM unblock_events u
        WHERE u.auto_block_event_id = b.id AND u.institution_id = b.institution_id)
        OR (SELECT s.decision FROM decision_states s WHERE s.institution_id = b.institution_id
          AND s.transaction_id = b.transaction_id ORDER BY s.decision_sequence DESC LIMIT 1)
          <> 'DECLINE'),
      CASE WHEN CAST(? AS uuid) IS NULL THEN NULL ELSE EXISTS (SELECT 1 FROM decision_states k
        WHERE k.institution_id = ? AND k.transaction_id = CAST(? AS uuid)
        AND k.decision_sequence = 1) END
      FROM (SELECT 1) AS one
      LEFT JOIN auto_block_events b ON b.id = ? AND b.institution_id = ?
      LEFT JOIN customer_notifications r ON r.notification_id = ? AND r.institution_id = ?
        AND r.event = 'REQUESTED'
      """;

  /** What PostgreSQL shows for one intent, from one snapshot. */
  private record Snapshot(
      boolean block,
      String blockAccount,
      Boolean requestedLinkAllowed,
      String requestedLocale,
      Outcome outcome,
      boolean resolved,
      Boolean kept) {}

  private static final String RECORD =
      """
      INSERT INTO customer_notifications (notification_id, institution_id, auto_block_event_id,
      account_token, channel, template_key, locale, verification_link_allowed, event,
      provider_reference, occurred_at) VALUES (?, ?, ?, ?, 'SMS', ?, ?, ?, ?, ?, ?)
      ON CONFLICT DO NOTHING
      """;

  /** Outcome of one intent. */
  public enum Outcome {
    /** Accepted by the provider. */
    SENT,
    /** Not sent; recorded as FAILED. */
    FAILED,
    /** Not sent and not recorded: the block was lifted or decided again before the SMS could go. */
    RESOLVED
  }

  private final DataSource dataSource;
  private final ContactDirectory contacts;
  private final InstitutionMessaging institutions;
  private final VerificationService verifications;
  private final SmsGateway gateway;
  private final SmsCatalogue catalogue;
  private final Clock clock;

  /**
   * Creates the sender.
   *
   * @param dataSource connections as {@code fs_app}
   * @param contacts PII vault lookup
   * @param institutions per-institution messaging settings
   * @param verifications verification link issuer
   * @param gateway SMS provider
   * @param catalogue message templates
   * @param clock clock
   */
  public CustomerSmsSender(
      DataSource dataSource,
      ContactDirectory contacts,
      InstitutionMessaging institutions,
      VerificationService verifications,
      SmsGateway gateway,
      SmsCatalogue catalogue,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.contacts = Objects.requireNonNull(contacts, "contacts");
    this.institutions = Objects.requireNonNull(institutions, "institutions");
    this.verifications = Objects.requireNonNull(verifications, "verifications");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Handles one intent.
   *
   * @param institutionId institution from the envelope
   * @param intent the {@code notification-customer} payload
   * @return the outcome
   * @throws SQLException when the outcome cannot be recorded
   * @throws NotYetRecordedException when the intent's auto-block event is not in PostgreSQL yet;
   *     nothing has been sent or issued
   */
  public Outcome send(UUID institutionId, JsonNode intent) throws SQLException {
    return send(institutionId, intent, Optional.empty());
  }

  /**
   * Handles one intent whose record carried its transaction ({@code fs-transaction-id}).
   *
   * @param institutionId institution from the envelope
   * @param intent the {@code notification-customer} payload
   * @param transactionId the intent's transaction, when the record carries it
   * @return the outcome
   * @throws SQLException when PostgreSQL is unavailable or the outcome cannot be recorded
   * @throws NotYetRecordedException S4: the kept decision is not written yet; nothing was done
   * @throws NotTheKeptDecisionException S0 or S3: this intent's block will never be written, or
   *     belongs to another account; nothing was done
   */
  public Outcome send(UUID institutionId, JsonNode intent, Optional<UUID> transactionId)
      throws SQLException {
    UUID notification = UUID.fromString(intent.get("notification_id").asString());
    UUID block = UUID.fromString(intent.get("auto_block_event_id").asString());
    String account = intent.get("account_token").asString();
    Snapshot now = classify(institutionId, notification, block, transactionId);
    if (now.block() && !account.equals(now.blockAccount())) {
      // S0: cannot happen with ids derived from the submission; a second guard against sending
      // another account's block.
      throw new NotTheKeptDecisionException(
          "auto-block event " + block + " belongs to another account");
    }
    if (now.block() && now.outcome() != null) {
      // S1: a re-read, or a duplicate decision of the same submission: not sent again.
      return now.outcome();
    }
    if (now.block() && now.resolved()) {
      // S2r: lifted or decided again before the SMS could go.
      LOG.info("auto-block event {} was resolved before its SMS could be sent", block);
      return Outcome.RESOLVED;
    }
    if (!now.block()) {
      if (Boolean.TRUE.equals(now.kept())) {
        // S3: the kept decision for the transaction is written and has no such block (G2).
        throw new NotTheKeptDecisionException(
            "auto-block event "
                + block
                + " is not the kept decision of transaction "
                + transactionId.orElseThrow());
      }
      // S4: the kept decision is not written yet, or the intent does not say which it is.
      throw new NotYetRecordedException("auto-block event " + block + " is not recorded yet");
    }
    // S2. The kept decision's REQUESTED row decides the link; without it, none (fails closed).
    boolean linkAllowed = Boolean.TRUE.equals(now.requestedLinkAllowed());
    String locale = now.requestedLocale() != null ? now.requestedLocale() : "en";
    Optional<ContactDirectory.Contact> contact = contacts.find(institutionId, account);
    Optional<InstitutionMessaging.Settings> settings = institutions.find(institutionId);
    if (contact.isEmpty() || settings.isEmpty()) {
      record(
          institutionId, notification, block, account, locale, linkAllowed, Outcome.FAILED, null);
      return Outcome.FAILED;
    }
    String link = null;
    if (linkAllowed) {
      link =
          verifications
              .issue(institutionId, block)
              .map(token -> settings.get().verificationBase() + token)
              .orElse(null);
    }
    JsonNode parameters = intent.get("parameters");
    String customerLocale = contact.get().locale();
    String text;
    try {
      text =
          catalogue.autoBlock(
              customerLocale,
              new SmsCatalogue.Values(
                  Money.of(
                      parameters.get("amount").get("amount").asString(),
                      CurrencyCode.valueOf(parameters.get("amount").get("currency").asString())),
                  contact.get().maskedAccount(),
                  parameters.get("local_time").asString(),
                  parameters.get("reference_code").asString(),
                  settings.get().officialPhone(),
                  link));
    } catch (IllegalArgumentException unrenderable) {
      record(
          institutionId,
          notification,
          block,
          account,
          customerLocale,
          linkAllowed,
          Outcome.FAILED,
          null);
      return Outcome.FAILED;
    }
    try {
      String reference = gateway.send(settings.get().senderId(), contact.get().phoneE164(), text);
      record(
          institutionId,
          notification,
          block,
          account,
          customerLocale,
          linkAllowed,
          Outcome.SENT,
          reference);
      return Outcome.SENT;
    } catch (IOException rejected) {
      record(
          institutionId,
          notification,
          block,
          account,
          customerLocale,
          linkAllowed,
          Outcome.FAILED,
          null);
      return Outcome.FAILED;
    }
  }

  private Snapshot classify(
      UUID institution, UUID notification, UUID block, Optional<UUID> transaction)
      throws SQLException {
    try (Connection c = tenant(institution);
        PreparedStatement s = c.prepareStatement(CLASSIFY)) {
      String t = transaction.map(UUID::toString).orElse(null);
      s.setObject(1, notification);
      s.setObject(2, institution);
      s.setString(3, t);
      s.setObject(4, institution);
      s.setString(5, t);
      s.setObject(6, block);
      s.setObject(7, institution);
      s.setObject(8, notification);
      s.setObject(9, institution);
      try (ResultSet row = s.executeQuery()) {
        row.next();
        String outcome = row.getString(5);
        Snapshot snapshot =
            new Snapshot(
                row.getBoolean(1),
                row.getString(2),
                (Boolean) row.getObject(3),
                row.getString(4),
                outcome == null ? null : Outcome.valueOf(outcome),
                row.getBoolean(6),
                (Boolean) row.getObject(7));
        c.commit();
        return snapshot;
      }
    }
  }

  /** A connection in a transaction scoped to the institution (row-level security). */
  private Connection tenant(UUID institution) throws SQLException {
    Connection c = dataSource.getConnection();
    try {
      c.setAutoCommit(false);
      try (PreparedStatement tenant =
          c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
        tenant.setString(1, institution.toString());
        tenant.execute();
      }
      return c;
    } catch (SQLException | RuntimeException e) {
      c.close();
      throw e;
    }
  }

  private void record(
      UUID institution,
      UUID notification,
      UUID block,
      String account,
      String locale,
      boolean linkAllowed,
      Outcome outcome,
      String reference)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement tenant =
          c.prepareStatement("SELECT set_config('fraudshield.institution_id', ?, true)")) {
        tenant.setString(1, institution.toString());
        tenant.execute();
      }
      try (PreparedStatement s = c.prepareStatement(RECORD)) {
        s.setObject(1, notification);
        s.setObject(2, institution);
        s.setObject(3, block);
        s.setString(4, account);
        s.setString(5, CustomerSmsPolicy.TEMPLATE);
        s.setString(6, SmsCatalogue.LOCALES.contains(locale) ? locale : "en");
        s.setBoolean(7, linkAllowed);
        s.setString(8, outcome.name());
        s.setString(9, reference);
        s.setObject(10, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        s.executeUpdate();
      }
      c.commit();
    }
  }
}
