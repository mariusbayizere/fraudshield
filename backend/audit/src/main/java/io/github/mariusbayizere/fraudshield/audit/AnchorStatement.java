package io.github.mariusbayizere.fraudshield.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * What a daily audit anchor signs (D-32): the rows of one writer partition appended since the
 * previous anchor, identified by their sequence range, the last row's hash and the Merkle root of
 * their row hashes.
 *
 * <p>Anchors cover sequence ranges rather than calendar days, so a row whose transaction started
 * before midnight but committed after the anchoring job ran is covered by the next anchor instead
 * of by none (ADR 0027).
 *
 * @param anchorDate day the anchor was made for (UTC)
 * @param writerPartition chain partition
 * @param afterSeq last sequence number of the previous anchor, 0 for the first
 * @param lastSeq last sequence number covered
 * @param lastHash row hash of {@code lastSeq}
 * @param merkleRoot Merkle root of the row hashes in ({@code afterSeq}, {@code lastSeq}]
 */
public record AnchorStatement(
    LocalDate anchorDate,
    short writerPartition,
    long afterSeq,
    long lastSeq,
    byte[] lastHash,
    byte[] merkleRoot) {

  private static final String DOMAIN = "fraudshield-audit-anchor-v1";
  private static final int HASH_BYTES = 32;

  /** Validates the range and hash lengths. */
  public AnchorStatement {
    Objects.requireNonNull(anchorDate, "anchorDate");
    if (afterSeq < 0 || lastSeq <= afterSeq) {
      throw new IllegalArgumentException("an anchor covers at least one row after afterSeq");
    }
    if (lastHash.length != HASH_BYTES || merkleRoot.length != HASH_BYTES) {
      throw new IllegalArgumentException("hashes are 32 bytes");
    }
    lastHash = lastHash.clone();
    merkleRoot = merkleRoot.clone();
  }

  /**
   * The canonical bytes that are signed and verified; a domain prefix stops a signature over any
   * other message being accepted as an anchor.
   *
   * @return UTF-8 bytes
   */
  public byte[] signedBytes() {
    HexFormat hex = HexFormat.of();
    String text =
        String.join(
            "\n",
            DOMAIN,
            anchorDate.toString(),
            Short.toString(writerPartition),
            Long.toString(afterSeq),
            Long.toString(lastSeq),
            hex.formatHex(lastHash),
            hex.formatHex(merkleRoot));
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public byte[] lastHash() {
    return lastHash.clone();
  }

  @Override
  public byte[] merkleRoot() {
    return merkleRoot.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AnchorStatement that
        && Arrays.equals(signedBytes(), that.signedBytes());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(signedBytes());
  }

  @Override
  public String toString() {
    return "AnchorStatement[" + new String(signedBytes(), StandardCharsets.UTF_8) + "]";
  }
}
