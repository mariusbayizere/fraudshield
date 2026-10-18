package io.github.mariusbayizere.fraudshield.auth.testing;

import java.time.Instant;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The application under test: Spring Boot with every FraudShield auto-configuration on the
 * classpath, a movable clock and a recording mailer. Nothing else is scanned.
 */
@SpringBootApplication
public class AuthTestApplication {

  /**
   * The movable clock, replacing the system clock everywhere.
   *
   * @return the clock
   */
  @Bean
  @Primary
  public MutableClock mutableClock() {
    return new MutableClock(Instant.now());
  }

  /**
   * The recording mailer.
   *
   * @return the mailer
   */
  @Bean
  public RecordingStaffMailer staffMailer() {
    return new RecordingStaffMailer();
  }
}
