package io.github.mariusbayizere.fraudshield.audit.anchor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads Ed25519 anchor keys from PEM files: a PKCS#8 private key for the anchoring job and X.509
 * public keys for verification. Ed25519 is in the JDK, so no cryptography dependency is added.
 */
public final class AnchorKeys {

  private static final String ALGORITHM = "Ed25519";

  private AnchorKeys() {}

  /**
   * Reads a PKCS#8 PEM private key.
   *
   * @param pem path to the PEM file
   * @return the key
   */
  public static PrivateKey privateKey(Path pem) {
    try {
      return KeyFactory.getInstance(ALGORITHM)
          .generatePrivate(new PKCS8EncodedKeySpec(decode(pem, "PRIVATE KEY")));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("not an Ed25519 PKCS#8 private key: " + pem, e);
    }
  }

  /**
   * Reads an X.509 PEM public key.
   *
   * @param pem path to the PEM file
   * @return the key
   */
  public static PublicKey publicKey(Path pem) {
    try {
      return KeyFactory.getInstance(ALGORITHM)
          .generatePublic(new X509EncodedKeySpec(decode(pem, "PUBLIC KEY")));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("not an Ed25519 X.509 public key: " + pem, e);
    }
  }

  private static byte[] decode(Path pem, String label) {
    String text;
    try {
      text = Files.readString(pem, StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read key file " + pem, e);
    }
    String begin = "-----BEGIN " + label + "-----";
    String end = "-----END " + label + "-----";
    int start = text.indexOf(begin);
    int stop = text.indexOf(end);
    if (start < 0 || stop < start) {
      throw new IllegalStateException(pem + " is not a PEM " + label);
    }
    return Base64.getMimeDecoder().decode(text.substring(start + begin.length(), stop));
  }
}
