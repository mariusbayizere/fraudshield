-- Model registry, datasets and retraining (FR-02-08, FR-06-03, FR-06-04, D-11, D-50). Models serve
-- every institution, so these tables are not tenant-scoped; they hold no customer data.

CREATE TABLE model_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  model_version text NOT NULL UNIQUE CHECK (model_version ~ '^[A-Za-z0-9._-]{1,100}$'),
  algorithm text NOT NULL CHECK (char_length(algorithm) BETWEEN 1 AND 100),
  xgb_weight double precision NOT NULL CHECK (xgb_weight BETWEEN 0 AND 1),
  lgb_weight double precision NOT NULL CHECK (lgb_weight BETWEEN 0 AND 1),
  training_dataset_size bigint NOT NULL CHECK (training_dataset_size >= 0),
  training_date timestamptz NOT NULL,
  auc_roc double precision CHECK (auc_roc BETWEEN 0 AND 1),
  precision_at_1pct_fpr double precision CHECK (precision_at_1pct_fpr BETWEEN 0 AND 1),
  recall double precision CHECK (recall BETWEEN 0 AND 1),
  f1_score double precision CHECK (f1_score BETWEEN 0 AND 1),
  ece double precision CHECK (ece BETWEEN 0 AND 1),
  feature_importance jsonb CHECK (jsonb_typeof(feature_importance) = 'object'),
  mlflow_run_id text NOT NULL CHECK (char_length(mlflow_run_id) BETWEEN 1 AND 64),
  is_production boolean NOT NULL DEFAULT false,
  is_shadow boolean NOT NULL DEFAULT false,
  is_previous_production boolean NOT NULL DEFAULT false,
  deployed_at timestamptz,
  retired_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (abs(xgb_weight + lgb_weight - 1) < 1e-9),
  CHECK (NOT (is_production AND is_shadow)),
  CHECK (NOT (is_production AND is_previous_production)),
  CHECK (NOT is_production OR deployed_at IS NOT NULL),
  CHECK (retired_at IS NULL OR NOT (is_production OR is_shadow))
);
-- SRS: at most one production and one shadow model (plus one rollback target, FR-06-03).
CREATE UNIQUE INDEX model_versions_one_production ON model_versions ((true)) WHERE is_production;
CREATE UNIQUE INDEX model_versions_one_shadow ON model_versions ((true)) WHERE is_shadow;
CREATE UNIQUE INDEX model_versions_one_previous_production ON model_versions ((true)) WHERE is_previous_production;
CREATE INDEX model_versions_mlflow_run ON model_versions (mlflow_run_id);
CREATE TRIGGER model_versions_updated_at BEFORE UPDATE ON model_versions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE training_datasets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL CHECK (char_length(name) BETWEEN 3 AND 100),
  row_count bigint NOT NULL CHECK (row_count >= 0),
  sha256 text NOT NULL UNIQUE CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  object_key text NOT NULL CHECK (char_length(object_key) BETWEEN 1 AND 512),
  validation_state text NOT NULL DEFAULT 'VALIDATING' CHECK (validation_state IN ('VALIDATING', 'ACCEPTED', 'REJECTED')),
  rejection_reasons text[] NOT NULL DEFAULT '{}',
  uploaded_by uuid NOT NULL REFERENCES users (id),
  uploaded_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((validation_state = 'REJECTED') = (cardinality(rejection_reasons) > 0))
);
CREATE TRIGGER training_datasets_updated_at BEFORE UPDATE ON training_datasets FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE retraining_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dataset_id uuid NOT NULL REFERENCES training_datasets (id),
  requested_by uuid NOT NULL REFERENCES users (id),
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'TRAINING', 'EVALUATING', 'COMPLETED', 'FAILED')),
  progress_percent integer NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  candidate_model_version text REFERENCES model_versions (model_version),
  evaluation_passed boolean,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((state IN ('COMPLETED', 'FAILED')) = (finished_at IS NOT NULL)),
  CHECK (state <> 'COMPLETED' OR (progress_percent = 100 AND evaluation_passed IS NOT NULL))
);
-- One retraining job at a time (FR-06-04).
CREATE UNIQUE INDEX retraining_jobs_one_active ON retraining_jobs ((true)) WHERE state IN ('QUEUED', 'TRAINING', 'EVALUATING');
CREATE TRIGGER retraining_jobs_updated_at BEFORE UPDATE ON retraining_jobs FOR EACH ROW EXECUTE FUNCTION set_updated_at();
