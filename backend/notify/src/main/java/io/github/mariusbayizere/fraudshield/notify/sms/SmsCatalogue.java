package io.github.mariusbayizere.fraudshield.notify.sms;

import io.github.mariusbayizere.fraudshield.common.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Localised customer SMS templates (E.7, D-43), loaded from {@code sms-catalogue.json}, rendered
 * and checked: every message is GSM-7 and one segment, or it is not sent.
 */
public final class SmsCatalogue {

  /**
   * Longest verification link every template fits in one segment with, at the worst-case amount,
   * number and time lengths (asserted by SmsCatalogueTest): {@code https://} plus a domain of up to
   * 21 characters, {@code /v/} and the 22-character token.
   */
  public static final int MAX_LINK_LENGTH = 54;

  /** Supported locales (D-43). */
  public static final Set<String> LOCALES = Set.of("en", "rw", "fr", "sw");

  /** A template's review status (D-43). */
  public enum Status {
    /** Drafted without native-speaker review. */
    MACHINE_DRAFT,
    /** Reviewed by a native speaker. */
    REVIEWED
  }

  /**
   * The values a message is rendered with.
   *
   * @param amount amount in the transaction currency
   * @param maskedAccount masked account, for example {@code ***4821}
   * @param localTime local time with zone, for example {@code 10:15 CAT}
   * @param referenceCode reference code
   * @param officialPhone the institution's official number
   * @param link the single-use verification link, or null when self-service is disabled
   */
  public record Values(
      Money amount,
      String maskedAccount,
      String localTime,
      String referenceCode,
      String officialPhone,
      String link) {}

  private record Template(Status status, String link, String noLink) {}

  private final Map<String, Template> templates = new HashMap<>();

  /** Loads the catalogue from the classpath. */
  public SmsCatalogue() {
    try (InputStream in = SmsCatalogue.class.getResourceAsStream("sms-catalogue.json")) {
      JsonNode root = new ObjectMapper().readTree(Objects.requireNonNull(in, "catalogue"));
      JsonNode autoBlock = root.get("templates").get(CustomerSmsPolicy.TEMPLATE);
      for (String locale : LOCALES) {
        JsonNode node = autoBlock.get(locale);
        templates.put(
            locale,
            new Template(
                Status.valueOf(node.get("status").asString().toUpperCase(java.util.Locale.ROOT)),
                node.get("link").asString(),
                node.get("no_link").asString()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the SMS catalogue", e);
    }
  }

  /**
   * A template's review status.
   *
   * @param locale locale
   * @return its status
   */
  public Status status(String locale) {
    return template(locale).status();
  }

  /**
   * Renders the auto-block message.
   *
   * @param locale customer locale
   * @param values the values; a null link selects the no-self-service text (D-25)
   * @return the message, GSM-7 and at most 160 septets
   * @throws IllegalArgumentException when the rendered message would not fit or is not GSM-7
   */
  public String autoBlock(String locale, Values values) {
    Template template = template(locale);
    if (values.link() != null
        && (!values.link().startsWith("https://") || values.link().length() > MAX_LINK_LENGTH)) {
      throw new IllegalArgumentException(
          "verification links are HTTPS and at most " + MAX_LINK_LENGTH + " characters");
    }
    String text =
        (values.link() == null ? template.noLink() : template.link())
            .replace("{amount}", values.amount().displayAmount().toPlainString())
            .replace("{currency}", values.amount().currency().name())
            .replace("{account}", values.maskedAccount())
            .replace("{time}", values.localTime())
            .replace("{ref}", values.referenceCode())
            .replace("{phone}", values.officialPhone())
            .replace("{link}", values.link() == null ? "" : values.link());
    if (!Gsm7.fitsOneSegment(text)) {
      throw new IllegalArgumentException(
          "the "
              + locale
              + " message is not one GSM-7 segment ("
              + Gsm7.septets(text)
              + " septets)");
    }
    return text;
  }

  private Template template(String locale) {
    Template template = templates.get(locale);
    if (template == null) {
      throw new IllegalArgumentException("unsupported locale " + locale);
    }
    return template;
  }
}
