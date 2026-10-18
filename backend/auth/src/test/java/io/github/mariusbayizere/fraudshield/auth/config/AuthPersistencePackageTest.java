package io.github.mariusbayizere.fraudshield.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;

/**
 * The staff-persistence package is registered once, whichever application it joins. The registrar
 * reads Boot's internal record of the registered packages, so a Boot upgrade that changes it fails
 * here rather than at an application's start.
 */
class AuthPersistencePackageTest {

  private static final String STAFF = "io.github.mariusbayizere.fraudshield.auth.persistence";

  private static List<String> afterRegistrar(String... alreadyRegistered) {
    DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
    if (alreadyRegistered.length > 0) {
      AutoConfigurationPackages.register(registry, alreadyRegistered);
    }
    new AuthPersistenceAutoConfiguration.StaffPersistencePackage()
        .registerBeanDefinitions(null, registry);
    return AutoConfigurationPackages.get(registry);
  }

  @Test
  void addedWhenNoPackageIsRegistered() {
    assertThat(afterRegistrar()).containsExactly(STAFF);
  }

  @Test
  void addedBesideAnApplicationPackageThatDoesNotCoverIt() {
    // M7's own test application lives in auth.testing.
    assertThat(afterRegistrar("io.github.mariusbayizere.fraudshield.auth.testing"))
        .containsExactly("io.github.mariusbayizere.fraudshield.auth.testing", STAFF);
  }

  @Test
  void notAddedWhenTheApplicationPackageCoversIt() {
    // The API application sits in the root package; a second registration would make Spring Data
    // define every staff repository twice.
    assertThat(afterRegistrar("io.github.mariusbayizere.fraudshield"))
        .containsExactly("io.github.mariusbayizere.fraudshield");
  }
}
