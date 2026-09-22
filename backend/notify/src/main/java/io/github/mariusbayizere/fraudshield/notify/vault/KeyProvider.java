package io.github.mariusbayizere.fraudshield.notify.vault;

import javax.crypto.SecretKey;

/**
 * Where the vault's key material comes from (D-20): envelope encryption, so that the master key
 * never leaves the provider and a row's data key is stored beside the row, wrapped.
 *
 * <p>M6 has one implementation, {@link PassphraseKeyProvider}, which derives the master key from
 * configuration. A KMS-backed provider is M9's; nothing outside this interface changes when it
 * arrives, because the wrapped key carries the id of the master key that wrapped it.
 */
public interface KeyProvider {

  /**
   * A fresh data key, with the wrapping that lets it be recovered.
   *
   * @param keyId id of the master key that wrapped it
   * @param key the data key itself, for this write only
   * @param wrapped the data key as stored beside the row
   */
  record DataKey(String keyId, SecretKey key, byte[] wrapped) {}

  /**
   * Makes a data key for one row.
   *
   * @return the key and its wrapping
   */
  DataKey newDataKey();

  /**
   * Recovers a data key stored with a row.
   *
   * @param keyId the master key that wrapped it
   * @param wrapped the stored wrapping
   * @return the data key
   * @throws VaultException when the master key is unknown or the wrapping does not verify
   */
  SecretKey unwrap(String keyId, byte[] wrapped);
}
