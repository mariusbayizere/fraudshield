package io.github.mariusbayizere.fraudshield.audit.anchor;

import io.github.mariusbayizere.fraudshield.audit.MerkleRoot;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Streams one partition's row hashes in sequence order through {@code audit_chain_hashes} (V70),
 * checking that sequence numbers are contiguous and each {@code prev_hash} is the previous row's
 * hash, and folds them into a Merkle root. Row content is never read.
 */
final class ChainHashes {

  private static final int FETCH_SIZE = 10_000;

  private ChainHashes() {}

  /** The folded range. */
  record Range(long lastSeq, byte[] lastHash, byte[] merkleRoot, long rows) {}

  /**
   * Folds the rows in ({@code afterSeq}, {@code upToSeq}]. Must run inside a transaction so the
   * PostgreSQL driver streams with a cursor instead of loading every row.
   *
   * @throws AuditChainBrokenException if the range does not link up or is incomplete
   */
  static Range fold(
      JdbcTemplate jdbc, short partition, long afterSeq, byte[] afterHash, long upToSeq) {
    MerkleRoot merkle = new MerkleRoot();
    long[] expectedSeq = {afterSeq + 1};
    byte[][] previous = {afterHash};
    // A private template: the fetch size must not leak into the shared one. It joins the caller's
    // transaction because transactions are bound to the data source, not to the template.
    JdbcTemplate streaming = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
    streaming.setFetchSize(FETCH_SIZE);
    streaming.query(
        "SELECT seq, prev_hash, row_hash FROM audit_chain_hashes(?, ?, ?)",
        row -> {
          long seq = row.getLong(1);
          byte[] prevHash = row.getBytes(2);
          byte[] rowHash = row.getBytes(3);
          if (seq != expectedSeq[0]) {
            throw new AuditChainBrokenException(
                "partition " + partition + ": missing row, expected seq " + expectedSeq[0]);
          }
          if (!MessageDigest.isEqual(prevHash, previous[0])) {
            throw new AuditChainBrokenException(
                "partition " + partition + ": prev_hash of seq " + seq + " does not match");
          }
          merkle.add(rowHash);
          previous[0] = rowHash;
          expectedSeq[0] = seq + 1;
        },
        partition,
        afterSeq,
        upToSeq);
    if (expectedSeq[0] != upToSeq + 1) {
      throw new AuditChainBrokenException(
          "partition "
              + partition
              + ": rows end at seq "
              + (expectedSeq[0] - 1)
              + " but the range ends at "
              + upToSeq);
    }
    return new Range(upToSeq, previous[0], merkle.root(), merkle.size());
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
