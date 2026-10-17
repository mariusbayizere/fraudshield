package io.github.mariusbayizere.fraudshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The FraudShield API process (C.1 {@code fraudshield-api}). Scans the whole {@code
 * io.github.mariusbayizere.fraudshield} package, so modules built in parallel contribute their
 * beans: the API-key module (M7) must provide an {@link
 * io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator}, and the application
 * refuses to start without one.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudShieldApiApplication {

  /**
   * Starts the API.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(FraudShieldApiApplication.class, args);
  }
}
