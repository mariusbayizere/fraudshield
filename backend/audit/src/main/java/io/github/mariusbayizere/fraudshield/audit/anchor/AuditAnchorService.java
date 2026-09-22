package io.github.mariusbayizere.fraudshield.audit.anchor;

import io.github.mariusbayizere.fraudshield.audit.AnchorStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes the daily signed audit anchors (D-32).
 *
 * <p>For each writer partition, the anchor covers every row appended since the partition's previous
 * anchor: the chain is re-linked row by row (sequence contiguity and {@code prev_hash}), the row
 * hashes are folded into a Merkle root, and the statement is signed with Ed25519 and stored in the
 * append-only {@code audit_anchors}. A broken chain is never signed. Running twice for the same day
 * is harmless: the second run finds the anchor and does nothing.
 */
public final class AuditAnchorService {

  private static final Logger LOG = LoggerFactory.getLogger(AuditAnchorService.class);
  static final short PARTITIONS = 64;
  static final byte[] GENESIS_HASH = new byte[32];

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final AnchorSigner signer;

  /**
   * Creates the service.
   *
   * @param jdbc JDBC template of a role with EXECUTE on the V12 chain functions and INSERT on
   *     audit_anchors
   * @param transactions transaction template of the same data source
   * @param signer anchor signer
   */
  public AuditAnchorService(
      JdbcTemplate jdbc, TransactionTemplate transactions, AnchorSigner signer) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.signer = Objects.requireNonNull(signer, "signer");
  }

  /**
   * Anchors every partition that has rows since its previous anchor.
   *
   * @param anchorDate the day being anchored (UTC)
   * @return the anchors made
   */
  public List<AnchorStatement> anchorAll(LocalDate anchorDate) {
    List<AnchorStatement> made = new ArrayList<>();
    for (short partition = 0; partition < PARTITIONS; partition++) {
      anchor(partition, anchorDate).ifPresent(made::add);
    }
    LOG.info("audit anchors made: {}", made.size());
    return made;
  }

  /**
   * Anchors one partition.
   *
   * @param partition writer partition
   * @param anchorDate the day being anchored (UTC)
   * @return the anchor made, or empty if the day is already anchored or nothing was appended
   */
  public Optional<AnchorStatement> anchor(short partition, LocalDate anchorDate) {
    return Objects.requireNonNull(
        transactions.execute(status -> anchorInTransaction(partition, anchorDate)));
  }

  private Optional<AnchorStatement> anchorInTransaction(short partition, LocalDate anchorDate) {
    Boolean done =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM audit_anchors WHERE writer_partition = ?"
                + " AND anchor_date >= ?)",
            Boolean.class,
            partition,
            anchorDate);
    if (Boolean.TRUE.equals(done)) {
      return Optional.empty();
    }
    List<Long> head =
        jdbc.query(
            "SELECT last_seq FROM audit_chain_head(?)", (row, i) -> row.getLong(1), partition);
    List<Previous> previous =
        jdbc.query(
            """
            SELECT last_seq, last_hash FROM audit_anchors WHERE writer_partition = ?
            ORDER BY anchor_date DESC LIMIT 1
            """,
            (row, i) -> new Previous(row.getLong(1), row.getBytes(2)),
            partition);
    long afterSeq = previous.isEmpty() ? 0 : previous.getFirst().lastSeq();
    byte[] afterHash = previous.isEmpty() ? GENESIS_HASH : previous.getFirst().lastHash();
    if (head.isEmpty() || head.getFirst() <= afterSeq) {
      return Optional.empty();
    }
    ChainHashes.Range range =
        ChainHashes.fold(jdbc, partition, afterSeq, afterHash, head.getFirst());
    AnchorStatement statement =
        new AnchorStatement(
            anchorDate, partition, afterSeq, range.lastSeq(), range.lastHash(), range.merkleRoot());
    jdbc.update(
        """
        INSERT INTO audit_anchors (anchor_date, writer_partition, last_seq, last_hash, merkle_root,
          signature, signing_key_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (anchor_date, writer_partition) DO NOTHING
        """,
        anchorDate,
        partition,
        statement.lastSeq(),
        statement.lastHash(),
        statement.merkleRoot(),
        signer.sign(statement),
        signer.keyId());
    return Optional.of(statement);
  }

  private record Previous(long lastSeq, byte[] lastHash) {}
}
