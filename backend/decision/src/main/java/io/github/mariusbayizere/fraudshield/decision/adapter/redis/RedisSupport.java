package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded waits on Lettuce futures (H.1: every external call has a timeout). */
final class RedisSupport {

  private RedisSupport() {}

  static <T> T await(CompletionStage<T> stage, Duration timeout) {
    try {
      return stage.toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RedisUnavailableException("interrupted waiting for Redis", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new RedisUnavailableException("Redis did not answer in time", e);
    }
  }

  /** Redis failed or timed out; callers fall back or refuse. */
  static final class RedisUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RedisUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
