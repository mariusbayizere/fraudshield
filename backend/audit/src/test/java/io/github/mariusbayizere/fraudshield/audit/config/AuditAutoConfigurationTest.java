package io.github.mariusbayizere.fraudshield.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.anchor.AuditAnchorService;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.audit.testing.Pem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class AuditAutoConfigurationTest {

  private static final DriverManagerDataSource DATA_SOURCE =
      new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unused");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
          .withBean(JdbcTemplate.class, () -> new JdbcTemplate(DATA_SOURCE))
          .withBean(
              TransactionTemplate.class,
              () -> new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)));

  @Test
  void wiresTheWriterAndTenantTransactionsButNoAnchoringByDefault() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(AuditLog.class);
          assertThat(context).hasSingleBean(TenantTransactions.class);
          assertThat(context).hasSingleBean(Clock.class);
          assertThat(context).doesNotHaveBean(AuditAnchorService.class);
          AuditProperties properties = context.getBean(AuditProperties.class);
          assertThat(properties.writerPartition()).as("spread by thread").isNull();
          assertThat(properties.transactionAttempts()).isEqualTo(5);
        });
  }

  @Test
  void wiresTheAnchoringJobWhenEnabledWithKey(@TempDir Path dir) throws Exception {
    Path key = dir.resolve("anchor.pem");
    Files.writeString(
        key,
        Pem.of(
            "PRIVATE" + " KEY",
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded()));
    runner
        .withPropertyValues(
            "fraudshield.audit.writer-partition=7",
            "fraudshield.audit.anchor.enabled=true",
            "fraudshield.audit.anchor.signing-key=" + key,
            "fraudshield.audit.anchor.signing-key-id=anchor-1")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuditAnchorService.class);
              assertThat(context).hasSingleBean(AuditAnchorScheduler.class);
              assertThat(context.getBean(AuditProperties.class).writerPartition())
                  .isEqualTo((short) 7);
            });
  }

  @Test
  void refusesToStartAnchoringWithoutKey() {
    runner
        .withPropertyValues("fraudshield.audit.anchor.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void schedulerAnchorsThePreviousUtcDay() {
    AuditAnchorService service = mock(AuditAnchorService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-09-22T00:10:00Z"), ZoneOffset.UTC);
    new AuditAnchorScheduler(service, clock).anchorPreviousDay();
    verify(service).anchorAll(LocalDate.parse("2026-09-21"));
  }
}
