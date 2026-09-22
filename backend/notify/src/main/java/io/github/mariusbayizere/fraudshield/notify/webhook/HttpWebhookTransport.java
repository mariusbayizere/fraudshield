package io.github.mariusbayizere.fraudshield.notify.webhook;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Webhook attempts over HTTPS with the Java HTTP client: 5-second connect and 10-second request
 * timeouts (success is a 2xx within 10 s), no redirects, and a destination check against
 * server-side request forgery before every attempt (ADR 0011: webhook URLs must resolve to public
 * addresses).
 */
public final class HttpWebhookTransport implements WebhookTransport {

  /** Which destinations may be called. */
  @FunctionalInterface
  public interface Destinations {
    /**
     * Whether a URL may be called.
     *
     * @param url the destination
     * @return true when allowed
     * @throws UnknownHostException when the host does not resolve
     */
    boolean allowed(URI url) throws UnknownHostException;

    /** HTTPS to public addresses only: no loopback, private, link-local or multicast address. */
    Destinations PUBLIC_HTTPS =
        url -> {
          if (!"https".equals(url.getScheme())
              || url.getUserInfo() != null
              || url.getHost() == null) {
            return false;
          }
          for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || (address.getAddress()[0] & 0xfe) == 0xfc) {
              return false;
            }
          }
          return true;
        };
  }

  private final HttpClient client;
  private final Destinations destinations;

  /**
   * Creates the transport.
   *
   * @param destinations which destinations may be called
   */
  public HttpWebhookTransport(Destinations destinations) {
    this.destinations = Objects.requireNonNull(destinations, "destinations");
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public int post(URI url, Map<String, String> headers, byte[] body) throws IOException {
    if (!destinations.allowed(url)) {
      throw new IOException("the webhook destination is not allowed");
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(request::header);
    try {
      return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }
}
