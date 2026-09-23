package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

/**
 * One durable record.
 *
 * @param offset position of the record's frame
 * @param nextOffset position just after it; commit this to mark the record consumed
 * @param payload the record's bytes
 */
public record SpoolRecord(long offset, long nextOffset, byte[] payload) {

  /** Copies the payload. */
  public SpoolRecord {
    payload = payload.clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SpoolRecord that && offset == that.offset;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(offset);
  }

  @Override
  public String toString() {
    return "SpoolRecord[" + offset + ".." + nextOffset + "]";
  }
}
