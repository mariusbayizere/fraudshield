package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import java.util.UUID;

/**
 * Redis key layout of the decision path's own state: freeze windows and flags, decision states,
 * hold timers and MCC circuit-breaker counts (ADR 0061). The feature store is not here; the scorer
 * owns it and is its only writer (ADR 0033). Per-account keys share a hash tag so one account's
 * state lives in one cluster slot. Values hold tokens, times and counts only; never personal data
 * (D-20).
 */
final class RedisKeys {

  private RedisKeys() {}

  static String account(UUID institution, String account, String suffix) {
    return "fs:{" + institution + ":" + account + "}:" + suffix;
  }

  static String decision(UUID institution, UUID transaction) {
    return "fs:{" + institution + "}:decision:" + transaction;
  }

  static String mccBucket(UUID institution, String mcc, long minute) {
    return "fs:{" + institution + "}:mcc:" + mcc + ":" + minute;
  }

  static String mccState(UUID institution, String mcc) {
    return "fs:{" + institution + "}:mccstate:" + mcc;
  }

  static final String MCC_ACTIVE = "fs:mcc:active";
  static final String HOLDS = "fs:holds";
  static final String HOLDS_INDEX = "fs:holds:index";
  static final String HOLDS_LEADER = "fs:holds:leader";
}
