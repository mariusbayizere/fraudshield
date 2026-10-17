-- Reference FX rates for normalising amounts to RWF (E.2, D-51, ADR 0063).
--
-- Global reference data, not tenant-scoped: a rate is a market fact, identical for every
-- institution. Append-only: a correction is a new row for the same date with a later recorded_at,
-- and readers take the latest recorded row of the latest date on or before the transaction date.
-- Rates are loaded by operators (fs_migrator); the application only reads them. Development and
-- demo databases hold synthetic rates only (D-21).

CREATE TABLE fx_rates (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  currency char(3) NOT NULL CHECK (currency IN ('RWF', 'KES', 'TZS', 'UGX', 'CDF', 'BIF', 'SSP', 'SOS', 'USD', 'EUR')),
  rate_date date NOT NULL,
  rwf_per_unit numeric(20, 8) NOT NULL CHECK (rwf_per_unit > 0),
  source text NOT NULL CHECK (char_length(source) BETWEEN 3 AND 100),
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (currency, rate_date, recorded_at),
  CHECK (currency <> 'RWF' OR rwf_per_unit = 1)
);
CREATE INDEX fx_rates_lookup ON fx_rates (currency, rate_date DESC, recorded_at DESC);
CALL make_append_only('fx_rates');

GRANT SELECT ON fx_rates TO fs_app, fs_app_readonly;
