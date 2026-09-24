package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
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
import tools.jackson.databind.JsonNode;

/**
 * Sends the auto-block SMS for one {@code fs.notifications.customer} intent (FR-03-04, D-25).
 * Contact details come from the vault only now; the single-use link is minted only now and only
 * when the intent allows it; the message is rendered in the customer's locale and must be one GSM-7
 * segment. The outcome is recorded as a {@code customer_notifications} fact (V5), SENT with the
 * provider's reference or FAILED.
 */
public final class CustomerSmsSender {

  private static final String BLOCK_RECORDED =
      "SELECT 1 FROM auto_block_events WHERE id = ? AND institution_id = ?";

  private static final String OUTCOME =
      "SELECT event FROM customer_notifications WHERE notification_id = ? AND institution_id = ?"
          + " AND event IN ('SENT', 'FAILED') ORDER BY event DESC LIMIT 1";

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
    FAILED
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
    UUID notification = UUID.fromString(intent.get("notification_id").asString());
    UUID block = UUID.fromString(intent.get("auto_block_event_id").asString());
    String account = intent.get("account_token").asString();
    String locale = intent.get("locale").asString();
    boolean linkAllowed = intent.get("verification_link_allowed").asBoolean();
    if (!recorded(institutionId, block)) {
      // The spool drains to Kafka and PostgreSQL independently, so this intent can arrive before
      // its auto-block event is written. Nothing is sent or issued until it is. Sending first would
      // leave an SMS with no record, and a replayed dead letter would send it twice.
      throw new NotYetRecordedException("auto-block event " + block + " is not recorded yet");
    }
    Optional<Outcome> already = outcome(institutionId, notification);
    if (already.isPresent()) {
      // A re-read after the outcome was recorded (a crash before the offset commit): the customer
      // has had this SMS, or it failed for good; it is not sent again (review 11, 2026-09-24).
      return already.get();
    }
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

  private boolean recorded(UUID institution, UUID block) throws SQLException {
    try (Connection c = tenant(institution);
        PreparedStatement s = c.prepareStatement(BLOCK_RECORDED)) {
      s.setObject(1, block);
      s.setObject(2, institution);
      try (ResultSet row = s.executeQuery()) {
        boolean found = row.next();
        c.commit();
        return found;
      }
    }
  }

  private Optional<Outcome> outcome(UUID institution, UUID notification) throws SQLException {
    try (Connection c = tenant(institution);
        PreparedStatement s = c.prepareStatement(OUTCOME)) {
      s.setObject(1, notification);
      s.setObject(2, institution);
      try (ResultSet row = s.executeQuery()) {
        Optional<Outcome> found =
            row.next() ? Optional.of(Outcome.valueOf(row.getString(1))) : Optional.empty();
        c.commit();
        return found;
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
