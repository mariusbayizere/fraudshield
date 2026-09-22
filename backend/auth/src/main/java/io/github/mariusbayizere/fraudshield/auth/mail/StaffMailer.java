package io.github.mariusbayizere.fraudshield.auth.mail;

import io.github.mariusbayizere.fraudshield.auth.domain.StaffLocale;

/**
 * Emails to staff (port). Implementations send after the triggering transaction commits and never
 * log message bodies, which carry one-time codes, links and temporary passwords.
 */
public interface StaffMailer {

  /**
   * One email.
   *
   * @param to recipient address
   * @param locale language
   * @param template message template
   * @param firstName recipient's first name
   * @param secret the code, link or temporary password the email delivers, or null
   */
  record Message(
      String to, StaffLocale locale, Template template, String firstName, String secret) {

    @Override
    public String toString() {
      return "Message[template=" + template + ", locale=" + locale + ", to=<redacted>]";
    }
  }

  /** Staff email templates. */
  enum Template {
    /** Account locked after failed sign-ins, with the unlock link (FR-07-06). */
    ACCOUNT_LOCKED,
    /** Password reset code (FR-07-08). */
    PASSWORD_RESET_CODE,
    /** New account created by an administrator, with a temporary password (FR-06-01). */
    WELCOME,
    /** Someone tried to register with details of an existing account (ADR 0014). */
    REGISTRATION_EXISTING_ACCOUNT,
    /** Email verification link for a self-registration (FR-07-02). */
    EMAIL_VERIFICATION
  }

  /**
   * Sends an email, asynchronously.
   *
   * @param message the email
   */
  void send(Message message);
}
