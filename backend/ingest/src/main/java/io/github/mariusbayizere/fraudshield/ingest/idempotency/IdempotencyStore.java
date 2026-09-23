package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import java.util.UUID;

/**
 * Idempotency records (FR-01-03, ADR 0011 section 10): one per (institution, transaction id) for 24
 * hours, holding the request fingerprint and, once decided, the exact response bytes.
 */
public interface IdempotencyStore {

  /** What a claim found. */
  sealed interface Claim {}

  /**
   * The caller owns the submission and decides it, then completes, releases or marks it uncertain.
   *
   * @param token the claim's owner token; only its owner can complete, release or mark it
   * @param verify whether the durable record must be consulted before deciding, because the fast
   *     store may have lost a decision (after a Redis outage or data loss, or an uncertain earlier
   *     attempt; ADR 0067)
   */
  record Claimed(String token, boolean verify) implements Claim {
    /** Requires the token. */
    public Claimed {
      java.util.Objects.requireNonNull(token, "token");
    }

    /**
     * A claim needing no verification.
     *
     * @return the claim
     */
    public static Claimed plain() {
      return new Claimed(java.util.UUID.randomUUID().toString(), false);
    }
  }

  /**
   * A decided submission with the same fingerprint.
   *
   * @param response the cached response bytes
   */
  record Replay(byte[] response) implements Claim {
    /** Copies the bytes. */
    public Replay {
      response = response.clone();
    }

    @Override
    public byte[] response() {
      return response.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Replay that && java.util.Arrays.equals(response, that.response);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(response);
    }

    @Override
    public String toString() {
      return "Replay[" + response.length + " bytes]";
    }
  }

  /** The transaction id was submitted with a different fingerprint. */
  record Conflict() implements Claim {}

  /** The same submission is being decided right now. */
  record InFlight() implements Claim {}

  /**
   * Claims a transaction id.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @param fingerprint SHA-256 of the canonical request
   * @return what was found
   */
  Claim claim(UUID institutionId, UUID transactionId, byte[] fingerprint);

  /**
   * Stores the decided response for 24 hours, with the fingerprint, whether or not the claim's
   * lease is still held. If the same submission was already completed (a duplicate re-claimed after
   * the lease expired and finished first), that response stands and is returned, so every client of
   * the submission gets the same bytes.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @param claim the caller's claim
   * @param fingerprint SHA-256 of the canonical request
   * @param response exact response bytes
   * @return the response that stands for this submission
   */
  byte[] complete(
      UUID institutionId, UUID transactionId, Claimed claim, byte[] fingerprint, byte[] response);

  /**
   * The cached response of a decided transaction, without claiming anything.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @return the response bytes, if the transaction was decided and is still held
   */
  java.util.Optional<byte[]> decided(UUID institutionId, UUID transactionId);

  /**
   * Releases a claim whose decision was certainly not recorded, so a retry is decided as a first
   * submission. Does nothing unless the caller still owns the claim.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @param claim the caller's claim
   */
  void release(UUID institutionId, UUID transactionId, Claimed claim);

  /**
   * Marks a claim whose decision may or may not have been recorded: the next claim of the same
   * submission must verify against the durable record before deciding.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @param claim the caller's claim
   */
  void uncertain(UUID institutionId, UUID transactionId, Claimed claim);

  /**
   * Requires verification against the durable record for an institution's new claims until a time,
   * because decisions were made that this store does not hold.
   *
   * @param institutionId institution
   * @param until end of the verification window
   */
  default void requireVerification(UUID institutionId, java.time.Instant until) {}
}
