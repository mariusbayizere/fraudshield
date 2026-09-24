package io.github.mariusbayizere.fraudshield.ingest.config;

import java.util.Set;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with Hibernate settings that would take the schema away from Flyway or let a
 * lazy load escape its transaction (ADR 0068, following ADR 0071 point 3).
 *
 * <p>Flyway owns the schema (ADR 0017): {@code ddl-auto} may only be {@code validate} or {@code
 * none}, so Hibernate checks its mappings and never creates or alters anything — and a mapping that
 * no longer matches a migration stops the application instead of being silently repaired. {@code
 * open-in-view} must be false, because a persistence context that outlived the service transaction
 * could run a lazy load on a connection whose institution was never set, and row-level security
 * would then answer with nothing rather than refuse.
 */
@Component
public final class PersistenceSettingsGuard implements EnvironmentAware {

  private static final Set<String> ALLOWED_DDL = Set.of("validate", "none", "");

  @Override
  public void setEnvironment(Environment environment) {
    check(environment, "spring.jpa.hibernate.ddl-auto");
    check(environment, "spring.jpa.properties.hibernate.hbm2ddl.auto");
    check(
        environment, "spring.jpa.properties.jakarta.persistence.schema-generation.database.action");
    String openInView = environment.getProperty("spring.jpa.open-in-view", "false");
    if (!"false".equals(openInView.strip())) {
      throw new IllegalStateException(
          "spring.jpa.open-in-view must be false: a persistence context must not outlive the"
              + " transaction that set the institution (ADR 0068)");
    }
  }

  private static void check(Environment environment, String property) {
    // Matched exactly, neither lowercased nor compared case-insensitively: case mapping is
    // locale-dependent, and a guard should refuse anything it does not recognise as safe. So
    // "VALIDATE" is refused as firmly as "update" — fail closed, and say which value was seen.
    String value = environment.getProperty(property, "").strip();
    if (!ALLOWED_DDL.contains(value)) {
      throw new IllegalStateException(
          property
              + " is '"
              + value
              + "': Flyway owns the schema, so only 'validate' or 'none' are allowed (ADR 0068)");
    }
  }
}
