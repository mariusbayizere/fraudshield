-- Alert assignment history (M8B, ADR 0116 point 5).
--
-- assignAlert writes one row here per applied assignment, in the same transaction as the entry's
-- assigned_analyst_id, assigned_at and version (V4). The table serves two things the audit log
-- cannot: the operation's idempotency record (its key, unique per institution, and the request
-- fingerprint a replay is compared with), and a queryable history, which ADR 0117 reads for
-- average_seconds_from_assignment (the earliest assignment of an entry to its decider by another
-- user). Assignment is advisory: nothing here changes who may see or decide an entry.
--
-- Append-only and tenant-isolated, with composite foreign keys as V4 has them, so that a row can
-- never name an entry or a user of another institution even if the code's check is wrong.

CREATE TABLE alert_assignments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  alert_queue_entry_id uuid NOT NULL,
  previous_assignee_id uuid,
  assignee_id uuid,
  assigned_by uuid NOT NULL,
  assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  idempotency_key uuid NOT NULL,
  request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint) = 32),
  UNIQUE (institution_id, idempotency_key),
  FOREIGN KEY (alert_queue_entry_id, institution_id)
    REFERENCES alert_queue_entries (id, institution_id),
  FOREIGN KEY (previous_assignee_id, institution_id) REFERENCES users (id, institution_id),
  FOREIGN KEY (assignee_id, institution_id) REFERENCES users (id, institution_id),
  FOREIGN KEY (assigned_by, institution_id) REFERENCES users (id, institution_id),
  -- An applied assignment changes the assignee (ADR 0116 rule A6 answers no_change otherwise).
  CHECK (previous_assignee_id IS DISTINCT FROM assignee_id)
);
CREATE INDEX alert_assignments_entry
  ON alert_assignments (institution_id, alert_queue_entry_id, assigned_at);

-- DB time, whatever the writer sends.
CREATE FUNCTION alert_assignment_timing() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  NEW.assigned_at := clock_timestamp();
  RETURN NEW;
END
$$;
CREATE TRIGGER alert_assignments_timing BEFORE INSERT ON alert_assignments
  FOR EACH ROW EXECUTE FUNCTION alert_assignment_timing();

CALL make_append_only('alert_assignments');
CALL enable_tenant_isolation('alert_assignments');

-- Grants (V11's scheme): append-only, so INSERT and SELECT for the application only.
GRANT SELECT, INSERT ON alert_assignments TO fs_app;
GRANT SELECT ON alert_assignments TO fs_app_readonly, fs_compliance_ro;
