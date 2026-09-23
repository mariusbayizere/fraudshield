package io.github.mariusbayizere.fraudshield.notify.vault;

import java.util.Map;

/**
 * The one thing the vault asks of a key management service (D-20, ADR 0069 point 3): make a data
 * key under a key-encryption key it never releases, and unwrap one it made.
 *
 * <p>The shape is the common core of the cloud services, so a production binding is a thin adapter:
 * AWS KMS {@code GenerateDataKey}/{@code Decrypt} with an encryption context, Google Cloud KMS
 * {@code Encrypt}/{@code Decrypt} with additional authenticated data, HashiCorp Vault Transit
 * {@code datakey}/{@code decrypt} with a derivation context. Which service is M9's choice with the
 * cloud; every implementation must pass {@code KmsClientContract}.
 *
 * <p>An implementation must bind {@code context} to the wrapping (a wrong context fails to unwrap),
 * must never return the key-encryption key, and must throw rather than return anything when it
 * cannot answer. A wrapping, key or context that does not verify, or a key the service does not
 * have, is {@link VaultException#permanent}; a service that cannot be reached is not, because the
 * notification consumers dead-letter the first and retry the second.
 */
public interface KmsClient {

  /**
   * A new data key, in the clear for this use and wrapped for storage.
   *
   * @param plaintext 32 bytes of key material; the caller clears it after use
   * @param wrapped the key as the service wrapped it, opaque to the caller
   */
  record GeneratedKey(byte[] plaintext, byte[] wrapped) {

    /** Copies both arrays. */
    public GeneratedKey {
      plaintext = plaintext.clone();
      wrapped = wrapped.clone();
    }

    @Override
    public byte[] plaintext() {
      return plaintext.clone();
    }

    @Override
    public byte[] wrapped() {
      return wrapped.clone();
    }

    @Override
    public String toString() {
      return "GeneratedKey[plaintext=<redacted>, wrapped=" + wrapped.length + " bytes]";
    }
  }

  /**
   * Makes an AES-256 data key under a key-encryption key.
   *
   * @param kekId the service's identifier of the key-encryption key
   * @param context bound to the wrapping; the same map must be given to unwrap
   * @return the key
   * @throws VaultException when the service refuses or cannot be reached
   */
  GeneratedKey generateDataKey(String kekId, Map<String, String> context);

  /**
   * Unwraps a data key this service made.
   *
   * @param kekId the key-encryption key it was made under
   * @param wrapped what {@link #generateDataKey} returned as {@code wrapped}
   * @param context the context given when it was made
   * @return the 32-byte key material
   * @throws VaultException when the wrapping, key or context does not verify, or the service cannot
   *     be reached
   */
  byte[] decrypt(String kekId, byte[] wrapped, Map<String, String> context);
}
