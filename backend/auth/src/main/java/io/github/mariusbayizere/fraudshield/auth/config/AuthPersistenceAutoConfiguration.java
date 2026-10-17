package io.github.mariusbayizere.fraudshield.auth.config;

import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserEntity;
import java.util.List;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers the staff-identity entity and repository package with Spring Boot's JPA support (ADR
 * 0071). Adding an auto-configuration package, rather than {@code @EnableJpaRepositories}, keeps
 * the application's own entity and repository scanning intact: M6's configuration entities are
 * found next to these.
 *
 * <p>The package is added only when no auto-configuration package already covers it. The API
 * application sits in the root package, so its own package covers this one; adding it a second time
 * made Spring Data register every staff repository twice, and the context refused to start.
 */
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
@Import(AuthPersistenceAutoConfiguration.StaffPersistencePackage.class)
public class AuthPersistenceAutoConfiguration {

  /** Adds {@code auth.persistence} unless a registered package already contains it. */
  static final class StaffPersistencePackage implements ImportBeanDefinitionRegistrar {

    /** The bean under which Boot keeps the auto-configuration packages: its class name. */
    private static final String PACKAGES = AutoConfigurationPackages.class.getName();

    @Override
    public void registerBeanDefinitions(
        AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
      String staff = StaffUserEntity.class.getPackageName();
      if (registered(registry).stream()
          .noneMatch(base -> staff.equals(base) || staff.startsWith(base + "."))) {
        AutoConfigurationPackages.register(registry, staff);
      }
    }

    /**
     * The packages registered so far, read from the bean definition rather than through {@link
     * AutoConfigurationPackages#get}: that would create the packages bean now, with the list as it
     * stands, and a package registered afterwards would never reach it (the first version of this
     * class did that, and the staff repositories vanished from {@code AuthTestApplication}). Boot
     * 4.1 keeps the list as the definition's first constructor argument.
     */
    private static List<String> registered(BeanDefinitionRegistry registry) {
      if (!registry.containsBeanDefinition(PACKAGES)) {
        return List.of();
      }
      ConstructorArgumentValues.ValueHolder holder =
          registry
              .getBeanDefinition(PACKAGES)
              .getConstructorArgumentValues()
              .getIndexedArgumentValue(0, String[].class);
      return holder != null && holder.getValue() instanceof String[] packages
          ? List.of(packages)
          : List.of();
    }
  }
}
