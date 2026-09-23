package io.github.mariusbayizere.fraudshield.notify.vault;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The production key provider (D-20, ADR 0069 point 3): the key-encryption key lives in a key
 * management service and never enters this process; each row's data key is generated and unwrapped
 * by the service.
 *
 * <p>The key id stored beside a row is the service's key-encryption key id, so rotation keeps old
 * rows readable for as long as the service keeps the old key enabled. A row whose key id is not one
 * this deployment is configured to use is refused before the service is asked, so a row cannot be
 * used to make the vault call an arbitrary key.
 */
public final class KmsKeyProvider implements KeyProvider {

  /** Bound to every wrapping, so a data key made for the vault is useless to any other caller. */
  static final Map<String, String> CONTEXT = Map.of("purpose", "fraudshield-vault-datakey");

  private final KmsClient kms;
  private final String currentKekId;
  private final Set<String> readableKekIds;

  /**
   * Creates the provider.
   *
   * @param kms the service
   * @param currentKekId the key-encryption key new rows are wrapped with
   * @param readableKekIds every key-encryption key whose rows may be read, including the current
   */
  public KmsKeyProvider(KmsClient kms, String currentKekId, Set<String> readableKekIds) {
    this.kms = Objects.requireNonNull(kms, "kms");
    this.currentKekId = Objects.requireNonNull(currentKekId, "currentKekId");
    this.readableKekIds = Set.copyOf(readableKekIds);
    if (currentKekId.isBlank() || currentKekId.length() > 64) {
      // vault.contacts.key_id holds at most 64 characters: an id that does not fit is an alias.
      throw new IllegalArgumentException("a key-encryption key id is 1 to 64 characters");
    }
    if (!this.readableKekIds.contains(currentKekId)) {
      throw new IllegalArgumentException("the current key-encryption key must be readable");
    }
  }

  @Override
  public DataKey newDataKey() {
    KmsClient.GeneratedKey generated = kms.generateDataKey(currentKekId, CONTEXT);
    byte[] plaintext = generated.plaintext();
    try {
      if (plaintext.length != 32) {
        throw new VaultException("the key service returned a data key that is not 256 bits");
      }
      return new DataKey(currentKekId, new SecretKeySpec(plaintext, "AES"), generated.wrapped());
    } finally {
      Arrays.fill(plaintext, (byte) 0);
    }
  }

  @Override
  public SecretKey unwrap(String keyId, byte[] wrapped) {
    if (!readableKekIds.contains(keyId)) {
      throw new VaultException("no master key with id " + keyId);
    }
    byte[] plaintext = kms.decrypt(keyId, wrapped, CONTEXT);
    try {
      if (plaintext.length != 32) {
        throw new VaultException("the key service returned a data key that is not 256 bits");
      }
      return new SecretKeySpec(plaintext, "AES");
    } finally {
      Arrays.fill(plaintext, (byte) 0);
    }
  }
}
