-- Versioned thresholds and circuit-breaker settings under asymmetric dual control (FR-02-06, FR-03-07,
-- FR-05-07, D-18, owner decision in ADR 0014 section 3). The rules are enforced by
-- common.config.DualControlWorkflow; these constraints are the database's independent backstop for
-- the ones that can be stated per row: no self-review, one open change per kind, fixed 24-hour
-- confirmation deadline, and proposals that never change once written.

CREATE TABLE config_changes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  kind text NOT NULL CHECK (kind IN ('CHANNEL_THRESHOLDS', 'MCC_CIRCUIT_BREAKER')),
  direction text NOT NULL CHECK (direction IN ('TIGHTENING', 'LOOSENING')),
  status text NOT NULL CHECK (status IN (
    'PENDING_APPROVAL', 'APPLIED_PENDING_CONFIRMATION', 'APPROVED', 'CONFIRMED', 'REJECTED', 'REVERTED',
    'SUPERSEDED', 'WITHDRAWN')),
  proposed_by uuid NOT NULL,
  proposed_at timestamptz NOT NULL DEFAULT now(),
  reason text NOT NULL CHECK (char_length(reason) BETWEEN 10 AND 500),
  base_version bigint NOT NULL CHECK (base_version >= 1),
  previous jsonb NOT NULL CHECK (jsonb_typeof(previous) = 'object'),
  proposed jsonb NOT NULL CHECK (jsonb_typeof(proposed) = 'object'),
  confirm_by timestamptz,
  reviewed_by uuid,
  reviewed_at timestamptz,
  review_reason text CHECK (char_length(review_reason) BETWEEN 1 AND 500),
  effective_at timestamptz,
  reverted_at timestamptz,
  revert_cause text CHECK (revert_cause IN ('REJECTED', 'NOT_CONFIRMED_IN_TIME')),
  FOREIGN KEY (proposed_by, institution_id) REFERENCES users (id, institution_id),
  FOREIGN KEY (reviewed_by, institution_id) REFERENCES users (id, institution_id),
  -- Nobody reviews their own change.
  CHECK (reviewed_by IS NULL OR reviewed_by <> proposed_by),
  CHECK (direction = 'LOOSENING' OR status IN ('APPLIED_PENDING_CONFIRMATION', 'CONFIRMED', 'REVERTED', 'SUPERSEDED')),
  CHECK (direction = 'TIGHTENING' OR status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'WITHDRAWN')),
  CHECK ((direction = 'TIGHTENING') = (confirm_by IS NOT NULL)),
  CHECK (confirm_by IS NULL OR confirm_by = proposed_at + interval '24 hours'),
  CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
  CHECK ((reviewed_by IS NULL) = (review_reason IS NULL) OR status IN ('APPROVED', 'CONFIRMED')),
  CHECK (status NOT IN ('PENDING_APPROVAL', 'APPLIED_PENDING_CONFIRMATION', 'SUPERSEDED', 'WITHDRAWN') OR reviewed_by IS NULL),
  CHECK (status NOT IN ('APPROVED', 'CONFIRMED', 'REJECTED') OR reviewed_by IS NOT NULL),
  CHECK (status <> 'REJECTED' OR char_length(review_reason) >= 10),
  CHECK ((status = 'REVERTED') = (reverted_at IS NOT NULL)),
  CHECK ((status = 'REVERTED') = (revert_cause IS NOT NULL)),
  CHECK (status <> 'REVERTED' OR (revert_cause = 'REJECTED') = (reviewed_by IS NOT NULL)),
  CHECK ((effective_at IS NOT NULL) = (direction = 'TIGHTENING' OR status = 'APPROVED'))
);
-- At most one open change per kind per institution.
CREATE UNIQUE INDEX config_changes_one_open_per_kind ON config_changes (institution_id, kind)
  WHERE status IN ('PENDING_APPROVAL', 'APPLIED_PENDING_CONFIRMATION');
CREATE UNIQUE INDEX config_changes_id_institution ON config_changes (id, institution_id);
CREATE INDEX config_changes_confirmation_deadline ON config_changes (confirm_by)
  WHERE status = 'APPLIED_PENDING_CONFIRMATION';

