package io.github.mariusbayizere.fraudshield.notify.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A key management service in memory, for tests: key-encryption keys that never leave it, and the
 * context bound to every wrapping as AES-GCM additional data, which is what the real services do.
 */
public final class InMemoryKms implements KmsClient {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Map<String, SecretKey> keks = new ConcurrentHashMap<>();

  /** Calls made, so a test can show that a refused row never reached the service. */
  public final AtomicInteger calls = new AtomicInteger();

  /** When set, every call fails as an unreachable service would. */
  public volatile boolean down;

  /**
   * A service holding fresh key-encryption keys.
   *
   * @param kekIds their ids
   */
  public InMemoryKms(String... kekIds) {
    for (String id : kekIds) {
      byte[] raw = new byte[32];
      RANDOM.nextBytes(raw);
      keks.put(id, new SecretKeySpec(raw, "AES"));
    }
  }

  @Override
  public GeneratedKey generateDataKey(String kekId, Map<String, String> context) {
    calls.incrementAndGet();
    byte[] data = new byte[32];
    RANDOM.nextBytes(data);
    byte[] nonce = new byte[12];
    RANDOM.nextBytes(nonce);
    byte[] sealed = cipher(Cipher.ENCRYPT_MODE, kekId, nonce, context).apply(data);
    byte[] wrapped = new byte[12 + sealed.length];
    System.arraycopy(nonce, 0, wrapped, 0, 12);
    System.arraycopy(sealed, 0, wrapped, 12, sealed.length);
    return new GeneratedKey(data, wrapped);
  }

  @Override
  public byte[] decrypt(String kekId, byte[] wrapped, Map<String, String> context) {
    calls.incrementAndGet();
    if (wrapped.length <= 12) {
      throw VaultException.permanent("not a wrapped key", null);
    }
    byte[] nonce = java.util.Arrays.copyOf(wrapped, 12);
    byte[] sealed = java.util.Arrays.copyOfRange(wrapped, 12, wrapped.length);
    return cipher(Cipher.DECRYPT_MODE, kekId, nonce, context).apply(sealed);
  }

  private interface Op {
    byte[] apply(byte[] input);
  }

  private Op cipher(int mode, String kekId, byte[] nonce, Map<String, String> context) {
    if (down) {
      throw new VaultException("the key service is unreachable");
    }
    SecretKey kek = keks.get(kekId);
    if (kek == null) {
      throw new VaultException("the key service has no key " + kekId);
    }
    return input -> {
      try {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, kek, new GCMParameterSpec(128, nonce));
        c.updateAAD(new TreeMap<>(context).toString().getBytes(StandardCharsets.UTF_8));
        return c.doFinal(input);
      } catch (GeneralSecurityException e) {
        throw VaultException.permanent("the key service refused the wrapping", e);
      }
    };
  }
}
