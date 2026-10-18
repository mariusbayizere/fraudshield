package io.github.mariusbayizere.fraudshield.persistence.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo seeding settings. The credentials are generated locally by {@code make seed-demo}, passed as
 * environment variables and never committed (ADR 0019).
 *
 * @param enabled whether to seed demo data (allowed only with the dev or demo profile)
 * @param passwords one password per demo account
 * @param apiKey the demo API key, {@code fsk_dev_<keyId>_<secret>}
 * @param apiKeyPepperHex server-side pepper for API-key HMACs, hex encoded, at least 32 bytes
 */
@ConfigurationProperties("fraudshield.demo-seed")
public record DemoSeedProperties(
    boolean enabled, Passwords passwords, String apiKey, String apiKeyPepperHex) {

  /**
   * Passwords of the demo accounts.
   *
   * @param analyst ANALYST account
   * @param seniorAnalyst SENIOR_ANALYST account
   * @param riskOfficerA first RISK_OFFICER account
   * @param riskOfficerB second RISK_OFFICER account (dual control needs two)
   * @param admin ADMIN account
   */
  public record Passwords(
      String analyst,
      String seniorAnalyst,
      String riskOfficerA,
      String riskOfficerB,
      String admin) {

    @Override
    public String toString() {
      return "Passwords[<redacted>]";
    }
  }

  @Override
  public String toString() {
    return "DemoSeedProperties[enabled=" + enabled + ", credentials=<redacted>]";
  }
}
