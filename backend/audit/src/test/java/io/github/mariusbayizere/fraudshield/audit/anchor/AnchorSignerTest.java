package io.github.mariusbayizere.fraudshield.audit.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.AnchorStatement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("D-32")
class AnchorSignerTest {

  static KeyPair keyPair() throws Exception {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  static Path writePem(Path dir, String name, String label, byte[] der) throws Exception {
    Path file = dir.resolve(name);
    Files.writeString(
        file,
        "-----BEGIN "
            + label
            + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
            + "\n-----END "
            + label
            + "-----\n");
    return file;
  }

  private static AnchorStatement statement(long lastSeq) {
    return new AnchorStatement(
        LocalDate.parse("2026-09-21"), (short) 2, 10, lastSeq, new byte[32], new byte[32]);
  }

  @Test
  void signatureVerifiesOnlyForTheSignedStatementAndKey(@TempDir Path dir) throws Exception {
    KeyPair pair = keyPair();
    Path privatePem = writePem(dir, "anchor.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
    Path publicPem = writePem(dir, "anchor.pub.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
    AnchorSigner signer = new AnchorSigner("anchor-2026", AnchorKeys.privateKey(privatePem));
    byte[] signature = signer.sign(statement(20));
    assertThat(signature).hasSize(64);
    assertThat(AnchorSigner.verify(AnchorKeys.publicKey(publicPem), statement(20), signature))
        .isTrue();
    assertThat(AnchorSigner.verify(AnchorKeys.publicKey(publicPem), statement(21), signature))
        .isFalse();
    assertThat(AnchorSigner.verify(keyPair().getPublic(), statement(20), signature)).isFalse();
    assertThat(AnchorSigner.verify(pair.getPublic(), statement(20), new byte[3])).isFalse();
    assertThat(signer.keyId()).isEqualTo("anchor-2026");
  }

  @Test
  void keyFilesMustBePemOfTheRightKind(@TempDir Path dir) throws Exception {
    KeyPair pair = keyPair();
    Path publicPem = writePem(dir, "anchor.pub.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
    Path wrongKind = writePem(dir, "wrong.pem", "PRIVATE KEY", pair.getPublic().getEncoded());
    assertThatThrownBy(() -> AnchorKeys.privateKey(publicPem))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AnchorKeys.privateKey(wrongKind))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AnchorKeys.publicKey(dir.resolve("missing.pem")))
        .isInstanceOf(IllegalStateException.class);
    Path rsa =
        writePem(
            dir,
            "rsa.pem",
            "PUBLIC KEY",
            KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());
    assertThatThrownBy(() -> AnchorKeys.publicKey(rsa)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void statementsRejectEmptyRangesAndWrongHashLengths() {
    assertThatThrownBy(
            () -> new AnchorStatement(LocalDate.EPOCH, (short) 0, 5, 5, new byte[32], new byte[32]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AnchorStatement(LocalDate.EPOCH, (short) 0, 0, 5, new byte[31], new byte[32]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(statement(20)).isEqualTo(statement(20)).hasSameHashCodeAs(statement(20));
    assertThat(statement(20)).isNotEqualTo(statement(21));
    assertThat(statement(20).toString()).contains("fraudshield-audit-anchor-v1");
  }
}
