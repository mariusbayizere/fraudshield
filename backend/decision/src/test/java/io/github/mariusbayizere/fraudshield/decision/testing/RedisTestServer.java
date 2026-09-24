package io.github.mariusbayizere.fraudshield.decision.testing;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** A Redis for integration tests, with the image of docker-compose.yml. */
public final class RedisTestServer implements AutoCloseable {

  /** Image of the Redis service in docker-compose.yml. */
  public static final String IMAGE = "redis:7.2.16";

  private final GenericContainer<?> container;
  private final RedisClient client;

  /** Starts Redis. */
  @SuppressWarnings("resource")
  public RedisTestServer() {
    container = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
    container.start();
    client = RedisClient.create(uri());
  }

  /**
   * The Redis URI.
   *
   * @return redis://host:port
   */
  public String uri() {
    return "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
  }

  /**
   * A new connection.
   *
   * @return the connection
   */
  public StatefulRedisConnection<String, String> connect() {
    return client.connect();
  }

  /** Deletes every key, as a cache flush or failover to an empty replica would. */
  public void flush() {
    try (StatefulRedisConnection<String, String> connection = connect()) {
      connection.sync().flushall();
    }
  }

  /** Freezes the Redis process. */
  public void pause() {
    container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
  }

  /** Resumes a paused Redis. */
  public void unpause() {
    container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
  }

  @Override
  public void close() {
    client.shutdown();
    container.stop();
  }
}
