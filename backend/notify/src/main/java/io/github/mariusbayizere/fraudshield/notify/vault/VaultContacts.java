package io.github.mariusbayizere.fraudshield.notify.vault;

import io.github.mariusbayizere.fraudshield.notify.sms.ContactDirectory;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The PII vault's contact directory (D-20, ADR 0012 section 4, ADR 0068): the only place in
 * FraudShield where a customer's phone number and account number exist.
 *
 * <p>It is a separate PostgreSQL instance with its own role, reached with explicit SQL: a vault is
 * one of the cases ADR 0071 keeps out of JPA, because nothing here may be written by change
 * tracking and nothing may be cached in a persistence context. One row per account token holds an
 * AES-256-GCM ciphertext under a per-row data key, itself wrapped by the {@link KeyProvider}'s
 * master key. The institution and account token are the additional authenticated data, so a
 * ciphertext moved to another row fails to decrypt rather than answering for the wrong customer.
 *
 * <p>A resolved contact is returned to one caller for one send and never logged, stored or put on a
 * topic; {@link ContactDirectory.Contact} redacts itself in {@code toString}.
 */
public final class VaultContacts implements ContactDirectory {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private static final String READ =
      "SELECT key_id, wrapped_key, nonce, ciphertext FROM vault.contacts"
          + " WHERE institution_id = ? AND account_token = ?";
  private static final String WRITE =
      "INSERT INTO vault.contacts (institution_id, account_token, key_id, wrapped_key, nonce,"
          + " ciphertext) VALUES (?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT (institution_id, account_token) DO UPDATE SET key_id = EXCLUDED.key_id,"
          + " wrapped_key = EXCLUDED.wrapped_key, nonce = EXCLUDED.nonce,"
          + " ciphertext = EXCLUDED.ciphertext";

  private final DataSource dataSource;
  private final KeyProvider keys;

  /**
   * Creates the directory.
   *
   * @param dataSource connections to the vault instance as {@code fs_vault}
   * @param keys the key provider
   */
  public VaultContacts(DataSource dataSource, KeyProvider keys) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.keys = Objects.requireNonNull(keys, "keys");
  }

  /**
   * Stores or replaces one customer's contact details.
   *
   * <p>M6 has no enrolment endpoint: the institution's onboarding fills the vault (M7's staff and
   * batch APIs), and this method is what it calls.
   *
   * @param institutionId institution
   * @param accountToken the account's token, the only identifier the rest of the system holds
   * @param contact the details to protect
   */
  public void store(UUID institutionId, String accountToken, Contact contact) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("phone_e164", contact.phoneE164());
    payload.put("locale", contact.locale());
    payload.put("masked_account", contact.maskedAccount());
    KeyProvider.DataKey dataKey = keys.newDataKey();
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    byte[] ciphertext;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, dataKey.key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(institutionId, accountToken));
      ciphertext =
          cipher.doFinal(JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new VaultException("could not encrypt a contact", e);
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement(WRITE)) {
      s.setObject(1, institutionId);
      s.setString(2, accountToken);
      s.setString(3, dataKey.keyId());
      s.setBytes(4, dataKey.wrapped());
      s.setBytes(5, nonce);
      s.setBytes(6, ciphertext);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new VaultException("the vault refused a write", e);
    }
  }

  @Override
  public Optional<Contact> find(UUID institutionId, String accountToken) {
    String keyId;
    byte[] wrapped;
    byte[] nonce;
    byte[] ciphertext;
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement(READ)) {
      s.setObject(1, institutionId);
      s.setString(2, accountToken);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        keyId = row.getString(1);
        wrapped = row.getBytes(2);
        nonce = row.getBytes(3);
        ciphertext = row.getBytes(4);
      }
    } catch (SQLException e) {
      throw new VaultException("the vault is unavailable", e);
    }
    SecretKey dataKey = keys.unwrap(keyId, wrapped);
    byte[] plaintext;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(institutionId, accountToken));
      plaintext = cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException e) {
      // A row that does not decrypt is a tampered or misplaced row, never a missing contact.
      throw VaultException.permanent("a vault row does not verify", e);
    }
    var payload = JSON.readTree(new String(plaintext, StandardCharsets.UTF_8));
    java.util.Arrays.fill(plaintext, (byte) 0);
    return Optional.of(
        new Contact(
            payload.get("phone_e164").asString(),
            payload.get("locale").asString(),
            payload.get("masked_account").asString()));
  }

  private static byte[] aad(UUID institutionId, String accountToken) {
    return (institutionId + "|" + accountToken).getBytes(StandardCharsets.UTF_8);
  }
}
