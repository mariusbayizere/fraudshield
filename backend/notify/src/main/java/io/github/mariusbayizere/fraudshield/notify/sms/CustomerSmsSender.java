package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.notify.kafka.EnvelopeConsumer;
import io.github.mariusbayizere.fraudshield.notify.kafka.MalformedHeaderException;
import io.github.mariusbayizere.fraudshield.notify.kafka.MalformedPayloadException;
import io.github.mariusbayizere.fraudshield.notify.kafka.NotTheKeptDecisionException;
import io.github.mariusbayizere.fraudshield.notify.kafka.NotYetRecordedException;
import io.github.mariusbayizere.fraudshield.notify.kafka.ProviderResult;
import io.github.mariusbayizere.fraudshield.notify.kafka.TransactionHeader;
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
import java.util.regex.Pattern;
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
 *
 * <p>Every field read from the intent is validated before anything is issued, sent or recorded
 * (12.1). The message is rendered as a trial before the verification is issued, so a rendering
 * failure leaves no link behind. Once the provider has been called, its result is handed to the
 * consumer to keep before the outcome is written; a re-read holding that result writes it and never
 * calls the provider again (12.4). Every statement runs under a {@code statement_timeout} below the
 * pool's socket timeout (12.3).
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
        OR (SELECT s.decision FROM v_decision_states s WHERE s.institution_id = b.institution_id
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

  /** Sets the tenant and the statement timeout of a transaction, in one statement (12.3). */
  static final String TENANT =
      "SELECT set_config('fraudshield.institution_id', ?, true),"
          + " set_config('statement_timeout', '5s', true)";

  private static final Pattern CANONICAL_UUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /** A placeholder exactly as long as the longest link, for the trial rendering (12.4). */
  private static final String PLACEHOLDER_LINK =
      "https://" + "x".repeat(SmsCatalogue.MAX_LINK_LENGTH - "https://".length());

  /** The intent's fields, validated (12.1). */
  private record Intent(
      UUID notification,
      UUID block,
      String account,
      Money amount,
      String localTime,
      String referenceCode) {}

  /** Outcome of one intent. */
  public enum Outcome {
    /** Accepted by the provider. */
    SENT,
    /** Not known to be accepted by the provider (12.4); recorded as FAILED. */
    FAILED,
    /** Not sent and not recorded: the block was lifted or decided again before the SMS could go. */
    RESOLVED
  }

  private final java.util.concurrent.atomic.AtomicLong resolvedBeforeSend =
      new java.util.concurrent.atomic.AtomicLong();
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
   * Intents not sent because their block was resolved first (S2r), since start-up.
   *
   * @return the count
   */
  public long resolvedBeforeSend() {
    return resolvedBeforeSend.get();
  }

  /**
   * Handles one intent whose record carried no transaction.
   *
   * @param institutionId institution from the envelope
   * @param intent the {@code notification-customer} payload
   * @return the outcome
   * @throws SQLException when PostgreSQL is unavailable or the outcome cannot be recorded
   * @throws NotYetRecordedException S4: B is not recorded yet, and without a transaction the intent
   *     cannot be judged permanent; nothing was done
   */
  public Outcome send(UUID institutionId, JsonNode intent) throws SQLException {
    return send(institutionId, intent, Optional.empty());
  }

  /**
   * Handles one intent whose record carried its transaction ({@code fs-transaction-id}), with no
   * provider result kept and none to keep: for callers outside the consumer.
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
    return send(
        institutionId,
        intent,
        transactionId.map(TransactionHeader::valid).orElse(TransactionHeader.ABSENT),
        Optional.empty(),
        result -> {});
  }

  /**
   * Handles one intent as the consumer hands it over (sections 5 and 12).
   *
   * @param institutionId institution from the envelope
   * @param intent the {@code notification-customer} payload
   * @param transaction the {@code fs-transaction-id} header's state
   * @param kept the provider result already kept for this record, if any
   * @param keeper takes the provider result as soon as the provider has answered
   * @return the outcome
   * @throws SQLException when PostgreSQL is unavailable or the outcome cannot be recorded
   * @throws MalformedPayloadException a field the sender reads is absent or unreadable; nothing was
   *     done
   * @throws NotYetRecordedException S4: the kept decision is not written yet; nothing was done
   * @throws NotTheKeptDecisionException S0 or S3: this intent's block will never be written, or
   *     belongs to another account; nothing was done
   * @throws MalformedHeaderException S3m: B is absent and the header is malformed; nothing was done
   */
  public Outcome send(
      UUID institutionId,
      JsonNode intent,
      TransactionHeader transaction,
      Optional<ProviderResult> kept,
      EnvelopeConsumer.ResultKeeper keeper)
      throws SQLException {
    Intent read = read(intent);
    UUID notification = read.notification();
    UUID block = read.block();
    String account = read.account();
    Snapshot now = classify(institutionId, notification, block, transaction.transaction());
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
    if (kept.isPresent()) {
      // The provider was called for this record already: write its result, never send again (12.4).
      if (!now.block()) {
        throw new IllegalStateException(
            "auto-block event " + block + " is not visible although its SMS went to the provider");
      }
      ProviderResult result = kept.get();
      record(
          institutionId,
          notification,
          block,
          account,
          result.locale(),
          Boolean.TRUE.equals(now.requestedLinkAllowed()),
          result.sent() ? Outcome.SENT : Outcome.FAILED,
          result.reference());
      return result.sent() ? Outcome.SENT : Outcome.FAILED;
    }
    if (now.block() && now.resolved()) {
      // S2r: lifted or decided again before the SMS could go.
      resolvedBeforeSend.incrementAndGet();
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
                + transaction.transaction().orElseThrow());
      }
      if (transaction.state() == TransactionHeader.State.MALFORMED) {
        // S3m: which decision PostgreSQL kept cannot be read from the record; not waited on.
        throw new MalformedHeaderException(
            "auto-block event "
                + block
                + " is not recorded and the intent's transaction is"
                + " malformed");
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
    String customerLocale = contact.get().locale();
    try {
      // The trial: the message about to be sent, with a link as long as the longest one, so a
      // rendering failure leaves no verification behind (12.4). Its text is discarded.
      render(read, contact.get(), settings.get(), linkAllowed ? PLACEHOLDER_LINK : null);
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
    String link = null;
    if (linkAllowed) {
      link =
          verifications
              .issue(institutionId, block)
              .map(token -> settings.get().verificationBase() + token)
              .orElse(null);
    }
    String text;
    try {
      text = render(read, contact.get(), settings.get(), link);
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
    ProviderResult result;
    try {
      String reference = gateway.send(settings.get().senderId(), contact.get().phoneE164(), text);
      result = ProviderResult.sent(customerLocale, link != null, reference);
    } catch (ProviderReplyUnknownException unknown) {
      result = ProviderResult.failed(customerLocale, link != null, true);
    } catch (IOException rejected) {
      result = ProviderResult.failed(customerLocale, link != null, false);
    } catch (RuntimeException unexpected) {
      // Any other failure of the call may have reached the provider: never retried (12.4).
      LOG.error("the SMS provider adapter failed for auto-block event {}", block, unexpected);
      result = ProviderResult.failed(customerLocale, link != null, true);
    }
    keeper.keep(result);
    record(
        institutionId,
        notification,
        block,
        account,
        result.locale(),
        linkAllowed,
        result.sent() ? Outcome.SENT : Outcome.FAILED,
        result.reference());
    return result.sent() ? Outcome.SENT : Outcome.FAILED;
  }

  private String render(
      Intent intent,
      ContactDirectory.Contact contact,
      InstitutionMessaging.Settings settings,
      String link) {
    return catalogue.autoBlock(
        contact.locale(),
        new SmsCatalogue.Values(
            intent.amount(),
            contact.maskedAccount(),
            intent.localTime(),
            intent.referenceCode(),
            settings.officialPhone(),
            link));
  }

  /** Validates every field the sender reads from the intent (12.1). */
  private static Intent read(JsonNode intent) {
    try {
      UUID notification = uuid(intent, "notification_id");
      UUID block = uuid(intent, "auto_block_event_id");
      String account = text(intent, "account_token");
      JsonNode parameters = intent.get("parameters");
      if (parameters == null || !parameters.isObject()) {
        throw new IllegalArgumentException("parameters is absent or not an object");
      }
      JsonNode amount = parameters.get("amount");
      if (amount == null || !amount.isObject()) {
        throw new IllegalArgumentException("parameters.amount is absent or not an object");
      }
      Money money =
          Money.of(text(amount, "amount"), CurrencyCode.valueOf(text(amount, "currency")));
      return new Intent(
          notification,
          block,
          account,
          money,
          text(parameters, "local_time"),
          text(parameters, "reference_code"));
    } catch (RuntimeException unreadable) {
      throw new MalformedPayloadException(
          "an SMS intent with a field that is absent or unreadable: " + unreadable.getMessage(),
          unreadable);
    }
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = text(node, field);
    if (!CANONICAL_UUID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is not a canonical UUID");
    }
    return UUID.fromString(value);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.asString().isEmpty()) {
      throw new IllegalArgumentException(field + " is absent, not a string, or empty");
    }
    return value.asString();
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
      try (PreparedStatement tenant = c.prepareStatement(TENANT)) {
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
      try (PreparedStatement tenant = c.prepareStatement(TENANT)) {
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
