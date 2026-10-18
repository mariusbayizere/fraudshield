package io.github.mariusbayizere.fraudshield.auth.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The persistence defaults of every FraudShield service (ADR 0071), added with the lowest
 * precedence so they apply unless configuration says otherwise; {@link PersistenceSettingsGuard}
 * then refuses the settings that must never be changed.
 *
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto=validate}: Flyway owns the schema; Hibernate only
 *       checks that its mappings match it at startup.
 *   <li>{@code spring.jpa.open-in-view=false}: no database session spans the HTTP response, so lazy
 *       loading can never issue queries outside the tenant transaction.
 *   <li>{@code hibernate.default_schema=fraudshield}: entities name tables without a schema.
 * </ul>
 */
public final class PersistenceDefaults implements EnvironmentPostProcessor, Ordered {

  /** Name of the property source. */
  public static final String SOURCE = "fraudshieldPersistenceDefaults";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                SOURCE,
                Map.of(
                    "spring.jpa.hibernate.ddl-auto", "validate",
                    "spring.jpa.open-in-view", "false",
                    "spring.jpa.properties.hibernate.default_schema", "fraudshield")));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
