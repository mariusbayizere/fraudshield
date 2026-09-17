-- Transactional outbox for Kafka publication after the decision (D-13, D-15, C.2).
--
-- Not tenant-scoped by row-level security: the relay publishes for every institution. Payloads are
-- the Kafka event envelopes, which carry tokens and no personal data (ADR 0012). Only fs_app may
-- read or write it (ADR 0017).

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  topic text NOT NULL CHECK (topic ~ '^fs\.[a-z]+(\.[a-z]+)*(\.v[0-9]+)?$'),
  event_key text NOT NULL CHECK (char_length(event_key) BETWEEN 1 AND 128),
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);
CREATE INDEX outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
