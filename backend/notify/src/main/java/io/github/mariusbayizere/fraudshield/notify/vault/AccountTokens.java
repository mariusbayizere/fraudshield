package io.github.mariusbayizere.fraudshield.notify.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

/**
 * The tokenisation map (D-20, ADR 0069 point 9): an institution's account number in, the token
 * every other part of FraudShield uses out, and back again.
 *
 * <p>The number is found by a blind index, HMAC-SHA-256 of the institution and the normalised
 * number under an index key held by the application, so the database can answer "which token" for a
 * number without holding the number in a form anyone can search. The number itself is stored as
 * AES-256-GCM ciphertext under a per-row data key from the {@link KeyProvider}, with the
 * institution and token as additional authenticated data. A token is permanent: the same number
 * gets the same token for ever, and concurrent first requests for one number agree on one token.
 *
 * <p>Explicit SQL, not JPA (ADR 0068 point 2). Account numbers are never logged: every message this
 * class produces names the institution at most.
 */
public final class AccountTokens {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int ATTEMPTS = 3;

  /** An account number after normalisation: IBAN-like, 4 to 34 letters and digits. */
  private static final Pattern NUMBER = Pattern.compile("[A-Z0-9]{4,34}");

  /** Separators people write inside account numbers, dropped before hashing. */
  private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-./]");

  private static final String FIND =
      "SELECT account_token FROM vault.account_tokens WHERE institution_id = ? AND lookup_hash = ?";
  private static final String INSERT =
      "INSERT INTO vault.account_tokens (institution_id, lookup_hash, account_token, key_id,"
          + " wrapped_key, nonce, ciphertext) VALUES (?, ?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT (institution_id, lookup_hash) DO NOTHING";
  private static final String READ =
      "SELECT key_id, wrapped_key, nonce, ciphertext FROM vault.account_tokens"
          + " WHERE institution_id = ? AND account_token = ?";

  private final DataSource dataSource;
  private final KeyProvider keys;
  private final SecretKey indexKey;

  /**
   * Creates the map.
   *
   * @param dataSource connections to the vault instance as {@code fs_vault}
   * @param keys the key provider for the stored numbers
   * @param indexKey 32 bytes for the blind index; it must never change, or every number receives a
   *     new token
   */
  public AccountTokens(DataSource dataSource, KeyProvider keys, byte[] indexKey) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.keys = Objects.requireNonNull(keys, "keys");
    if (indexKey == null || indexKey.length != 32) {
      throw new IllegalArgumentException("the vault's index key must be 32 bytes");
    }
    this.indexKey = new SecretKeySpec(indexKey, "HmacSHA256");
  }

  /**
   * The token for an account number, created on first use.
   *
   * @param institutionId the institution whose number it is
   * @param accountNumber as the institution writes it; spaces, hyphens, dots and slashes are
   *     ignored, and letters are compared without case
   * @return the account's token, the same for every call with the same number
   * @throws IllegalArgumentException when the number is not an account number
   * @throws VaultException when the vault cannot answer
   */
  public String tokenise(UUID institutionId, String accountNumber) {
    Objects.requireNonNull(institutionId, "institutionId");
    String number = normalise(accountNumber);
    byte[] lookup = lookupHash(institutionId, number);
    try (Connection c = dataSource.getConnection()) {
      Optional<String> existing = find(c, institutionId, lookup);
      if (existing.isPresent()) {
        return existing.get();
      }
      for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
        String token = newToken();
        Sealed sealed = seal(institutionId, token, number);
        try (PreparedStatement s = c.prepareStatement(INSERT)) {
          s.setObject(1, institutionId);
          s.setBytes(2, lookup);
          s.setString(3, token);
          s.setString(4, sealed.keyId());
          s.setBytes(5, sealed.wrapped());
          s.setBytes(6, sealed.nonce());
          s.setBytes(7, sealed.ciphertext());
          s.executeUpdate();
        } catch (SQLException e) {
          if ("23505".equals(e.getSQLState())) {
            // The random token collided with another number's: draw again.
            continue;
          }
          throw e;
        }
        // Either this insert won, or a concurrent first request for the same number did; both
        // callers read back the one row that exists.
        return find(c, institutionId, lookup)
            .orElseThrow(() -> new VaultException("a token was written and cannot be read"));
      }
      throw new VaultException("could not draw an unused token");
    } catch (SQLException e) {
      throw new VaultException("the vault is unavailable", e);
    }
  }

  /**
   * The account number behind a token.
   *
   * @param institutionId the institution
   * @param accountToken the token
   * @return the normalised number, or empty when this institution has no such token
   * @throws VaultException when the row does not verify or the vault cannot answer
   */
  public Optional<String> detokenise(UUID institutionId, String accountToken) {
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
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(institutionId, accountToken));
      byte[] plaintext = cipher.doFinal(ciphertext);
      String number = new String(plaintext, StandardCharsets.US_ASCII);
      Arrays.fill(plaintext, (byte) 0);
      return Optional.of(number);
    } catch (GeneralSecurityException e) {
      throw VaultException.permanent("a vault row does not verify", e);
    }
  }

  private static Optional<String> find(Connection c, UUID institutionId, byte[] lookup)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(FIND)) {
      s.setObject(1, institutionId);
      s.setBytes(2, lookup);
      try (ResultSet row = s.executeQuery()) {
        return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
      }
    }
  }

  private record Sealed(String keyId, byte[] wrapped, byte[] nonce, byte[] ciphertext) {}

  private Sealed seal(UUID institutionId, String token, String number) {
    KeyProvider.DataKey dataKey = keys.newDataKey();
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, dataKey.key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(institutionId, token));
      byte[] ciphertext = cipher.doFinal(number.getBytes(StandardCharsets.US_ASCII));
      return new Sealed(dataKey.keyId(), dataKey.wrapped(), nonce, ciphertext);
    } catch (GeneralSecurityException e) {
      throw new VaultException("could not encrypt an account number", e);
    }
  }

  private byte[] lookupHash(UUID institutionId, String number) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(indexKey);
      mac.update((institutionId + "|").getBytes(StandardCharsets.US_ASCII));
      return mac.doFinal(number.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new VaultException("could not compute a lookup hash", e);
    }
  }

  static String normalise(String accountNumber) {
    if (accountNumber == null) {
      throw new IllegalArgumentException("an account number is required");
    }
    String number = SEPARATORS.matcher(accountNumber).replaceAll("").toUpperCase(Locale.ROOT);
    if (!NUMBER.matcher(number).matches()) {
      // The value is not repeated: it may be an account number with one wrong character.
      throw new IllegalArgumentException("not an account number (4 to 34 letters and digits)");
    }
    return number;
  }

  private static String newToken() {
    char[] token = new char[24];
    for (int i = 0; i < token.length; i++) {
      token[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return "tok_" + new String(token);
  }

  private static byte[] aad(UUID institutionId, String token) {
    return ("account-token|" + institutionId + "|" + token).getBytes(StandardCharsets.UTF_8);
  }
}
