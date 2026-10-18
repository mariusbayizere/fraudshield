package io.github.mariusbayizere.fraudshield.common.config;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thresholds and MEDIUM timeout policy for all six channels (FR-05-07).
 *
 * <p>Lowering a threshold, or switching the timeout policy to {@code DECLINE_AND_VERIFY}, blocks
 * more and is tightening. Raising a threshold, or switching to {@code RELEASE_WITH_TIMEOUT_LABEL},
 * is loosening. A change containing any loosening element is loosening as a whole.
 *
 * @param byChannel exactly one threshold per channel
 */
public record ChannelThresholds(Map<Channel, ChannelThreshold> byChannel)
    implements ConfigSettings {

  /** Requires a threshold for every channel and stores an unmodifiable copy. */
  public ChannelThresholds {
    Objects.requireNonNull(byChannel, "byChannel");
    EnumMap<Channel, ChannelThreshold> copy = new EnumMap<>(Channel.class);
    copy.putAll(byChannel);
    if (copy.size() != Channel.values().length || copy.containsValue(null)) {
      throw new IllegalArgumentException("a threshold is required for every channel");
    }
    byChannel = Collections.unmodifiableMap(copy);
  }

  @Override
  public ConfigKind kind() {
    return ConfigKind.CHANNEL_THRESHOLDS;
  }

  /**
   * Returns these thresholds with one channel replaced.
   *
   * @param channel the channel to change
   * @param threshold its new threshold
   * @return the new thresholds
   */
  public ChannelThresholds with(Channel channel, ChannelThreshold threshold) {
    EnumMap<Channel, ChannelThreshold> copy = new EnumMap<>(byChannel);
    copy.put(channel, threshold);
    return new ChannelThresholds(copy);
  }

  @Override
  public Optional<ChangeDirection> directionFrom(ConfigSettings previous) {
    if (!(previous instanceof ChannelThresholds before)) {
      throw new IllegalArgumentException("cannot compare channel thresholds with " + previous);
    }
    boolean tightens = false;
    boolean loosens = false;
    for (Channel channel : Channel.values()) {
      ChannelThreshold old = before.byChannel.get(channel);
      ChannelThreshold now = byChannel.get(channel);
      int medium = now.medium().compareTo(old.medium());
      int high = now.high().compareTo(old.high());
      tightens |= medium < 0 || high < 0;
      loosens |= medium > 0 || high > 0;
      if (now.timeoutPolicy() != old.timeoutPolicy()) {
        boolean declines = now.timeoutPolicy() == MediumTimeoutPolicy.DECLINE_AND_VERIFY;
        tightens |= declines;
        loosens |= !declines;
      }
    }
    if (loosens) {
      return Optional.of(ChangeDirection.LOOSENING);
    }
    return tightens ? Optional.of(ChangeDirection.TIGHTENING) : Optional.empty();
  }
}
