package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import jakarta.servlet.http.HttpServletRequest;

/** Request helpers shared by the controllers. */
public final class Requests {

  private Requests() {}

  /**
   * The audit context of a request. The client address is the servlet remote address; behind the
   * edge proxy the application enables forwarded-header handling (server.forward-headers-strategy),
   * so only the trusted proxy's X-Forwarded-For is honoured.
   *
   * @param request the request
   * @return client address, user agent and correlation ID
   */
  public static RequestContext context(HttpServletRequest request) {
    return new RequestContext(
        request.getRemoteAddr(), request.getHeader("User-Agent"), CorrelationIdFilter.of(request));
  }
}
