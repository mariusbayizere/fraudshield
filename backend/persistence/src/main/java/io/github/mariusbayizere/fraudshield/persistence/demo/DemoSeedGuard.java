package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Refuses to start any application in which demo seeding is enabled outside the {@code dev} and
 * {@code demo} profiles (ADR 0019).
 *
 * <p>Registered in {@code META-INF/spring.factories}, so it runs as soon as the environment is
 * prepared, before any bean (and so before any database connection) is created. Demo seeding
 * creates known accounts with generated credentials; it must never reach an environment holding
 * real data.
 */
public final class DemoSeedGuard
    implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

  /** Property that enables demo seeding. */
  public static final String ENABLED_PROPERTY = "fraudshield.demo-seed.enabled";

  /** The only profiles under which demo seeding may run. */
  public static final Set<String> ALLOWED_PROFILES = Set.of("dev", "demo");

  @Override
  public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
    check(event.getEnvironment());
  }

  /**
   * Fails when demo seeding is enabled and the active profiles are empty or include a profile other
   * than {@code dev} and {@code demo}.
   *
   * @param environment the application environment
   * @throws IllegalStateException if demo seeding is enabled outside dev or demo
   */
  public static void check(Environment environment) {
    if (!isEnabled(environment)) {
      return;
    }
    if (!onlyDevOrDemoProfiles(environment)) {
      throw new IllegalStateException(
          "Refusing to start: demo seeding ("
              + ENABLED_PROPERTY
              + "=true) is allowed only when every active profile is dev or demo, but the active"
              + " profiles are "
              + activeProfiles(environment)
              + " (ADR 0019)");
    }
  }

  /**
   * Whether at least one profile is active and every active profile is dev or demo.
   *
   * @param environment the application environment
   * @return true only for dev and demo deployments
   */
  public static boolean onlyDevOrDemoProfiles(Environment environment) {
    Set<String> active = activeProfiles(environment);
    return !active.isEmpty() && ALLOWED_PROFILES.containsAll(active);
  }

  static Set<String> activeProfiles(Environment environment) {
    return new TreeSet<>(Arrays.asList(environment.getActiveProfiles()));
  }

  /**
   * Whether demo seeding is enabled, which also means the deployment shows synthetic data (D-21).
   *
   * @param environment the application environment
   * @return true when enabled
   */
  public static boolean isEnabled(Environment environment) {
    return environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
  }
}
