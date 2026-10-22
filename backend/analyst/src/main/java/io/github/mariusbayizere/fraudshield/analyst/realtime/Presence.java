package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.util.UUID;

/**
 * Where presence lives (ADR 0117 point 4): a Redis sorted set per institution, one member per user
 * with the real-time channel open, scored by its expiry in epoch milliseconds.
 */
public final class Presence {

  private Presence() {}

  /**
   * The institution's presence set.
   *
   * @param institutionId the institution
   * @return the key
   */
  public static String key(UUID institutionId) {
    return "fs:presence:" + institutionId;
  }
}
