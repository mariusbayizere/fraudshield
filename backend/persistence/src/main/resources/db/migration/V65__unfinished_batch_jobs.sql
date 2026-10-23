-- Batch jobs left unfinished by a process that died (FR-01-06, Principal Review finding 12).
--
-- A job's items live in memory while it runs. If the instance dies, the job answered 202 stays
-- QUEUED or RUNNING for ever, with nothing to finish or fail it, and a client polling its status
-- waits on a job that no longer exists anywhere. Each instance marks such jobs FAILED when it
-- starts. Row-level security scopes fs_app to one institution per transaction, so the sweep first
-- asks which institutions have them: this function returns institution ids only, never a row's
-- content, in the pattern of institutions_with_overdue_holds (V62).

CREATE INDEX batch_jobs_unfinished ON batch_jobs (institution_id)
  WHERE state IN ('QUEUED', 'RUNNING');

CREATE FUNCTION institutions_with_unfinished_batch_jobs()
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT j.institution_id FROM fraudshield.batch_jobs j
    WHERE j.state IN ('QUEUED', 'RUNNING')
  $$;
REVOKE ALL ON FUNCTION institutions_with_unfinished_batch_jobs() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION institutions_with_unfinished_batch_jobs() TO fs_app;
