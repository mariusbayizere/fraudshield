# 0023 — Africa-wide portability: country packs, blocs, and a validated core

- **Status:** Accepted
- **Date:** 2026-09-18
- **Requirements affected:** ML-DATA-03, ML-DATA-05, ML-DATA-07, ML-DATA-08, D-03, D-08,
  E.2 feature list, E.5 evaluation
- **Owner direction (2026-09-18):** the system must be Africa-wide and portable beyond one country or
  region, while validation stays where real sources exist; the simulated country set is *not* to be
  broadened; implemented as a scoped branch after M2 closes and before M3 features, with this ADR
  first.

## Context

M2 produced a benchmark for five East African Community countries, and it did so by hard-coding that
choice in several places: `COUNTRIES` in the generator, an EAC-specific `corridor_class`
(`DOMESTIC | EAC_CROSS_BORDER | NON_EAC_CROSS_BORDER`, build prompt Part E.2), currency and timezone
tables in Python and Java, and country lists in the frontend. A reader can reasonably ask whether the
work generalises or whether East Africa is baked into it.

Two pressures point in opposite directions. Portability wants the system to accept any African
country without code changes. Honesty wants claims limited to the five countries whose parameters
were sourced from documents actually read (`docs/research/sourcing_pass.md`): twelve sourced
parameters, and none of them about fraud behaviour. Expanding the *simulated* country set would make
the dataset look broader while adding nothing verified — the owner's direction is explicit that this
must not happen.

## Decision

### 1. Country packs, and nothing country-specific outside them

Every country-specific value moves to `dataset/params/countries/<ISO 3166-1 alpha-2>.yaml`, carrying
the same provenance discipline as the existing parameters (value, unit, provenance, citation or
rationale): currency and its ISO 4217 minor units; timezones, per region where a country spans more
than one; population and urban share; mobile money penetration; agent and merchant density; channel
mix; languages; school terms and public holidays; KYC tiers; phone number formats; and regional bloc
memberships.

No country, currency or bloc may be named in Java, Python or TypeScript source. The acceptance test
is behavioural rather than a lint: a **synthetic "Country Z" pack**, entirely assumed, is added in CI,
and the generator, the feature pipeline and the UI must work for it with zero code changes. If adding
a pack requires touching code, this decision has been violated.

### 2. `corridor_class` generalises; EAC becomes a configured case

The feature keeps its slot in the 44 (D-03, Part E.2) and becomes
`DOMESTIC | INTRA_BLOC | CROSS_BLOC_AFRICA | INTERCONTINENTAL`, with bloc membership (EAC, ECOWAS,
SADC, COMESA, CEMAC, AMU) read from the packs. A country may belong to several blocs, which is the
normal case in Africa and which the previous three-value feature could not express. EAC behaviour is
then a configuration, not a branch in code.

### 3. A validated core, and packs that exist only to prove portability

The five EAC countries remain the **validated core**: their parameters are sourced where sources were
found, and every claim in the paper is scoped to them. Three further packs — from West Africa (NG, GH
or SN, covering XOF) and Southern Africa (ZA) — are added **entirely ASSUMED** and exist to
demonstrate that the machinery is not EAC-shaped. They are never presented as validated, and the
datasheet, the claims register and the paper state the split plainly.

This is the reason the country set is not broadened in the simulation: the packs prove portability,
not coverage.

### 4. Generalisation is measured, not asserted (M4)

The evaluation (Part E.5) gains a headline experiment: **leave-one-country-out** — train on four EAC
countries, evaluate on the fifth, for each of the five — reporting AUC, recall at 1% FPR and
calibration drift against the in-distribution model, with confidence intervals. A **fine-tuning
variant** adapts the four-country model on a small labelled sample from the held-out country and
reports how much target data is needed to recover performance. The EAC-trained model is also run over
the assumed non-EAC packs, reported explicitly as a portability probe on assumed data and never as
evidence about real performance in those countries.

These rows go into the traceability register so they cannot be forgotten. The register is generated
from the SRS and the build prompt's Part B, and `fs-traceability-seed --check` rejects rows it cannot
derive, so the seeder gains Part E.5 as a source rather than the rows being hand-written — the
mechanism is part of this work, not a later cleanup.

### 5. Front-end portability, including right-to-left, now

Currency and number formatting are driven by the ISO 4217 minor units in the packs; dates and times
by the country's timezone; locale packs are pluggable. **Right-to-left support is built now**, with
CSS logical properties and direction-aware layout, and a Playwright test that exercises the UI in RTL.
Arabic is spoken across North Africa, and retrofitting direction into a laid-out interface is far more
expensive than building it in.

### 6. The dataset is renamed for accuracy

`FraudShield-EAC-Transactions` becomes **`FraudShield-Africa-Transactions`, with a validated EAC-5
core**. The README, datasheet, claims register and paper wording are updated so that no sentence
implies validation beyond the sourced countries. A name that promises Africa and a body of text that
says "validated on five East African countries" is the honest combination; the reverse is not.

## Consequences

- Adding a country becomes a data change with a provenance review, which is the right shape for a
  claim about a country.
- The generator must refuse a pack that is internally inconsistent (a currency with no minor units, a
  bloc no other pack knows), because a silent default would produce a dataset nobody can interpret.
- The M2 gates are unchanged and must stay green: leakage, determinism, provenance honesty and the
  memory budget are properties of the machinery, not of the country set. The "Country Z" test makes
  that explicit — a new pack must not move any gate.
- Three assumed packs increase the share of assumed parameters. The provenance counts in the datasheet
  will show that, and the sourced core is reported separately so the numbers cannot be read as a
  weakening of the validated part.
- PB-26 (the month partition key is the local month while timestamps are UTC) is fixed in this branch
  rather than later: M3 reads these partitions, and a wrong key would silently distort time-window
  features.

## Alternatives considered

- **Leave the EAC hard-coding and describe the system as regional.** Rejected: the owner's direction
  is portability, and the hard-coding is also what made the earlier channel-mix algebra brittle.
- **Broaden the simulated country set instead of adding assumed packs.** Rejected, and explicitly
  ruled out by the owner: it would present unsourced countries as part of the benchmark.
- **Add RTL later, once a right-to-left locale is needed.** Rejected: direction is a layout property,
  and retrofitting it means revisiting every component.
- **Hand-write the E.5 traceability rows.** Rejected: the register is generated, and a hand-written row
  fails the seed check. Extending the seeder keeps the register's guarantee that rows come from a
  source document.
