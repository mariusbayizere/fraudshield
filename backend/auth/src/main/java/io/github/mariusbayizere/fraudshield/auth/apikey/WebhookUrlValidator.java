package io.github.mariusbayizere.fraudshield.auth.apikey;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Checks a webhook URL before it is stored (contract ApiKeyCreate.webhook_url, SSRF): an absolute
 * HTTPS URL without user info, whose host is a DNS name (no IP literal) resolving only to public
 * addresses. Loopback, private (RFC 1918, RFC 4193), link-local (including the 169.254.169.254
 * cloud metadata address), carrier-grade NAT (100.64.0.0/10), multicast, unspecified and other
 * special-purpose addresses are refused. The dispatcher (M6) must resolve and check again at every
 * delivery, because DNS can change after this check.
 */
public final class WebhookUrlValidator {

  private static final int MAX_LENGTH = 2048;
  private static final Pattern IPV4_LITERAL = Pattern.compile("^[0-9.]+$");

  /** Resolves host names; a seam for tests. */
  @FunctionalInterface
  public interface Resolver {

    /**
     * Resolves a host.
     *
     * @param host host name
     * @return its addresses
     * @throws UnknownHostException if it does not resolve
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final Resolver resolver;

  /**
   * Creates the validator.
   *
   * @param resolver host resolver ({@code InetAddress::getAllByName} in production)
   */
  public WebhookUrlValidator(Resolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  /**
   * Whether the URL may be stored.
   *
   * @param url the URL
   * @return whether it is allowed
   */
  public boolean allowed(String url) {
    if (url == null || url.length() > MAX_LENGTH) {
      return false;
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return false;
    }
    String host = uri.getHost();
    if (!"https".equals(uri.getScheme())
        || uri.getRawUserInfo() != null
        || host == null
        || host.startsWith("[")
        || host.contains(":")
        || IPV4_LITERAL.matcher(host).matches()) {
      return false;
    }
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      return false;
    }
    if (addresses.length == 0) {
      return false;
    }
    for (InetAddress address : addresses) {
      if (!isPublic(address)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether an address is publicly routable.
   *
   * @param address the address
   * @return whether it is public
   */
  static boolean isPublic(InetAddress address) {
    if (address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      return isPublicIpv4(b);
    }
    if (address instanceof Inet6Address) {
      int first = b[0] & 0xff;
      boolean uniqueLocal = (first & 0xfe) == 0xfc; // fc00::/7
      boolean documentation =
          first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8;
      if (uniqueLocal || documentation) {
        return false;
      }
      if (isIpv4Mapped(b)) {
        return isPublicIpv4(new byte[] {b[12], b[13], b[14], b[15]});
      }
      return true;
    }
    return false;
  }

  private static boolean isIpv4Mapped(byte[] b) {
    for (int i = 0; i < 10; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
  }

  private static boolean isPublicIpv4(byte[] b) {
    int a = b[0] & 0xff;
    int second = b[1] & 0xff;
    return !(a == 0 // 0.0.0.0/8
        || a == 10 // RFC 1918
        || a == 127 // loopback
        || (a == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
        || (a == 169 && second == 254) // link-local, cloud metadata
        || (a == 172 && second >= 16 && second <= 31) // RFC 1918
        || (a == 192 && second == 168) // RFC 1918
        || (a == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24 IETF assignments
        || (a == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 benchmarking
        || a >= 224); // multicast, reserved, broadcast
  }
}
