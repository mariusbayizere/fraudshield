package io.github.mariusbayizere.fraudshield.ingest.ratelimit;

import java.util.UUID;

/**
 * The per-key budget (E.1), counted in transactions (ADR 0058, adopting ADR 0100): a request for
 * one transaction costs one unit, a batch costs its item count, charged once and all or nothing. A
 * limiter answers one question — may this work proceed — and never blocks a caller that is inside
 * its budget.
 */
public interface RateLimiter {

  /**
   * What the limiter decided.
   *
   * @param allowed whether the request may proceed
   * @param limit the budget, in transactions per second
   * @param remaining units left after this charge, floored at zero
   * @param retryAfterSeconds how long a refused caller should wait, at least one second
   * @param degraded true when the shared limiter was unavailable and this instance's own limiter
   *     answered, so the budget held is per instance rather than per deployment
   */
  record Permit(
      boolean allowed, int limit, int remaining, int retryAfterSeconds, boolean degraded) {}

  /**
   * Charges units of budget, all or nothing: when fewer than {@code units} remain, nothing is taken
   * and the answer says how long until they will have refilled.
   *
   * @param apiKeyId the API key, which is what the budget belongs to
   * @param units the transactions this work costs, at least one
   * @return the decision
   */
  Permit take(UUID apiKeyId, int units);
}
