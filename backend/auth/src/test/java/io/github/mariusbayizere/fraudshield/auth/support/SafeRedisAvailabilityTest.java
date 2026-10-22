package io.github.mariusbayizere.fraudshield.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** {@link SafeRedis#available()} (ADR 0115 point 3): failures in a row, never a missing key. */
class SafeRedisAvailabilityTest {

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final StringRedisTemplate template = mock(StringRedisTemplate.class);

  @Test
  @Tag("FR-04-02")
  void missingKeyIsSuccessAndNeverCountsAsDown() {
    when(template.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenReturn(null);
    SafeRedis redis = new SafeRedis(template);
    for (int i = 0; i < 10; i++) {
      assertThat(redis.get("missing")).isEmpty();
    }
    assertThat(redis.available()).isTrue();
  }

  @Test
  @Tag("FR-04-02")
  void threeFailuresConsecutivelyAreDownAndOneSuccessIsUpAgain() {
    when(template.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenThrow(new QueryTimeoutException("down"));
    SafeRedis redis = new SafeRedis(template);
    redis.get("a");
    redis.get("b");
    assertThat(redis.available()).as("two failures").isTrue();
    redis.get("c");
    assertThat(redis.available()).as("three in a row").isFalse();
    doReturn("1").when(values).get("d");
    assertThat(redis.get("d")).contains("1");
    assertThat(redis.available()).isTrue();
  }

  @Test
  @Tag("FR-04-02")
  void noRedisConfiguredIsNeverAvailable() {
    SafeRedis redis = new SafeRedis(null);
    assertThat(redis.available()).isFalse();
    assertThat(redis.ping()).isFalse();
  }
}
