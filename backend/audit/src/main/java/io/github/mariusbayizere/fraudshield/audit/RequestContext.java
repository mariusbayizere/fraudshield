package io.github.mariusbayizere.fraudshield.audit;

import java.util.UUID;

/**
 * Where a request came from, recorded with audit events.
 *
 * @param ipAddress client address, or null
 * @param userAgent client user agent, or null
 * @param correlationId request correlation ID, or null
 */
public record RequestContext(String ipAddress, String userAgent, UUID correlationId) {

  /** Context of work that no request started (scheduled jobs). */
  public static final RequestContext SYSTEM = new RequestContext(null, null, null);
}
