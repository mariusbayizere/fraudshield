package io.github.mariusbayizere.fraudshield.auth.support;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Starts the Redis invalidation subscription in the background and keeps retrying, so the
 * application starts and serves while Redis is down (C.4). Until the subscription is up, the cache
 * time-to-live values alone bound how stale a session or API-key record can be.
 */
public final class BackgroundSubscriber implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundSubscriber.class);
  private static final Duration RETRY = Duration.ofSeconds(5);

  private final RedisMessageListenerContainer container;
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread starter;

  /**
   * Wraps a container whose automatic start-up is disabled.
   *
   * @param container the listener container
   */
  public BackgroundSubscriber(RedisMessageListenerContainer container) {
    this.container = Objects.requireNonNull(container, "container");
    container.setAutoStartup(false);
  }

  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    starter =
        Thread.ofVirtual()
            .name("fraudshield-redis-subscriber")
            .start(
                () -> {
                  while (running.get() && !container.isRunning()) {
                    try {
                      container.start();
                    } catch (RuntimeException e) {
                      LOG.warn(
                          "Redis subscription not available yet: {}", e.getClass().getSimpleName());
                      try {
                        Thread.sleep(RETRY);
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                      }
                    }
                  }
                });
  }

  @Override
  public void stop() {
    running.set(false);
    if (starter != null) {
      starter.interrupt();
    }
    if (container.isRunning()) {
      container.stop();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  /**
   * Whether the subscription is established.
   *
   * @return whether the container is running
   */
  public boolean subscribed() {
    return container.isRunning();
  }
}
