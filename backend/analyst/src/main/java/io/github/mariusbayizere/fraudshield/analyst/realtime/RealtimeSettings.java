package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The real-time channel's limits and timings (ADR 0115 points 3, 5, 6.2 and 8). Every number is
 * ASSUMED in the ADR and configurable here.
 *
 * @param allowedOrigins origins allowed to open the socket; empty means the API's own origin only
 * @param connectTimeout time from upgrade to CONNECT
 * @param sessionsPerUser authenticated sessions per user, per instance
 * @param sessionsPerInstance authenticated sessions per instance
 * @param pairsPerInstance distinct (user, sid) pairs per instance
 * @param pendingPerInstance sockets awaiting CONNECT, per instance
 * @param pendingPerAddress sockets awaiting CONNECT, per client address
 * @param handshakesPerAddressPerMinute handshakes per client address, per minute
 * @param connectsPerUserPerMinute accepted CONNECTs per user, per instance, per minute
 * @param inboundFrameBytes the largest inbound frame
 * @param queueFrames a session's outbound queue, in frames
 * @param queueBytes a session's outbound queue, in bytes
 * @param instanceBudgetBytes the sum of every session's queued bytes
 * @param heartBeat the server's offered heart-beat, each way
 * @param maxClientHeartBeat the longest client heart-beat accepted
 * @param backstopPeriod the revocation backstop's period
 * @param backstopJitter the backstop's jitter, either way
 * @param loadsPerSecond the backstop's database loads per second
 * @param redisPing the Redis availability probe's period
 * @param presenceRefresh how often a user's presence is refreshed
 * @param presenceLifetime a presence entry's lifetime
 * @param handOffQueue the change publisher's hand-off queue, in entries
 * @param producerBufferBytes the change producer's {@code buffer.memory}
 * @param producerMaxBlock the change producer's {@code max.block.ms}
 * @param nameCacheTtl a staff name's lifetime in the fan-out's cache
 * @param resolverThreads the name resolver's threads
 * @param resolverQueue the name resolver's queue, in records
 * @param resolverTimeout the name resolver's load timeout
 * @param consumerStall a consumer that cannot poll for this long closes its sessions
 * @param partitionRefresh how often new partitions are looked for
 */
@ConfigurationProperties("fraudshield.realtime")
public record RealtimeSettings(
    @DefaultValue List<String> allowedOrigins,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("5") int sessionsPerUser,
    @DefaultValue("500") int sessionsPerInstance,
    @DefaultValue("100") int pairsPerInstance,
    @DefaultValue("50") int pendingPerInstance,
    @DefaultValue("200") int pendingPerAddress,
    @DefaultValue("1000") int handshakesPerAddressPerMinute,
    @DefaultValue("10") int connectsPerUserPerMinute,
    @DefaultValue("8192") int inboundFrameBytes,
    @DefaultValue("256") int queueFrames,
    @DefaultValue("262144") int queueBytes,
    @DefaultValue("33554432") long instanceBudgetBytes,
    @DefaultValue("25000ms") Duration heartBeat,
    @DefaultValue("60000ms") Duration maxClientHeartBeat,
    @DefaultValue("1500ms") Duration backstopPeriod,
    @DefaultValue("250ms") Duration backstopJitter,
    @DefaultValue("50") int loadsPerSecond,
    @DefaultValue("1s") Duration redisPing,
    @DefaultValue("10s") Duration presenceRefresh,
    @DefaultValue("30s") Duration presenceLifetime,
    @DefaultValue("10000") int handOffQueue,
    @DefaultValue("8388608") long producerBufferBytes,
    @DefaultValue("100ms") Duration producerMaxBlock,
    @DefaultValue("60s") Duration nameCacheTtl,
    @DefaultValue("2") int resolverThreads,
    @DefaultValue("1000") int resolverQueue,
    @DefaultValue("500ms") Duration resolverTimeout,
    @DefaultValue("30s") Duration consumerStall,
    @DefaultValue("5m") Duration partitionRefresh) {

  /** Bytes per hand-off entry, estimated (ADR 0115 point 6.2), for the start-up check. */
  static final long HAND_OFF_ENTRY_BYTES = 128;

  /** Bytes per queued resolver record, ASSUMED (ADR 0115 point 8), for the start-up check. */
  static final long RESOLVER_RECORD_BYTES = 1024;

  /** Copies the origins. */
  public RealtimeSettings {
    allowedOrigins = List.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
  }

  /**
   * The ADR's values, from the {@link DefaultValue}s.
   *
   * @return the defaults
   */
  public static RealtimeSettings defaults() {
    return new Binder().bindOrCreate("fraudshield.realtime", RealtimeSettings.class);
  }

  /**
   * Every allocation the channel bounds (ADR 0115 point 8): the outbound budget, the producer's
   * buffer, the hand-off queue and the resolver's queue.
   *
   * @return bytes
   */
  public long allocationBytes() {
    return instanceBudgetBytes
        + producerBufferBytes
        + handOffQueue * HAND_OFF_ENTRY_BYTES
        + resolverQueue * RESOLVER_RECORD_BYTES;
  }

  /**
   * Refuses to start when the channel's allocations exceed one eighth of the heap (ADR 0115 point
   * 8).
   *
   * @param maxHeapBytes the instance's maximum heap
   */
  public void checkFits(long maxHeapBytes) {
    if (allocationBytes() > maxHeapBytes / 8) {
      throw new IllegalStateException(
          "the real-time channel's allocations ("
              + allocationBytes()
              + " bytes) exceed one eighth of the maximum heap ("
              + maxHeapBytes
              + " bytes); lower fraudshield.realtime's budgets or raise the heap");
    }
  }
}
