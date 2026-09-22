-- Staff identity and audit anchoring additions for M7 (ADR 0070, ADR 0071, D-24, D-32, FR-06-02, FR-07-02).
-- Additive only: one column, one index and five owner-rights read functions.

-- Optimistic locking for administrator edits (StaffUserUpdate.version, FR-06-02). The JPA entity's
-- @Version owns this column (ADR 0071): Hibernate increments it on every entity update and checks it
-- in the WHERE clause. Sign-in bookkeeping uses bulk statements that advance it only when the status
-- changes (a lock, an unlock), so an ordinary sign-in never makes an administrator's edit stale.
-- Numbered V70 in M7's range V70-V79 (it was V12 before merge; ADR 0071).
ALTER TABLE users ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);

GRANT SELECT (version) ON users TO fs_app_readonly;

-- Registration asks whether an employee ID is free before the institution is known (E.8). Employee
-- IDs are unique per institution; this answers across all of them, which errs towards "taken".
CREATE INDEX users_employee_id ON users (employee_id);

CREATE FUNCTION auth_employee_id_registered(p_employee_id text) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$ SELECT EXISTS (SELECT 1 FROM fraudshield.users WHERE employee_id = p_employee_id) $$;

-- Institution of an account by ID, for a signed single-use token (unlock, password reset) that
-- arrives before the institution is known. Returns nothing but the institution.
CREATE FUNCTION auth_find_user_institution(p_user_id uuid) RETURNS uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$ SELECT institution_id FROM fraudshield.users WHERE id = p_user_id $$;

-- Audit anchoring and verification (D-32). A chain spans every institution that wrote to the
-- partition, so these run with the owner's rights. They return positions and hashes only, never
-- event content, so neither the anchoring job (fs_app) nor compliance (fs_compliance_ro) gains
-- cross-tenant read access to audit events.
CREATE FUNCTION audit_chain_head(p_writer_partition smallint)
  RETURNS TABLE (last_seq bigint, last_hash bytea)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT h.last_seq, h.last_hash FROM fraudshield.audit_chain_heads h
    WHERE h.writer_partition = p_writer_partition
  $$;

CREATE FUNCTION audit_chain_hashes(p_writer_partition smallint, p_after_seq bigint, p_up_to_seq bigint)
  RETURNS TABLE (seq bigint, prev_hash bytea, row_hash bytea)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT e.seq, e.prev_hash, e.row_hash FROM fraudshield.audit_events e
    WHERE e.writer_partition = p_writer_partition AND e.seq > p_after_seq AND e.seq <= p_up_to_seq
    ORDER BY e.seq
  $$;

-- The last sequence number of a partition recorded before a time: verification requires every such
-- row to be covered by a signed anchor, so deleting the most recent anchors cannot hide an edited
-- tail of the chain.
CREATE FUNCTION audit_chain_last_seq_before(p_writer_partition smallint, p_before timestamptz)
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT max(e.seq) FROM fraudshield.audit_events e
    WHERE e.writer_partition = p_writer_partition AND e.recorded_at < p_before
  $$;

GRANT EXECUTE ON FUNCTION auth_employee_id_registered(text), auth_find_user_institution(uuid) TO fs_app;
GRANT EXECUTE ON FUNCTION audit_chain_last_seq_before(smallint, timestamptz) TO fs_compliance_ro;
GRANT EXECUTE ON FUNCTION audit_chain_head(smallint), audit_chain_hashes(smallint, bigint, bigint)
  TO fs_app, fs_compliance_ro;
