package io.github.mariusbayizere.fraudshield.audit.anchor;

import io.github.mariusbayizere.fraudshield.audit.AnchorStatement;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;

/** Signs and verifies anchor statements with Ed25519 (D-32). */
public final class AnchorSigner {

  private static final String ALGORITHM = "Ed25519";

  private final String keyId;
  private final PrivateKey privateKey;

  /**
   * Creates a signer.
   *
   * @param keyId identifier stored with each anchor so verifiers pick the right public key
   * @param privateKey Ed25519 private key
   */
  public AnchorSigner(String keyId, PrivateKey privateKey) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
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
   * Signs a statement.
   *
   * @param statement the statement
   * @return a 64-byte Ed25519 signature
   */
  public byte[] sign(AnchorStatement statement) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(privateKey);
      signature.update(statement.signedBytes());
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not sign the audit anchor", e);
    }
  }

  /**
   * Verifies a signature.
   *
   * @param publicKey Ed25519 public key
   * @param statement the statement
   * @param signatureBytes the signature
   * @return whether the signature is valid
   */
  public static boolean verify(
      PublicKey publicKey, AnchorStatement statement, byte[] signatureBytes) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initVerify(publicKey);
      signature.update(statement.signedBytes());
      return signature.verify(signatureBytes);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