-- A proposal never changes; only its review outcome is recorded, once, from an open state.
CREATE FUNCTION guard_config_change_update() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'configuration changes are never deleted' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (NEW.id, NEW.institution_id, NEW.kind, NEW.direction, NEW.proposed_by, NEW.proposed_at, NEW.reason,
      NEW.base_version, NEW.previous, NEW.proposed, NEW.confirm_by)
     IS DISTINCT FROM
     (OLD.id, OLD.institution_id, OLD.kind, OLD.direction, OLD.proposed_by, OLD.proposed_at, OLD.reason,
      OLD.base_version, OLD.previous, OLD.proposed, OLD.confirm_by) THEN
    RAISE EXCEPTION 'the proposal of configuration change % cannot change', OLD.id USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF OLD.status NOT IN ('PENDING_APPROVAL', 'APPLIED_PENDING_CONFIRMATION') THEN
    RAISE EXCEPTION 'configuration change % is closed (%)', OLD.id, OLD.status USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER config_changes_guard BEFORE UPDATE OR DELETE ON config_changes
  FOR EACH ROW EXECUTE FUNCTION guard_config_change_update();
CALL enable_tenant_isolation('config_changes');

CREATE TABLE risk_threshold_versions (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  version bigint NOT NULL CHECK (version >= 1),
  config_change_id uuid,
  effective_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, version),
  FOREIGN KEY (config_change_id, institution_id) REFERENCES config_changes (id, institution_id),
  CHECK (version = 1 OR config_change_id IS NOT NULL)
);
CALL make_append_only('risk_threshold_versions');
CALL enable_tenant_isolation('risk_threshold_versions');

CREATE TABLE risk_thresholds (
  institution_id uuid NOT NULL,
  version bigint NOT NULL,
  channel text NOT NULL CHECK (channel IN ('MOBILE_MONEY', 'CARD', 'AGENT_BANKING', 'USSD', 'ONLINE', 'BANK_TRANSFER')),
  medium_threshold numeric(5, 4) NOT NULL CHECK (medium_threshold >= 0),
  high_threshold numeric(5, 4) NOT NULL CHECK (high_threshold <= 1),
  medium_timeout_policy text NOT NULL CHECK (medium_timeout_policy IN ('RELEASE_WITH_TIMEOUT_LABEL', 'DECLINE_AND_VERIFY')),
  PRIMARY KEY (institution_id, version, channel),
  FOREIGN KEY (institution_id, version) REFERENCES risk_threshold_versions (institution_id, version),
  CHECK (medium_threshold < high_threshold)
);
CALL make_append_only('risk_thresholds');
CALL enable_tenant_isolation('risk_thresholds');

CREATE TABLE mcc_circuit_breaker_settings_versions (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  version bigint NOT NULL CHECK (version >= 1),
  config_change_id uuid,
  fraud_rate_threshold numeric(5, 4) NOT NULL CHECK (fraud_rate_threshold > 0 AND fraud_rate_threshold < 1),
  window_minutes integer NOT NULL CHECK (window_minutes BETWEEN 1 AND 1440),
  minimum_transactions integer NOT NULL CHECK (minimum_transactions >= 1),
  clean_reset_minutes integer NOT NULL CHECK (clean_reset_minutes BETWEEN 1 AND 10080),
  effective_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, version),
  FOREIGN KEY (config_change_id, institution_id) REFERENCES config_changes (id, institution_id),
  CHECK (version = 1 OR config_change_id IS NOT NULL)
);
CALL make_append_only('mcc_circuit_breaker_settings_versions');
CALL enable_tenant_isolation('mcc_circuit_breaker_settings_versions');

CREATE TABLE mcc_circuit_breaker_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL,
  merchant_category_code char(4) NOT NULL CHECK (merchant_category_code ~ '^[0-9]{4}$'),
  event text NOT NULL CHECK (event IN ('OPENED', 'CLOSED')),
  window_fraud_rate numeric(5, 4) NOT NULL CHECK (window_fraud_rate BETWEEN 0 AND 1),
  window_transactions integer NOT NULL CHECK (window_transactions >= 0),
  settings_version bigint NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (institution_id, settings_version) REFERENCES mcc_circuit_breaker_settings_versions (institution_id, version)
);
CREATE INDEX mcc_circuit_breaker_events_latest ON mcc_circuit_breaker_events (institution_id, merchant_category_code, occurred_at DESC);
CALL make_append_only('mcc_circuit_breaker_events');
CALL enable_tenant_isolation('mcc_circuit_breaker_events');
