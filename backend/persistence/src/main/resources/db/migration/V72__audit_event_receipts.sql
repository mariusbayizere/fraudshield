-- Kafka audit events already appended to the chain (D-32, ADR 0012; M7 merge review, finding 2).
--
-- The decision engine publishes its audit events to fs.audit.events; the audit consumer appends each
-- to audit_events through the audit writer. Kafka delivers at least once, and audit_events, a
-- hypertable, cannot hold a unique key on the envelope's event_id (a unique index there must include
-- recorded_at). So the consumer inserts the event_id here in the same transaction as the audit row:
-- a redelivered event conflicts, inserts nothing and is skipped, and a rolled-back append leaves no
-- receipt behind.
CREATE TABLE audit_event_receipts (
  event_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL REFERENCES institutions (id),
  received_at timestamptz NOT NULL DEFAULT now()
);
CALL make_append_only('audit_event_receipts');
CALL enable_tenant_isolation('audit_event_receipts');

GRANT SELECT, INSERT ON audit_event_receipts TO fs_app;
GRANT SELECT ON audit_event_receipts TO fs_app_readonly;
