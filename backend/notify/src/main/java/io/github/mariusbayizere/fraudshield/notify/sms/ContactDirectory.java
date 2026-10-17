package io.github.mariusbayizere.fraudshield.notify.sms;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an account token to the customer's contact details at send time, from the PII vault
 * (D-20, ADR 0012 section 4). The only place a phone number exists in FraudShield, and only for the
 * duration of one send; it is never logged, stored or put on a topic.
 */
@FunctionalInterface
public interface ContactDirectory {

  /**
   * The customer's contact.
   *
   * @param phoneE164 phone number in E.164
   * @param locale preferred locale ({@code en}, {@code rw}, {@code fr} or {@code sw})
   * @param maskedAccount the account number as the customer knows it, masked (***1234)
   */
  record Contact(String phoneE164, String locale, String maskedAccount) {
    @Override
    public String toString() {
      return "Contact[phone=<redacted>, locale=" + locale + "]";
    }
  }

  /**
   * Looks a customer up.
   *
   * @param institutionId institution
   * @param accountToken account token
   * @return the contact, or empty when the vault has none
   */
  Optional<Contact> find(UUID institutionId, String accountToken);
}
