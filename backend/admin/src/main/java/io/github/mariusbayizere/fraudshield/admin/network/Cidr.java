package io.github.mariusbayizere.fraudshield.admin.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strict CIDR parsing (contract IpAllowlistEntryBase.cidr): an IPv4 or IPv6 literal, a prefix of
 * 16-32 or 32-128, and no host bits set. Anything else is 422 {@code invalid_cidr}.
 */
public final class Cidr {

  private static final Pattern IPV4 = Pattern.compile("^([0-9]{1,3})(\\.[0-9]{1,3}){3}$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$");
  private static final Pattern PREFIX = Pattern.compile("^[0-9]{1,3}$");
  private static final int IPV4_BITS = 32;
  private static final int IPV6_BITS = 128;
  // An office egress range is small; a broad one such as 0.0.0.0/0 would lift the sign-in ceiling
  // for every address (review finding 17).
  private static final int MIN_IPV4_PREFIX = 16;
  private static final int MIN_IPV6_PREFIX = 32;

  private Cidr() {}

  /**
   * Parses and canonicalises a network.
   *
   * @param text the submitted CIDR
   * @return the canonical form, or empty if it is not a network address
   */
  public static Optional<String> canonical(String text) {
    if (text == null) {
      return Optional.empty();
    }
    int slash = text.indexOf('/');
    if (slash <= 0 || slash != text.lastIndexOf('/')) {
      return Optional.empty();
    }
    String address = text.substring(0, slash);
    String prefixText = text.substring(slash + 1);
    boolean v4 = IPV4.matcher(address).matches();
    if ((!v4 && !IPV6.matcher(address).matches()) || !PREFIX.matcher(prefixText).matches()) {
      return Optional.empty();
    }
    if (v4) {
      for (String octet : address.split("\\.")) {
        if (Integer.parseInt(octet) > 255 || (octet.length() > 1 && octet.startsWith("0"))) {
          return Optional.empty();
        }
      }
    }
    byte[] bytes;
    try {
      bytes = InetAddress.getByName(address).getAddress();
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
    int bits = bytes.length * Byte.SIZE;
    if ((v4 && bits != IPV4_BITS) || (!v4 && bits != IPV6_BITS)) {
      return Optional.empty(); // an IPv4-mapped IPv6 literal collapses to 4 bytes
    }
    int prefix = Integer.parseInt(prefixText);
    int narrowest = v4 ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX;
    if (prefix > bits || prefix < narrowest || hasHostBits(bytes, prefix)) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByAddress(bytes).getHostAddress() + "/" + prefix);
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  private static boolean hasHostBits(byte[] address, int prefix) {
    for (int bit = prefix; bit < address.length * Byte.SIZE; bit++) {
      if ((address[bit / Byte.SIZE] & (0x80 >>> (bit % Byte.SIZE))) != 0) {
        return true;
      }
    }
    return false;
  }
}
