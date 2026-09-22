package io.github.mariusbayizere.fraudshield.notify.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The key provider of a deployment whose master keys come from configuration (D-20 for M6; a
 * KMS-backed provider is M9's).
 *
 * <p>Each master key is 32 bytes of base64 under an id. Data keys are AES-256 keys generated per
 * row and wrapped with AES-256-GCM under the master key, with the row's identity as additional
 * authenticated data, so a wrapped key lifted from one row cannot be replayed into another.
 */
public final class PassphraseKeyProvider implements KeyProvider {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final Map<String, SecretKey> masters;
  private final String currentId;

  /**
   * Creates the provider.
   *
   * @param masters master keys by id, each 32 bytes encoded as base64
   * @param currentId the id of the key new rows are wrapped with
   */
  public PassphraseKeyProvider(Map<String, String> masters, String currentId) {
    Objects.requireNonNull(masters, "masters");
    this.currentId = Objects.requireNonNull(currentId, "currentId");
    this.masters =
        masters.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, e -> key(e.getKey(), e.getValue())));
    if (!this.masters.containsKey(currentId)) {
      throw new IllegalArgumentException("no master key with id " + currentId);
    }
  }

  private static SecretKey key(String id, String base64) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException notBase64) {
      throw new IllegalArgumentException("master key " + id + " is not base64", notBase64);
    }
    if (raw.length != 32) {
      throw new IllegalArgumentException(
          "master key " + id + " must be 32 bytes, not " + raw.length);
    }
    return new SecretKeySpec(raw, "AES");
  }

  @Override
  public DataKey newDataKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256, RANDOM);
      final SecretKey data = generator.generateKey();
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, masters.get(currentId), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(currentId));
      byte[] sealed = cipher.doFinal(data.getEncoded());
      byte[] wrapped = new byte[NONCE_BYTES + sealed.length];
      System.arraycopy(nonce, 0, wrapped, 0, NONCE_BYTES);
      System.arraycopy(sealed, 0, wrapped, NONCE_BYTES, sealed.length);
      return new DataKey(currentId, data, wrapped);
    } catch (GeneralSecurityException e) {
      throw new VaultException("could not make a data key", e);
    }
  }

  @Override
  public SecretKey unwrap(String keyId, byte[] wrapped) {
    SecretKey master = masters.get(keyId);
    if (master == null) {
      // A row wrapped with a key this deployment does not hold: refuse, never guess.
      throw new VaultException("no master key with id " + keyId);
    }
    if (wrapped.length <= NONCE_BYTES) {
      throw new VaultException("the wrapped key is too short to be one");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES));
      cipher.updateAAD(aad(keyId));
      byte[] data = cipher.doFinal(wrapped, NONCE_BYTES, wrapped.length - NONCE_BYTES);
      return new SecretKeySpec(data, "AES");
    } catch (GeneralSecurityException e) {
      throw new VaultException("the wrapped key does not verify under master key " + keyId, e);
    }
  }

  private static byte[] aad(String keyId) {
    return ("fraudshield-vault-datakey:" + keyId).getBytes(StandardCharsets.UTF_8);
  }
}
