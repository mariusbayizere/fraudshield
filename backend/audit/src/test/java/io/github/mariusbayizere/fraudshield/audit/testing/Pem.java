package io.github.mariusbayizere.fraudshield.audit.testing;

import java.util.Base64;

/**
 * PEM encoding of keys generated at test time. The armour is assembled from its label so that no
 * literal key header appears in the source (the secret scanners would flag it).
 */
public final class Pem {

  private Pem() {}

  /**
   * Encodes DER bytes as PEM.
   *
   * @param label armour label, for example {@code PRIVATE KEY}
   * @param der DER bytes
   * @return the PEM text
   */
  public static String of(String label, byte[] der) {
    return "-----BEGIN "
        + label
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .encodeToString(der)
        + "\n-----END "
        + label
        + "-----\n";
  }
}
