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
            if (!isPublic(address)) {
              return false;
            }
          }
          return true;
        };

    /**
     * Whether one resolved address is a public unicast address.
     *
     * <p>An explicit deny-list of the special-purpose ranges, because the {@code InetAddress}
     * predicates miss some and the IPv6 unique-local test was being applied to the first octet of
     * IPv4 addresses, where {@code 252.x} is not unique-local (Principal Review finding 18).
     *
     * @param address a resolved address
     * @return true when a webhook may be sent to it
     */
    private static boolean isPublic(InetAddress address) {
      if (address.isLoopbackAddress()
          || address.isAnyLocalAddress()
          || address.isLinkLocalAddress()
          || address.isMulticastAddress()
          || address.isSiteLocalAddress()) {
        return false;
      }
      byte[] bytes = address.getAddress();
      if (bytes.length == 4) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return !(first == 0 // 0.0.0.0/8 "this network"
            || first == 100 && second >= 64 && second <= 127 // 100.64.0.0/10 carrier-grade NAT
            || first == 127 // loopback
            || first == 192 && second == 0 // 192.0.0.0/24 and 192.0.2.0/24 documentation
            || first == 192 && second == 88 // 192.88.99.0/24 6to4 relay anycast
            || first == 198 && (second == 18 || second == 19) // 198.18.0.0/15 benchmarking
            || first == 198 && second == 51 // 198.51.100.0/24 documentation
            || first == 203 && second == 0 // 203.0.113.0/24 documentation
            || first >= 224); // multicast and 240.0.0.0/4 reserved, including 255.255.255.255
      }
      int first = bytes[0] & 0xff;
      if ((first & 0xfe) == 0xfc) { // fc00::/7 unique local
        return false;
      }
      if (first == 0x20 && (bytes[1] & 0xff) == 0x01) {
        int third = bytes[2] & 0xff;
        // 2001:db8::/32 documentation, 2001::/23 IETF protocol assignments (Teredo, ORCHID).
        return !(third == 0x0d && (bytes[3] & 0xff) == 0xb8) && third != 0x00;
      }
      // ::/128, ::1 and IPv4-mapped addresses are covered by the predicates above and by the
      // IPv4 branch; anything else is public unicast.
      return true;
    }
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
