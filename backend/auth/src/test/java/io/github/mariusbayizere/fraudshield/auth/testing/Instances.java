package io.github.mariusbayizere.fraudshield.auth.testing;

import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Independent "instances" sharing one Redis, for cross-instance invalidation tests. */
public final class Instances {

  private Instances() {}

  /**
   * A Redis connection of its own.
   *
   * @return the template
   */
  public static StringRedisTemplate redis() {
    URI uri = URI.create(TestRedis.url());
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
    factory.afterPropertiesSet();
    factory.start();
    return new StringRedisTemplate(factory);
  }

  /**
   * Subscribes a listener to a channel on its own connection.
   *
   * @param redis template whose connection factory to use
   * @param channel channel
   * @param listener message handler
   * @return the running container (stop it after the test)
   */
  public static RedisMessageListenerContainer subscribe(
      StringRedisTemplate redis, String channel, Consumer<String> listener) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(redis.getConnectionFactory());
    container.addMessageListener(
        (message, pattern) ->
            listener.accept(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(channel));
    container.afterPropertiesSet();
    container.start();
    return container;
  }

  /**
   * A SafeRedis over a template.
   *
   * @param redis the template, or null for "Redis unreachable"
   * @return the wrapper
   */
  public static SafeRedis safe(StringRedisTemplate redis) {
    return new SafeRedis(redis);
  }

  /**
   * Writes a measurement under target/benchmarks for the evidence record.
   *
   * @param name file name
   * @param json content
   */
  public static void record(String name, String json) {
    try {
      Path dir = Path.of("target", "benchmarks");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(name), json);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
