package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.CustomerNotificationPolicy;
import io.github.mariusbayizere.fraudshield.decision.domain.Scoring;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composes the auto-block SMS intent (FR-03-04, D-25, ADR 0012 section 4). The intent carries no
 * contact details and no link: the notification service resolves the phone number and the
 * customer's locale from the PII vault and mints the single-use link at send time, only when {@code
 * verification_link_allowed} is true.
 *
 * <p>The contract requires a masked account in the intent, but FraudShield only ever sees tokens;
 * the intent carries the token's last four characters, and the sender substitutes the vault's
 * masked account number when it renders the message (recorded as a contract gap in ADR 0065).
 */
public final class CustomerSmsPolicy implements CustomerNotificationPolicy {

  /** Template of the auto-block message. */
  public static final String TEMPLATE = "sms.auto_block";

  private final String defaultLocale;

  /**
   * Creates the policy.
   *
   * @param defaultLocale locale used in the intent until the vault supplies the customer's
   */
  public CustomerSmsPolicy(String defaultLocale) {
    this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    if (!SmsCatalogue.LOCALES.contains(defaultLocale)) {
      throw new IllegalArgumentException("unsupported locale " + defaultLocale);
    }
  }

  @Override
  public DecisionEvent.CustomerNotificationRequested compose(
      Transaction transaction, UUID autoBlockEventId, Scoring scoring, Instant at) {
    SelfServicePolicy.Eligibility eligibility =
        SelfServicePolicy.evaluate(scoring, transaction.deviceToken() != null);
    String token = transaction.accountToken();
    return new DecisionEvent.CustomerNotificationRequested(
        UUID.randomUUID(),
        transaction.institutionId(),
        token,
        autoBlockEventId,
        TEMPLATE,
        defaultLocale,
        eligibility.allowed(),
        "***" + token.substring(token.length() - 4),
        transaction.amount(),
        LocalTimes.format(
            transaction.transactionTimestamp(),
            LocalTimes.zone(transaction.amount().currency(), transaction.longitude())),
        ReferenceCodes.of(autoBlockEventId),
        at);
  }
}
