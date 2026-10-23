package io.github.mariusbayizere.fraudshield.auth.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of small secrets at rest: webhook signing secrets in {@code api_keys} and
 * Google access tokens held for revocation (D-20 application-layer encryption). A fresh 96-bit
 * nonce per message; the associated data binds a ciphertext to its purpose and owner so it cannot
 * be moved to another row.
 */
public final class SecretBox {

  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final String keyId;
  private final SecretKeySpec key;

  /**
   * Creates a box.
   *
   * @param keyId identifier stored beside each ciphertext, for key rotation
   * @param key 32-byte AES key
   */
  public SecretBox(String keyId, byte[] key) {
    if (key.length != KEY_BYTES) {
      throw new IllegalArgumentException("the secret box key must be 32 bytes");
    }
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.key = new SecretKeySpec(key.clone(), "AES");
  }

  /**
   * The key identifier.
   *
   * @return the identifier
   */
  public String keyId() {
    return keyId;
  }

  /**
   * Encrypts.
   *
   * @param plaintext the secret
   * @param associatedData purpose and owner, authenticated but not encrypted
   * @return nonce followed by ciphertext and tag
   */
  public byte[] seal(String plaintext, String associatedData) {
    byte[] nonce = Crypto.randomBytes(NONCE_BYTES);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(NONCE_BYTES + ciphertext.length)
          .put(nonce)
          .put(ciphertext)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM encryption failed", e);
    }
  }

  /**
   * Decrypts.
   *
   * @param box output of {@link #seal}
   * @param associatedData the same associated data
   * @return the secret
   * @throws IllegalArgumentException if the box was altered, or ciphertext with another key or data
   */
  public String open(byte[] box, String associatedData) {
    if (box.length <= NONCE_BYTES) {
      throw new IllegalArgumentException("not a ciphertext secret");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(box, 0, NONCE_BYTES)));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(box, NONCE_BYTES, box.length)), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("the ciphertext secret does not authenticate", e);
    }
  }
}
