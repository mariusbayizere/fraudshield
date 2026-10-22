-- The institution's day and an analyst decision's time to decision (ADR 0117 points 1, 3 and 9).

-- Point 1: every institution names its IANA time zone, and "today" is its local day. The zone is an
-- explicit input when an institution is created: there is no default and no insert-time fill from
-- the country, because a country does not name a zone (DR Congo spans Kinshasa and Lubumbashi).
ALTER TABLE institutions ADD COLUMN time_zone text
  CHECK (char_length(time_zone) BETWEEN 1 AND 64 AND time_zone ~ '^[A-Za-z][A-Za-z0-9_+/-]*$');

-- A zone the database does not know is refused, on INSERT and on UPDATE, so the migrations that are
-- the only way to change it are checked too. Created before the backfill, which it therefore checks.
CREATE FUNCTION institution_time_zone_known() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  -- A missing zone is left to the NOT NULL constraint, so it reports not_null_violation.
  IF NEW.time_zone IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names z WHERE z.name = NEW.time_zone) THEN
    RAISE EXCEPTION 'time zone % is not known to the database', NEW.time_zone
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER institutions_time_zone_known BEFORE INSERT OR UPDATE OF time_zone ON institutions
  FOR EACH ROW EXECUTE FUNCTION institution_time_zone_known();

-- Existing rows only: a fixed map from the country. A country outside the map fails the migration,
-- naming it; a wrong day boundary is worse than a refused migration. Every DR Congo row is listed in
-- a notice, because Kinshasa's zone is not every Congolese institution's: one outside it is
-- corrected by a later migration naming its zone.
DO $$
DECLARE
  unmapped text;
  congo text;
BEGIN
  SELECT string_agg(DISTINCT country::text, ', ' ORDER BY country::text) INTO unmapped
  FROM institutions
  WHERE country NOT IN ('RW', 'KE', 'UG', 'TZ', 'CD');
  IF unmapped IS NOT NULL THEN
    RAISE EXCEPTION 'no time zone is mapped for institutions in country %; name their zones first',
      unmapped USING ERRCODE = 'check_violation';
  END IF;
  UPDATE institutions SET time_zone = CASE country
    WHEN 'RW' THEN 'Africa/Kigali'
    WHEN 'KE' THEN 'Africa/Nairobi'
    WHEN 'UG' THEN 'Africa/Kampala'
    WHEN 'TZ' THEN 'Africa/Dar_es_Salaam'
    WHEN 'CD' THEN 'Africa/Kinshasa'
  END;
  SELECT string_agg(code || ' (' || id || ')', ', ' ORDER BY code) INTO congo
  FROM institutions
  WHERE country = 'CD';
  IF congo IS NOT NULL THEN
    RAISE NOTICE 'DR Congo institutions set to Africa/Kinshasa, to be checked (Lubumbashi is Africa/Lubumbashi): %',
      congo;
  END IF;
END
$$;

ALTER TABLE institutions ALTER COLUMN time_zone SET NOT NULL;

-- Point 3: time to decision, computed once at insert on DB time, for CONFIRM_FRAUD and
-- MARK_LEGITIMATE: the decision's created_at minus its entry's created_at, never negative. The
-- entry's created_at is the raise time on the deciding instance's clock (or the escalation time on
-- the database's), so an instance clock ahead of the database could make the difference negative;
-- the CHECK of V4 would then fail the decision itself, so a negative difference is stored as 0. The
-- trigger's name sorts after V81's alert_decisions_timing, and PostgreSQL fires triggers of one
-- event in name order, so NEW.created_at is already clock_timestamp(). Every other kind stores NULL.
CREATE FUNCTION alert_decision_duration() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  entry_created timestamptz;
BEGIN
  NEW.decision_duration_seconds := NULL;
  IF NEW.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') THEN
    SELECT e.created_at INTO entry_created FROM fraudshield.alert_queue_entries e
    WHERE e.id = NEW.alert_queue_entry_id AND e.institution_id = NEW.institution_id;
    IF entry_created IS NOT NULL THEN
      NEW.decision_duration_seconds :=
        greatest(0, round(extract(epoch FROM NEW.created_at - entry_created)))::integer;
    END IF;
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER alert_decisions_timing_then_duration BEFORE INSERT ON alert_decisions
  FOR EACH ROW EXECUTE FUNCTION alert_decision_duration();

-- Point 9: the institution-wide figures read reviews by time; the per-person windows read one
-- person's decisions by time; auto_blocked_today reads blocks by time (V5 has only the account's).
CREATE INDEX alert_decisions_reviews_by_time ON alert_decisions (institution_id, created_at)
  WHERE decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE');
CREATE INDEX alert_decisions_by_analyst_and_time
  ON alert_decisions (institution_id, analyst_id, created_at);
CREATE INDEX auto_block_events_by_time ON auto_block_events (institution_id, blocked_at);
