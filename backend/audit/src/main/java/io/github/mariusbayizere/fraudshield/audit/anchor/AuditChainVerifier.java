package io.github.mariusbayizere.fraudshield.audit.anchor;

import io.github.mariusbayizere.fraudshield.audit.AnchorStatement;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the audit log (D-32, {@code fraudshield audit verify --from --to}).
 *
 * <p>For each writer partition:
 *
 * <ol>
 *   <li>every anchor dated up to {@code to} must carry a valid Ed25519 signature from a known key
 *       over its statement;
 *   <li>every anchor dated within [{@code from}, {@code to}] is recomputed from the stored row
 *       hashes: the range must link up and give the signed Merkle root and last hash;
 *   <li>the chain itself is re-hashed from the last anchor before {@code from} (or from the first
 *       row) to the head by {@code verify_audit_chain}, which detects edited, deleted and
 *       re-ordered rows and a head that is ahead of the stored rows.
 * </ol>
 *
 * <p>Runs as {@code fs_compliance_ro}; it sees positions and hashes, never event content.
 */
public final class AuditChainVerifier {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Map<String, PublicKey> publicKeys;
  private final Clock clock;

  /** The anchor of day D is made at 00:10 on D+1; a day is due once D+1 01:00 has passed. */
  private static final Duration ANCHOR_GRACE = Duration.ofHours(25);

  /**
   * Creates the verifier.
   *
   * @param jdbc JDBC template of the compliance role
   * @param transactions transaction template of the same data source
   * @param publicKeys anchor public keys by key ID
   * @param clock clock, to know which days must already be anchored
   */
  public AuditChainVerifier(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      Map<String, PublicKey> publicKeys,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.publicKeys = Map.copyOf(publicKeys);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * A problem found by verification.
   *
   * @param partition writer partition
   * @param seq first affected sequence number, or null if not positional
   * @param description what is wrong
   */
  public record Problem(short partition, Long seq, String description) {}

  /**
   * Result of a verification.
   *
   * @param rowsChecked rows re-hashed by the chain check
   * @param anchorsChecked anchors whose signature was verified
   * @param anchorsRecomputed anchors whose Merkle root was recomputed
   * @param problems problems found; empty means the log verified
   */
  public record Report(
      long rowsChecked, int anchorsChecked, int anchorsRecomputed, List<Problem> problems) {

    /** Copies the problems. */
    public Report {
      problems = List.copyOf(problems);
    }

    /**
     * Whether nothing was wrong.
     *
     * @return true if verified
     */
    public boolean verified() {
      return problems.isEmpty();
    }
  }

  private record StoredAnchor(
      LocalDate date,
      long lastSeq,
      byte[] lastHash,
      byte[] merkleRoot,
      byte[] signature,
      String keyId) {}

  /**
   * Verifies every partition.
   *
   * @param from first anchor date whose Merkle root is recomputed
   * @param to last anchor date considered
   * @return the report
   */
  public Report verify(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("--to is before --from");
    }
    return Objects.requireNonNull(transactions.execute(status -> verifyAll(from, to)));
  }

  private Report verifyAll(LocalDate from, LocalDate to) {
    List<Problem> problems = new ArrayList<>();
    long rows = 0;
    int checked = 0;
    int recomputed = 0;
    for (short partition = 0; partition < AuditAnchorService.PARTITIONS; partition++) {
      List<StoredAnchor> anchors = anchors(partition, to);
      long afterSeq = 0;
      byte[] afterHash = AuditAnchorService.GENESIS_HASH;
      long chainStartSeq = 0;
      byte[] chainStartHash = AuditAnchorService.GENESIS_HASH;
      for (StoredAnchor anchor : anchors) {
        checked++;
        if (anchor.lastSeq() <= afterSeq) {
          problems.add(
              new Problem(
                  partition,
                  anchor.lastSeq(),
                  "anchor " + anchor.date() + " does not advance the chain"));
          continue;
        }
        AnchorStatement statement =
            new AnchorStatement(
                anchor.date(),
                partition,
                afterSeq,
                anchor.lastSeq(),
                anchor.lastHash(),
                anchor.merkleRoot());
        PublicKey key = publicKeys.get(anchor.keyId());
        if (key == null) {
          problems.add(
              new Problem(
                  partition,
                  anchor.lastSeq(),
                  "anchor " + anchor.date() + " is signed by unknown key " + anchor.keyId()));
        } else if (!AnchorSigner.verify(key, statement, anchor.signature())) {
          problems.add(
              new Problem(
                  partition,
                  anchor.lastSeq(),
                  "anchor " + anchor.date() + " has an invalid signature"));
        }
        if (!anchor.date().isBefore(from)) {
          recomputed++;
          recompute(partition, afterSeq, afterHash, anchor).ifPresent(problems::add);
        } else {
          chainStartSeq = anchor.lastSeq();
          chainStartHash = anchor.lastHash();
        }
        afterSeq = anchor.lastSeq();
        afterHash = anchor.lastHash();
      }
      coverage(partition, anchors, to).ifPresent(problems::add);
      ChainCheck chain = chain(partition, chainStartSeq, chainStartHash);
      rows += chain.checkedRows();
      if (chain.problem() != null) {
        problems.add(new Problem(partition, chain.firstBadSeq(), chain.problem()));
      }
    }
    return new Report(rows, checked, recomputed, List.copyOf(problems));
  }

