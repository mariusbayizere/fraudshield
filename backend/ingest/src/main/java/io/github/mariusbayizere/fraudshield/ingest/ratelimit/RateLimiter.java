package io.github.mariusbayizere.fraudshield.ingest.ratelimit;

import java.util.UUID;

/**
 * The per-key request budget (E.1). A limiter answers one question — may this request proceed — and
 * never blocks a caller that is inside its budget.
 */
public interface RateLimiter {

  /**
   * What the limiter decided.
   *
   * @param allowed whether the request may proceed
   * @param limit the budget, in requests per second
   * @param remaining tokens left after this request, floored at zero
   * @param retryAfterSeconds how long a refused caller should wait, at least one second
   * @param degraded true when the shared limiter was unavailable and this instance's own limiter
   *     answered, so the budget held is per instance rather than per deployment
   */
  record Permit(
      boolean allowed, int limit, int remaining, int retryAfterSeconds, boolean degraded) {}

  /**
   * Takes one request's worth of budget.
   *
   * @param apiKeyId the API key, which is what the budget belongs to
   * @return the decision
   */
  Permit take(UUID apiKeyId);
}
