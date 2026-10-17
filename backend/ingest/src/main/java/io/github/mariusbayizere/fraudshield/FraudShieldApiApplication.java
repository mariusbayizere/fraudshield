package io.github.mariusbayizere.fraudshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The FraudShield API process (C.1 {@code fraudshield-api}): the ingestion and decision path (M6)
 * assembled with staff identity, API keys, administration and audit (M7). It scans the whole {@code
 * io.github.mariusbayizere.fraudshield} package; M7's modules contribute their auto-configurations,
 * and {@code StaffIdentityWiring} binds the ingestion API's ports to them.
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
