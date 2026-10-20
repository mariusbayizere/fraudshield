# Independent review: PB-154 fix (fallback account country from currency)

- Commit reviewed: `c67b67a` "fix(ml): derive the fallback's account country from the currency"
  (and the backlog note in `9b412c0`).
- Reviewer: independent; did not write the change. Read-only review: no pytest, mypy, ruff or
  Docker was run (memory constraints). Every statement below comes from reading the code at
  `9b412c0`.

## Verdict: APPROVE_WITH_CHANGES

The production change is correct, and the unit test pins it. Two construction sites in the Docker
test module were missed, though. They now raise `TypeError` before reaching the database, so the
Docker job will fail. Fix finding 1 before merge. The other findings are minor.

## Findings

### 1. MAJOR: two `PostgresFallback` construction sites were not updated

Evidence: `ml/tests/featurestore/test_db_fallback.py:511`
`PostgresFallback(db.scorer, dict(REFERENCE.minor_units), statement_timeout_ms=200)` and
`ml/tests/featurestore/test_db_fallback.py:532`
`PostgresFallback(db.scorer, dict(REFERENCE.minor_units))`.

`country_of_currency` is now a required keyword-only parameter with no default
(`ml/src/fraudshield_ml/featurestore/postgres.py:76`). Both tests
(`test_the_database_session_is_bounded_and_read_only` and
`test_at_the_production_bounds_a_timed_out_statement_does_not_refuse_the_next_read`, both
`requires_docker`) therefore raise `TypeError: missing 1 required keyword-only argument`. They
are skipped locally without Docker, so a local unit run stays green. The CI Docker job will fail.

mypy --strict would also report both call sites as `Missing named argument "country_of_currency"`
if it checks `ml/tests`.

The commit updated only the site at `:274`. A grep for `PostgresFallback(` over `ml/src` and
`ml/tests` finds 17 sites: `server.py:257` (updated), `test_db_fallback.py:274/511/532` (one of
three updated), and all 13 in `test_postgres_fallback.py` (updated).

Fix: pass `country_of_currency=REFERENCE.country_of_currency` at both sites.

### 2. MINOR: the defect was latent, as the backlog correction says, and nothing guards that

Evidence:
- The only consumer of `Durable.transactions` is `FeatureStore._restore_windows`
  (`ml/src/fraudshield_ml/featurestore/store.py:606-615`).
- That method turns each history row into `_member(row)` (`store.py:770-780`), which takes the
  id, amount, counterparty id and country, device and location. It never takes
  `account_country`.
- The features that read `account_country` (temporal `features/online.py:639`, corridor
  `online.py:1093`, `features/batch.py:151,532`, `serving/features.py:240,351`) all read the
  *scored* transaction. Serving builds that transaction from the currency
  (`serving/features.py:115`).
- `ReplayFallback` (`featurestore/fallback.py:37-60`) returns rows with whatever the corpus
  carried.
- No replay or rebuild path in `ml/src` reads a fallback row's country.

So the wrong value never reached a feature or a score. The defect was latent, and the corrected
backlog text in `docs/backlog/product.md` (PB-154) is accurate.

The Docker parity corpus (`test_db_fallback.py:425-427`) is all `RWF` with `account_country="RW"`,
and the test institution is Rwandan. Institution country and currency country therefore agree
there, so the parity test could not have caught the defect and cannot catch a regression. Only
the unit test guards it (see finding 4).

Fix (optional): add a one-line comment on `_member`, or on `Durable.transactions`, saying that
history rows' `account_country` is not read today and is currency-derived if a consumer needs it.

### 3. MINOR: unknown currency gives `None` here, while serving and training raise

Evidence:
- `postgres.py:234` uses `self._country_of_currency.get(currency)`.
- Serving uses `Reference.account_country` (`featurestore/reference.py:74-81`), which raises
  `ReferenceDataError`.
- Training (`cli.py:199`, `origin[currency]`) raises `KeyError`.

This difference is acceptable, and arguably right:
- A history row is past data, not the transaction being scored. Raising would turn one odd
  historical row into `FallbackUnavailableError` for the whole account read.
- The old code also produced `None`, from a NULL column (the second row in the unit test).
- `Transaction.account_country` is `str | None` (`features/types.py:37`).
- The only consumer ignores the field (finding 2).
- Any future consumer that fed such a row to the temporal or corridor code would fail loudly
  (`online.py:640-645`, `_required` at `online.py:1093`) rather than silently.

No unit test covers the unknown-currency branch, though.

Fix: add a row with an undeclared currency (for example `"XXX"`) to the unit fixture and assert
`account_country is None`. That records the intended difference from serving. Optionally also
mention it in the comment at `postgres.py:83-85`.

### 4. Information: the unit test does fail on the reverted code

Evidence: in `ml/tests/featurestore/test_postgres_fallback.py`, the first `SEEN` row carries `"RW"`
in V67's institution-country column (around line 96) and currency `"KES"`. The old code returned
`"RW"`, so `assert first.account_country == "KE"` (line 151) fails on revert. The second row has
a NULL column (line 112), which the old code mapped to `None`, so line 155 (`== "KE"`) fails as
well. The pin is effective. No change needed.

### 5. MINOR: the `noqa` justification miscounts slightly

Evidence: `postgres.py:71`
`# noqa: PLR0913 - two reference tables and three bounds, each a setting`.

The suppression is needed: ruff's default `max-args` is 5, and the method now has six parameters
besides `self`. Before the change it had exactly five and carried no `noqa`. RUF100 will not flag
the suppression, and the "code, then free text" form matches the existing `PLC0415` suppressions
in this file (`:270`, `:288`). However, `clock` is an injected time source, not a bound, and
`connect` is not counted in the text.

Fix (cosmetic): for example, "a connector, two reference tables, two bounds and a clock".

### 6. Information: lint and typing read clean otherwise

- `Mapping` is imported from `collections.abc` (`postgres.py:24`), matching `UP035`, and is used
  in the annotation.
- Under `from __future__ import annotations`, `dict(country_of_currency)` infers `dict[str, str]`.
- `.get(currency)` on a value unpacked from `tuple[Any, ...]` types as `str | None`, which matches
  the field.
- `_institution_country` in the tuple unpack is underscore-prefixed, so neither F841 nor RUF059
  applies.
- `server.py:262` passes `reference.country_of_currency`, a `Mapping[str, str]` on `Reference`
  (`reference.py:32`).
- The changed lines are all within 100 columns and look `ruff format`-stable. The only line over
  100 in the touched files is the pre-existing `test_db_fallback.py:201`, which carries a
  `noqa: S603`.

### 7. MINOR: V67 still documents and returns the institution country, and the fallback ignores it

Evidence: `backend/persistence/src/main/resources/db/migration/V67__feature_store_fallback.sql:63,74`
returns `i.country` as `account_country`. The column now crosses the wire unused. Migrations are
immutable, so no change is needed now. A future migration that touches this function could drop
or rename the column so that no other reader treats it as the sender's country.

Fix: optional backlog note. No change in this commit.
