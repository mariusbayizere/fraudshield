package io.github.mariusbayizere.fraudshield.notify.webhook;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Sends one webhook attempt. */
@FunctionalInterface
public interface WebhookTransport {

  /**
   * POSTs a body.
   *
   * @param url destination
   * @param headers request headers
   * @param body exact body bytes
   * @return HTTP status
   * @throws IOException when no response was received (refused, timed out, not allowed)
   */
  int post(URI url, Map<String, String> headers, byte[] body) throws IOException;
}
