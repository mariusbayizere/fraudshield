package io.github.mariusbayizere.fraudshield.auth.config;

import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserEntity;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;

/**
 * Registers the staff-identity entity and repository package with Spring Boot's JPA support (ADR
 * 0071). Adding an auto-configuration package, rather than {@code @EnableJpaRepositories}, keeps
 * the application's own entity and repository scanning intact: M6's configuration entities are
 * found next to these.
 */
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
@AutoConfigurationPackage(basePackageClasses = StaffUserEntity.class)
public class AuthPersistenceAutoConfiguration {}
