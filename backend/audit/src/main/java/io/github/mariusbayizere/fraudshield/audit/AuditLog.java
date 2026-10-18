package io.github.mariusbayizere.fraudshield.audit;

/**
 * Appends audit events (port). An implementation writes inside the caller's transaction, so an
 * audited change and its audit record commit or roll back together (FR-06-06: all write operations
 * audited).
 */
public interface AuditLog {

  /**
   * Appends an event in the current transaction.
   *
   * @param event the event
   */
  void record(AuditEvent event);
}
