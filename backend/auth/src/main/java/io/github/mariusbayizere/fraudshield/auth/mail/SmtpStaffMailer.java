package io.github.mariusbayizere.fraudshield.auth.mail;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends staff email over SMTP (Mailpit locally, D-51) from localised message catalogues ({@code
 * fraudshield/auth/mail*.properties}, D-43). Sending runs on an executor so a slow mail server
 * never delays a sign-in response; FR-07-06 asks for the unlock email within 30 seconds.
 */
public final class SmtpStaffMailer implements StaffMailer {

  private static final Logger LOG = LoggerFactory.getLogger(SmtpStaffMailer.class);

  private final JavaMailSender sender;
  private final MessageSource messages;
  private final Executor executor;
  private final String from;
  private final String consoleBaseUrl;

  /**
   * Creates the mailer.
   *
   * @param sender SMTP sender
   * @param messages mail catalogues
   * @param executor executor for sending
   * @param from sender address
   * @param consoleBaseUrl console base URL, for links
   */
  public SmtpStaffMailer(
      JavaMailSender sender,
      MessageSource messages,
      Executor executor,
      String from,
      String consoleBaseUrl) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.from = Objects.requireNonNull(from, "from");
    this.consoleBaseUrl = Objects.requireNonNull(consoleBaseUrl, "consoleBaseUrl");
  }

  @Override
  public void send(Message message) {
    executor.execute(() -> deliver(message));
  }

  void deliver(Message message) {
    SimpleMailMessage mail = compose(message);
    try {
      sender.send(mail);
    } catch (MailException e) {
      LOG.error(
          "staff email {} could not be sent: {}", message.template(), e.getClass().getSimpleName());
    }
  }

  SimpleMailMessage compose(Message message) {
    Locale locale = Locale.forLanguageTag(message.locale().name());
    String key = "mail." + message.template().name().toLowerCase(Locale.ROOT);
    Object[] args = {message.firstName(), message.secret(), consoleBaseUrl};
    SimpleMailMessage mail = new SimpleMailMessage();
    mail.setFrom(from);
    mail.setTo(message.to());
    mail.setSubject(messages.getMessage(key + ".subject", args, locale));
    mail.setText(messages.getMessage(key + ".body", args, locale));
    return mail;
  }
}
