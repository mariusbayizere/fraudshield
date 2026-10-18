package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The synthetic-data guard fails closed for data sources it cannot check, and applications without
 * a database start with the banner decided by their profiles (PB-16, ADR 0019).
 */
@Tag("D-21")
class SyntheticDataGuardCoverageTest {

  /** A data source defined in code, without spring.datasource.url. */
  @SpringBootConfiguration
  static class CustomDataSourceApplication {
    @Bean
    DriverManagerDataSource customDataSource() {
      return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unused");
    }
  }

  /** No data source at all. */
  @SpringBootConfiguration
  static class NoDatabaseApplication {}

  private static DefaultListableBeanFactory factoryWithDataSources(int count) {
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    for (int i = 0; i < count; i++) {
      factory.registerBeanDefinition(
          "dataSource" + i, new RootBeanDefinition(DriverManagerDataSource.class));
    }
    return factory;
  }

  @Test
  void uncheckedOrMultipleDataSourcesAreRefused() {
    assertThatThrownBy(() -> SyntheticDataGuard.requireCoverage(factoryWithDataSources(1), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic-data check");
    assertThatThrownBy(() -> SyntheticDataGuard.requireCoverage(factoryWithDataSources(2), true))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> SyntheticDataGuard.requireCoverage(factoryWithDataSources(1), true))
        .doesNotThrowAnyException();
    assertThatCode(() -> SyntheticDataGuard.requireCoverage(factoryWithDataSources(0), false))
        .doesNotThrowAnyException();
  }

  @Test
  void customDataSourceWithoutTheUrlPropertyFailsClosedAtStartup() {
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(CustomDataSourceApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=prod", "--spring.datasource.url="))
        .satisfies(
            e ->
                assertThat(NestedExceptionUtils.getMostSpecificCause(e))
                    .hasMessageContaining("covers exactly one data source"));
  }

  @Test
  void applicationWithoutDatabaseStartsWithTheBannerDecidedByProfiles() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(NoDatabaseApplication.class)
            .web(WebApplicationType.NONE)
            // The module's application.yml has a default URL; an application without a database
            // has none.
            .run("--spring.profiles.active=prod", "--spring.datasource.url=")) {
      assertThat(context.getBean(SyntheticDataStatus.class).syntheticData()).isFalse();
    }
  }
}
