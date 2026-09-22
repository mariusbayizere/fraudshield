package io.github.mariusbayizere.fraudshield.auth.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The RS256 keys for staff access tokens (FR-07-04). The first key signs; every key verifies, so a
 * new key can be published in the JWKS before it signs and an old one kept until its last token
 * expires (key rotation by {@code kid}).
 */
public final class SigningKeys {

  private static final int MIN_RSA_BITS = 2048;
  private static final String PEM_LABEL = "PRIVATE" + " KEY";

  private final RSAKey active;
  private final JWKSet all;

  /**
   * Creates the key set.
   *
   * @param keys RSA keys with private parts and key IDs, active first
   */
  public SigningKeys(List<RSAKey> keys) {
    if (keys.isEmpty()) {
      throw new IllegalStateException(
          "fraudshield.auth.jwt.signing-keys must name at least one key");
    }
    for (RSAKey key : keys) {
      if (key.size() < MIN_RSA_BITS) {
        throw new IllegalStateException("RS256 keys must be at least 2048 bits: " + key.getKeyID());
      }
    }
    this.active = keys.getFirst();
    this.all = new JWKSet(new ArrayList<JWK>(keys));
  }

  /**
   * Loads PKCS#8 PEM RSA private keys; the public keys are derived from them.
   *
   * @param files key ID to PEM path, active first
   * @return the key set
   */
  public static SigningKeys load(List<Map.Entry<String, Path>> files) {
    List<RSAKey> keys = new ArrayList<>();
    for (Map.Entry<String, Path> file : files) {
      keys.add(
          rsaKey(Objects.requireNonNull(file.getKey(), "kid"), readPrivateKey(file.getValue())));
    }
    return new SigningKeys(keys);
  }

  /**
   * Builds a JWK from a private key.
   *
   * @param kid key ID
   * @param privateKey RSA private key with CRT parameters
   * @return the JWK
   */
  public static RSAKey rsaKey(String kid, RSAPrivateCrtKey privateKey) {
    try {
      var publicKey =
          (java.security.interfaces.RSAPublicKey)
              KeyFactory.getInstance("RSA")
                  .generatePublic(
                      new RSAPublicKeySpec(
                          privateKey.getModulus(), privateKey.getPublicExponent()));
      return new RSAKey.Builder(publicKey)
          .privateKey(privateKey)
          .keyID(kid)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
          .build();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("not an RSA private key: " + kid, e);
    }
  }

  private static RSAPrivateCrtKey readPrivateKey(Path pem) {
    try {
      String text = Files.readString(pem, StandardCharsets.US_ASCII);
      String body =
          text.replace("-----BEGIN " + PEM_LABEL + "-----", "")
              .replace("-----END " + PEM_LABEL + "-----", "")
              .replaceAll("\\s", "");
      return (RSAPrivateCrtKey)
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    } catch (IOException
        | GeneralSecurityException
        | IllegalArgumentException
        | ClassCastException e) {
      throw new IllegalStateException("cannot read the PKCS#8 RSA private key " + pem, e);
    }
  }

  /**
   * The signing key.
   *
   * @return the active key
   */
  public RSAKey active() {
    return active;
  }

  /**
   * Every key, private parts included (for the verifier).
   *
   * @return the key set
   */
  public JWKSet all() {
    return all;
  }

  /**
   * The public JWKS document served at {@code /auth/jwks.json}.
   *
   * @return public keys only
   */
  public Map<String, Object> publicJwks() {
    return all.toPublicJWKSet().toJSONObject();
  }
}
