package io.github.mariusbayizere.fraudshield.auth.testing;

import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Records staff email instead of sending it (test double for the SMTP adapter, D-51). */
public final class RecordingStaffMailer implements StaffMailer {

  /**
   * A recorded email.
   *
   * @param message the email
   * @param at when it was handed over
   */
  public record Sent(Message message, Instant at) {}

  private final List<Sent> sent = new CopyOnWriteArrayList<>();

  @Override
  public void send(Message message) {
    sent.add(new Sent(message, Instant.now()));
  }

  /**
   * Waits up to a timeout for an email matching a predicate.
   *
   * @param to recipient
   * @param template template
   * @param timeout how long to wait
   * @return the email, if one arrived
   */
  public Optional<Sent> await(String to, Template template, Duration timeout) {
    Predicate<Sent> match = s -> s.message().to().equals(to) && s.message().template() == template;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<Sent> found = sent.stream().filter(match).reduce((first, second) -> second);
      if (found.isPresent()) {
        return found;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /**
   * Emails sent to an address.
   *
   * @param to recipient
   * @return the emails
   */
  public List<Sent> to(String to) {
    return sent.stream().filter(s -> s.message().to().equals(to)).toList();
  }
}
