package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import java.util.UUID;

/**
 * Idempotency records (FR-01-03, ADR 0011 section 10): one per (institution, transaction id) for 24
 * hours, holding the request fingerprint and, once decided, the exact response bytes.
 */
public interface IdempotencyStore {

  /** What a claim found. */
  sealed interface Claim {}

  /** No record existed; the caller decides and must complete or release. */
  record Claimed() implements Claim {}

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
   * Stores the decided response for 24 hours.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @param response exact response bytes
   */
  void complete(UUID institutionId, UUID transactionId, byte[] response);

  /**
   * The cached response of a decided transaction, without claiming anything.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   * @return the response bytes, if the transaction was decided and is still held
   */
  java.util.Optional<byte[]> decided(UUID institutionId, UUID transactionId);

  /**
   * Releases an unfinished claim so a retry is decided as a first submission.
   *
   * @param institutionId institution
   * @param transactionId transaction id
   */
  void release(UUID institutionId, UUID transactionId);
}
