package io.github.mariusbayizere.fraudshield.persistence.demo;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers {@link SyntheticDataStatus} in every Spring Boot application that has a FraudShield
 * database, so no service can serve a seeded database under a production profile (ADR 0019). Runs
 * after Flyway: Spring Boot initialises the database before creating {@link JdbcTemplate} users.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@ConditionalOnBean(JdbcTemplate.class)
public class SyntheticDataAutoConfiguration {

  @Bean
  SyntheticDataStatus syntheticDataStatus(Environment environment, JdbcTemplate jdbc) {
    return new SyntheticDataStatus(environment, jdbc);
  }
}
