-- Staff identity, sessions, credentials and API keys (FR-06, FR-07, D-19, D-23, D-24, D-26, D-27).

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  first_name text NOT NULL CHECK (char_length(first_name) BETWEEN 2 AND 100),
  last_name text NOT NULL CHECK (char_length(last_name) BETWEEN 2 AND 100),
  email text NOT NULL CHECK (char_length(email) <= 254 AND email = lower(email) AND email LIKE '%_@_%'),
  phone text CHECK (phone ~ '^\+[1-9][0-9]{6,14}$'),
  password_hash text CHECK (password_hash ~ '^\$2[aby]\$1[2-9]\$'),
  role text NOT NULL CHECK (role IN ('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER', 'ADMIN')),
  requested_role text CHECK (requested_role IN ('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER', 'ADMIN')),
  status text NOT NULL DEFAULT 'PENDING_APPROVAL'
    CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'LOCKED', 'DEACTIVATED')),
  locked_until timestamptz,
  failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
  token_version bigint NOT NULL DEFAULT 0 CHECK (token_version >= 0),
  email_verified boolean NOT NULL DEFAULT false,
  preferred_locale text NOT NULL DEFAULT 'en' CHECK (preferred_locale IN ('en', 'rw', 'fr', 'sw')),
  avatar_url text CHECK (char_length(avatar_url) <= 2048),
  oauth_provider text CHECK (oauth_provider IN ('GOOGLE')),
  oauth_id text CHECK (char_length(oauth_id) <= 255),
  employee_id text NOT NULL CHECK (employee_id ~ '^[A-Za-z0-9]{4,20}$'),
  department text NOT NULL CHECK (department IN ('FRAUD_OPERATIONS', 'RISK', 'COMPLIANCE', 'IT', 'OTHER')),
  last_login_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (institution_id, employee_id),
  UNIQUE (oauth_provider, oauth_id),
  CHECK ((oauth_provider IS NULL) = (oauth_id IS NULL)),
  CHECK (password_hash IS NOT NULL OR oauth_provider IS NOT NULL),
  CHECK (status <> 'LOCKED' OR locked_until IS NOT NULL)
);
-- Sign-in takes only an email, so staff emails are unique across institutions.
CREATE UNIQUE INDEX users_email_unique ON users (email);
CREATE UNIQUE INDEX users_id_institution ON users (id, institution_id);
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('users');

-- Sign-in happens before the institution is known. These lookups return only what
-- authentication needs and run with the owner's rights; the caller then sets the institution.
CREATE FUNCTION auth_find_user_by_email(p_email text)
  RETURNS TABLE (user_id uuid, institution_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$ SELECT id, institution_id FROM fraudshield.users WHERE email = lower(p_email) $$;

CREATE TABLE refresh_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL,
  user_id uuid NOT NULL,
  family_id uuid NOT NULL,
  token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  replaced_by uuid REFERENCES refresh_tokens (id),
  created_by_ip inet,
  oauth_provider text CHECK (oauth_provider IN ('GOOGLE')),
  user_agent text CHECK (char_length(user_agent) <= 1024),
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (user_id, institution_id) REFERENCES users (id, institution_id),
  CHECK (expires_at > created_at)
);
CREATE INDEX refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_expiry ON refresh_tokens (expires_at);
CALL enable_tenant_isolation('refresh_tokens');

CREATE TABLE password_reset_otps (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL,
  user_id uuid NOT NULL,
  code_hash bytea NOT NULL CHECK (octet_length(code_hash) = 32),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (user_id, institution_id) REFERENCES users (id, institution_id),
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '10 minutes')
);
CREATE INDEX password_reset_otps_user ON password_reset_otps (user_id) WHERE consumed_at IS NULL;
CALL enable_tenant_isolation('password_reset_otps');

CREATE TABLE email_verification_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL,
  user_id uuid NOT NULL,
  token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (user_id, institution_id) REFERENCES users (id, institution_id),
  CHECK (expires_at > created_at)
);
CALL enable_tenant_isolation('email_verification_tokens');

-- Single-use tokens arrive before the institution is known (links in email).
CREATE FUNCTION auth_find_email_verification(p_token_hash bytea)
  RETURNS TABLE (token_id uuid, institution_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$ SELECT id, institution_id FROM fraudshield.email_verification_tokens WHERE token_hash = p_token_hash $$;

CREATE TABLE office_ip_allowlist (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  cidr cidr NOT NULL,
  description text NOT NULL CHECK (char_length(description) BETWEEN 3 AND 200),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (institution_id, cidr),
  FOREIGN KEY (created_by, institution_id) REFERENCES users (id, institution_id)
);
CALL enable_tenant_isolation('office_ip_allowlist');

-- API keys: fsk_<env>_<key_id>_<secret>. Only an HMAC-SHA256 of the secret with a server-side pepper
-- is stored (D-19); the webhook signing secret is stored encrypted by the application's key
-- management, never in plaintext.
CREATE TABLE api_keys (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  key_id text NOT NULL UNIQUE CHECK (key_id ~ '^[a-z0-9]{12}$'),
  name text NOT NULL CHECK (name ~ '^[A-Za-z0-9 _.-]{3,80}$'),
  secret_hmac bytea NOT NULL CHECK (octet_length(secret_hmac) = 32),
  pepper_version smallint NOT NULL CHECK (pepper_version >= 1),
  last_four text NOT NULL CHECK (last_four ~ '^[A-Za-z0-9_-]{4}$'),
  scopes text[] NOT NULL CHECK (
    cardinality(scopes) >= 1 AND scopes <@ ARRAY['ingest:write', 'decisions:read', 'jobs:read']),
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'ROTATING', 'REVOKED')),
  webhook_url text CHECK (webhook_url ~ '^https://' AND char_length(webhook_url) <= 2048),
  webhook_secret_ciphertext bytea,
  webhook_secret_key_id text,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz,
  revoked_at timestamptz,
  last_used_at timestamptz,
  replaced_by uuid REFERENCES api_keys (id),
  FOREIGN KEY (created_by, institution_id) REFERENCES users (id, institution_id),
  CHECK ((webhook_url IS NULL) = (webhook_secret_ciphertext IS NULL)),
  CHECK ((webhook_secret_ciphertext IS NULL) = (webhook_secret_key_id IS NULL)),
  CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL)),
  CHECK (state <> 'ROTATING' OR expires_at IS NOT NULL)
);
CREATE UNIQUE INDEX api_keys_id_institution ON api_keys (id, institution_id);
CALL enable_tenant_isolation('api_keys');

-- API-key authentication happens before the institution is known.
CREATE FUNCTION auth_find_api_key(p_key_id text)
  RETURNS TABLE (api_key_id uuid, institution_id uuid, secret_hmac bytea, pepper_version smallint,
                 scopes text[], state text, expires_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT id, institution_id, secret_hmac, pepper_version, scopes, state, expires_at
    FROM fraudshield.api_keys WHERE key_id = p_key_id
  $$;

-- The refresh cookie arrives before the institution is known.
CREATE FUNCTION auth_find_refresh_token(p_token_hash bytea)
  RETURNS TABLE (refresh_token_id uuid, institution_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$ SELECT id, institution_id FROM fraudshield.refresh_tokens WHERE token_hash = p_token_hash $$;
