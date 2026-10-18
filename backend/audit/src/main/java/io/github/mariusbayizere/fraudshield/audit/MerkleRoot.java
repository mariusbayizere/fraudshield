package io.github.mariusbayizere.fraudshield.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Streaming Merkle tree hash over audit row hashes, as defined for Certificate Transparency (RFC
 * 6962 section 2.1): leaf = SHA-256(0x00 || row_hash), node = SHA-256(0x01 || left || right), and a
 * tree of n leaves splits at the largest power of two below n.
 *
 * <p>Leaves are added one at a time and only the O(log n) roots of complete subtrees are kept, so a
 * day of audit rows is hashed without holding it in memory (D-32 daily anchor).
 */
public final class MerkleRoot {

  private static final byte LEAF_PREFIX = 0x00;
  private static final byte NODE_PREFIX = 0x01;

  /** Complete subtrees, largest (leftmost) first; each entry is {height, hash}. */
  private final Deque<Subtree> subtrees = new ArrayDeque<>();

  private long leaves;

  private record Subtree(int height, byte[] hash) {}

  /**
   * Adds the next leaf.
   *
   * @param rowHash the 32-byte row hash
   */
  public void add(byte[] rowHash) {
    Subtree current = new Subtree(0, leafHash(rowHash));
    while (!subtrees.isEmpty() && subtrees.peekLast().height() == current.height()) {
      Subtree left = subtrees.removeLast();
      current = new Subtree(current.height() + 1, nodeHash(left.hash(), current.hash()));
    }
    subtrees.addLast(current);
    leaves++;
  }

  /**
   * Number of leaves added.
   *
   * @return the count
   */
  public long size() {
    return leaves;
  }

  /**
   * The root over every leaf added so far; SHA-256 of the empty string for no leaves (RFC 6962).
   *
   * @return the 32-byte root
   */
  public byte[] root() {
    if (subtrees.isEmpty()) {
      return sha256().digest();
    }
    Iterator<Subtree> fromRight = subtrees.descendingIterator();
    byte[] accumulator = fromRight.next().hash();
    while (fromRight.hasNext()) {
      accumulator = nodeHash(fromRight.next().hash(), accumulator);
    }
    return accumulator;
  }

  private static byte[] leafHash(byte[] rowHash) {
    MessageDigest digest = sha256();
    digest.update(LEAF_PREFIX);
    digest.update(rowHash);
    return digest.digest();
  }

  private static byte[] nodeHash(byte[] left, byte[] right) {
    MessageDigest digest = sha256();
    digest.update(NODE_PREFIX);
    digest.update(left);
    digest.update(right);
    return digest.digest();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