  /**
   * Every row recorded before the end of the last day that must already be anchored has to be
   * covered by a signed anchor dated no later than that day. Without this, deleting the most recent
   * anchors and rewriting the tail of the chain would verify (review finding 3).
   */
  private Optional<Problem> coverage(short partition, List<StoredAnchor> anchors, LocalDate to) {
    LocalDate due = LocalDate.ofInstant(clock.instant().minus(ANCHOR_GRACE), ZoneOffset.UTC);
    LocalDate lastDue = to.isBefore(due) ? to : due;
    Instant endOfDay = lastDue.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Long required =
        jdbc.queryForObject(
            "SELECT audit_chain_last_seq_before(?, ?)",
            Long.class,
            partition,
            java.sql.Timestamp.from(endOfDay));
    long anchored =
        anchors.stream()
            .filter(a -> !a.date().isAfter(lastDue))
            .mapToLong(StoredAnchor::lastSeq)
            .max()
            .orElse(0);
    if (required != null && required > anchored) {
      return Optional.of(
          new Problem(
              partition,
              anchored + 1,
              "rows up to seq "
                  + required
                  + " recorded before "
                  + endOfDay
                  + " are not covered by any anchor dated up to "
                  + lastDue));
    }
    return Optional.empty();
  }

  private Optional<Problem> recompute(
      short partition, long afterSeq, byte[] afterHash, StoredAnchor anchor) {
    ChainHashes.Range range;
    try {
      range = ChainHashes.fold(jdbc, partition, afterSeq, afterHash, anchor.lastSeq());
    } catch (AuditChainBrokenException e) {
      return Optional.of(
          new Problem(
              partition, anchor.lastSeq(), "anchor " + anchor.date() + ": " + e.getMessage()));
    }
    if (!MessageDigest.isEqual(range.merkleRoot(), anchor.merkleRoot())) {
      return Optional.of(
          new Problem(
              partition,
              anchor.lastSeq(),
              "anchor "
                  + anchor.date()
                  + ": Merkle root of the stored rows differs from the signed root"));
    }
    if (!MessageDigest.isEqual(range.lastHash(), anchor.lastHash())) {
      return Optional.of(
          new Problem(
              partition,
              anchor.lastSeq(),
              "anchor " + anchor.date() + ": last row hash differs from the signed hash"));
    }
    return Optional.empty();
  }

  private List<StoredAnchor> anchors(short partition, LocalDate to) {
    return jdbc.query(
        """
        SELECT anchor_date, last_seq, last_hash, merkle_root, signature, signing_key_id
        FROM audit_anchors WHERE writer_partition = ? AND anchor_date <= ? ORDER BY anchor_date
        """,
        (row, i) ->
            new StoredAnchor(
                row.getObject(1, LocalDate.class),
                row.getLong(2),
                row.getBytes(3),
                row.getBytes(4),
                row.getBytes(5),
                row.getString(6)),
        partition,
        to);
  }

  private record ChainCheck(long checkedRows, Long firstBadSeq, String problem) {}

  private ChainCheck chain(short partition, long afterSeq, byte[] afterHash) {
    return jdbc.queryForObject(
        "SELECT checked_rows, first_bad_seq, problem FROM verify_audit_chain(?, ?, ?)",
        (row, i) -> new ChainCheck(row.getLong(1), (Long) row.getObject(2), row.getString(3)),
        partition,
        afterSeq,
        afterHash);
  }
}
