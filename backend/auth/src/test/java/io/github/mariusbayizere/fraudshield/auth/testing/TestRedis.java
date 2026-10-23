package io.github.mariusbayizere.fraudshield.auth.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis for integration tests: the image of docker-compose.yml, started once per JVM (tests are
 * tagged requires-docker, ADR 0010), or an existing server named by {@code
 * FRAUDSHIELD_TEST_REDIS_URL}.
 */
public final class TestRedis {

  /** Image of the Redis service in docker-compose.yml. */
  public static final String IMAGE = "redis:7.2.16";

  private static final int PORT = 6379;
  private static GenericContainer<?> container;

  private TestRedis() {}

  /**
   * The Redis URL.
   *
   * @return redis://host:port
   */
  public static synchronized String url() {
    String external = System.getenv("FRAUDSHIELD_TEST_REDIS_URL");
    if (external != null && !external.isBlank()) {
      return external;
    }
    if (container == null) {
      container = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(PORT);
      container.start();
    }
    return "redis://" + container.getHost() + ":" + container.getMappedPort(PORT);
  }
}
