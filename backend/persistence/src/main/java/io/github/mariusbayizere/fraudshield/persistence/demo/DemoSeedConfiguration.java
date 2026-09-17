package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Demo seeding beans; present only with the dev or demo profile and seeding enabled (ADR 0019). */
@Configuration(proxyBeanMethods = false)
@Profile({"dev", "demo"})
@ConditionalOnProperty(name = DemoSeedGuard.ENABLED_PROPERTY, havingValue = "true")
@EnableConfigurationProperties(DemoSeedProperties.class)
public class DemoSeedConfiguration {

  @Bean
  DemoDataSeeder demoDataSeeder(
      Environment environment,
      DemoSeedProperties properties,
      JdbcTemplate jdbc,
      TransactionTemplate transactions) {
    // Defence in depth: the listener has already checked, but the seeder must never exist
    // otherwise.
    DemoSeedGuard.check(environment);
    return new DemoDataSeeder(properties, jdbc, transactions, Clock.systemUTC());
  }
}
