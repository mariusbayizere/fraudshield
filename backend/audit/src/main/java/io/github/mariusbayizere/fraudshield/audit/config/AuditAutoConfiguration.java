package io.github.mariusbayizere.fraudshield.audit.config;

import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.anchor.AnchorKeys;
import io.github.mariusbayizere.fraudshield.audit.anchor.AnchorSigner;
import io.github.mariusbayizere.fraudshield.audit.anchor.AuditAnchorService;
import io.github.mariusbayizere.fraudshield.audit.jdbc.JdbcAuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the audit writer, tenant transactions and, when enabled, the anchoring job. */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"
    })
@ConditionalOnBean(JdbcTemplate.class)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  /**
   * UTC system clock unless the application provides one (tests inject a fixed clock).
   *
   * @return the clock
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Tenant transaction runner.
   *
   * @param transactions transaction template
   * @param jdbc JDBC template
   * @param properties audit properties
   * @return the runner
   */
  @Bean
  @ConditionalOnMissingBean
  public TenantTransactions tenantTransactions(
      TransactionTemplate transactions, JdbcTemplate jdbc, AuditProperties properties) {
    return new TenantTransactions(transactions, jdbc, properties.transactionAttempts());
  }

  /**
   * Audit writer.
   *
   * @param jdbc JDBC template
   * @param properties audit properties
   * @return the writer
   */
  @Bean
  @ConditionalOnMissingBean
  public AuditLog auditLog(JdbcTemplate jdbc, AuditProperties properties) {
    return new JdbcAuditLog(jdbc, properties.writerPartition());
  }

  /** The anchoring job, only where configured. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "fraudshield.audit.anchor.enabled", havingValue = "true")
  @EnableScheduling
  static class Anchoring {

    @Bean
    AuditAnchorService auditAnchorService(
        JdbcTemplate jdbc, TransactionTemplate transactions, AuditProperties properties) {
      AuditProperties.Anchor anchor = properties.anchor();
      return new AuditAnchorService(
          jdbc,
          transactions,
          new AnchorSigner(
              Objects.requireNonNull(
                  anchor.signingKeyId(), "fraudshield.audit.anchor.signing-key-id is required"),
              AnchorKeys.privateKey(
                  Objects.requireNonNull(
                      anchor.signingKey(), "fraudshield.audit.anchor.signing-key is required"))));
    }

    @Bean
    AuditAnchorScheduler auditAnchorScheduler(AuditAnchorService service, Clock clock) {
      return new AuditAnchorScheduler(service, clock);
    }
  }
}
