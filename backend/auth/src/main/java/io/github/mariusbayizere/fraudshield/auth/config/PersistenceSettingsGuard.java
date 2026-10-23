package io.github.mariusbayizere.fraudshield.auth.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Refuses to start with persistence settings that would undermine the schema or tenant isolation
 * (ADR 0071): Hibernate may only validate the schema (Flyway owns it), and open-in-view stays off.
 */
public final class PersistenceSettingsGuard {

  private static final Set<String> ALLOWED_DDL = Set.of("validate", "none");

  /**
   * Checks the settings.
   *
   * @param environment the application environment
   * @throws IllegalStateException if a setting is not allowed
   */
  public PersistenceSettingsGuard(Environment environment) {
    String ddl =
        environment
            .getProperty("spring.jpa.hibernate.ddl-auto", "validate")
            .toLowerCase(Locale.ROOT);
    String hbm2ddl = environment.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto");
    if (!ALLOWED_DDL.contains(ddl)
        || (hbm2ddl != null && !ALLOWED_DDL.contains(hbm2ddl.toLowerCase(Locale.ROOT)))) {
      throw new IllegalStateException(
          "spring.jpa.hibernate.ddl-auto must be validate or none: Flyway owns the schema");
    }
    if (environment.getProperty("spring.jpa.open-in-view", Boolean.class, false)) {
      throw new IllegalStateException(
          "spring.jpa.open-in-view must be false: no query may run outside a tenant transaction");
    }
  }
}
