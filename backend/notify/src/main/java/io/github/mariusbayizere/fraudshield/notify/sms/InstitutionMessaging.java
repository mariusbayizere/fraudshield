package io.github.mariusbayizere.fraudshield.notify.sms;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Per-institution messaging settings (D-25: registered sender ID, official number, own domain). */
@FunctionalInterface
public interface InstitutionMessaging {

  /**
   * One institution's settings.
   *
   * @param senderId registered sender ID
   * @param officialPhone the number customers are told to call
   * @param verificationBase HTTPS base of the institution's own verification domain, ending in
   *     {@code /v/}
   */
  record Settings(String senderId, String officialPhone, String verificationBase) {
    /** Requires HTTPS and a short base so every link fits one segment. */
    public Settings {
      Objects.requireNonNull(senderId, "senderId");
      Objects.requireNonNull(officialPhone, "officialPhone");
      Objects.requireNonNull(verificationBase, "verificationBase");
      if (!verificationBase.startsWith("https://")
          || !verificationBase.endsWith("/v/")
          || verificationBase.length() + 22 > SmsCatalogue.MAX_LINK_LENGTH) {
        throw new IllegalArgumentException(
            "the verification base must be https://<domain>/v/"
                + " and leave room for the token within "
                + SmsCatalogue.MAX_LINK_LENGTH
                + " characters");
      }
    }
  }

  /**
   * An institution's settings.
   *
   * @param institutionId institution
   * @return settings, or empty when the institution has none configured
   */
  Optional<Settings> find(UUID institutionId);
}
