# Datasheet: FraudShield-EAC synthetic transaction benchmark

Following the Datasheets for Datasets template (Gebru et al., 2021). It describes the dataset the
generator in `dataset/` produces. Every figure here comes from a generated run and is reproducible
with the seed given below; nothing in this document is an estimate.

The companion documents are `dataset/params_provenance.md` (every generator parameter with its
provenance), `dataset/realism_report.md` (the gate checks on a generated run) and
`docs/ml/scenarios/` (one design note per fraud scenario).

## Motivation

**For what purpose was the dataset created?** To train and evaluate the FraudShield fraud
detection system for East African mobile-money, card and agent-banking payments. Public payment
data for the region does not exist in a form that may be redistributed, and the systems that hold
it cannot share it, so the project generates a synthetic benchmark instead of using real customer
transactions.

**Who created it?** Marius Bayizere, as the FraudShield project (see `LICENSE` and the repository
history). No external funding.

## Composition

**What do the instances represent?** Payment transactions of simulated retail customers in five
East African countries, with the account events that accompany them and a label per transaction.
Three tables:

| Table | Columns | Content |
|---|---:|---|
| `transactions` | 14 | one payment: identifiers, amount and currency, channel, merchant category, coarse location, device, agent, counterparty country, timestamp |
| `labels` | 6 | the true and the observed fraud label, the scenario type and sub-variant, and when the label became available |
| `account_events` | 3 | SIM swaps and device changes on an account, with their time |

**How many instances are there?** The release target is at least 5,000,000 transactions over 24
months (2024-01 to 2025-12). The verification run documented in `dataset/realism_report.md` holds
1,012,522 transactions, generated at 178 MiB peak resident memory; the figures in this datasheet
come from that run unless stated otherwise.

**Which run, exactly (added 2026-09-19).** Every figure here comes from the dataset generated at
tree `d85385fa43b8a392271135bf226d7053eaab01ce`, seed 20260917. **Regenerating at the `m2-complete`
tag reproduces M2's published figures; regenerating on `main` does not.** PB-29 moved every country
fact into packs, and `countries.simulated()` sorts, so country iteration changed from declaration
order to alphabetical and every downstream random draw shifted. **No parameter value changed** — the
pack FX rates are byte-identical to the table they replaced. The dataset's *properties* are
unchanged: every gate still passes, every rank order holds, the fraud rate is the same to three
decimal places. The *draw* is different, so absolute figures moved. A reader comparing M2's tagged
evidence against a fresh run on `main` is seeing a **re-draw, not a corrected error and not a
defect**.

**What is the smallest run that produces the whole dataset?** About **170,000 rows**. Below that the
mule-account role pool is usually empty, and a scenario with no role holder cannot be staged, so a
smaller run yields seven of the eight fraud scenarios. The generator refuses such a run by default
and names the size that would work; a development run may waive the refusal explicitly, and the
scenarios that could not be staged are then listed in `manifest.json` as `scenarios_not_staged`, so
a deficient dataset identifies itself rather than resembling a small release.

The figure is derived rather than chosen. Both sides scale with the run — role holders available
grow like `a·n`, rows demanded like `b·n` — so the requirement looks scale-free, and the guard
originally assumed it was. What is not scale-free is the integer pool actually drawn, whose spread
grows only like `√n`: at 30,000 rows one seed held 27 mules and another 2. The minimum is therefore
set where the expected pool clears the requirement by three standard deviations,
`E[holders](n) ≥ needed(n) + 3√needed(n)`, and it is solved **numerically rather than in closed
form** because below 300,000 rows the requirement is floored at one whole holder and is not
proportional to `n`. Two closed-form derivations were tried and rejected for returning minima below
a size that had already failed (M2 milestone review, MAJOR M-4).

**The minimum is a property of the parameters, not of the generator.** 170,000 rows applies to the
shipped defaults, where `mule_account` binds first. Change a role fraction or a scenario share and
it moves, sometimes by an order of magnitude: raising `fraud.scenario_share.synthetic_identity` to
0.9 with `fraud.synthetic_identity_fraction` at 0.01 puts the minimum at 1,251,558 rows, because
that scenario then binds instead. Anyone editing `dataset/generator/params/fraud.yaml` or
`population.yaml` must recompute it — the generator does it for you: run at any size and, if the
parameters need more rows, the refusal names the figure.

```console
$ uv run fs-dataset generate --rows 10000 --seed 20260917 --output /tmp/probe
ScaleError: scenario mule_account needs 1 customer-months ... Generate at 169,492 rows or more.
```

The sensitivity harness (`fraudshield_dataset.sensitivity`) deliberately runs below the minimum and
waives the refusal: it perturbs shares one at a time at development scale and compares each run
against a baseline generated the same way, so both sides carry the same deficiency and the
comparison remains valid.

**Does the dataset contain all possible instances or a sample?** It is generated in full: every
simulated customer's every simulated transaction is present, with one exception recorded in
`manifest.json` as `rows_dropped_after_simulation_end` — a fraud burst or delayed drain that starts
near the end of the last month can run past the simulated period, and those few rows are dropped
rather than written into a month that does not exist. There is no other sampling step.

**How are the files partitioned, and what does the partition key mean?** Each table is written as
`month=YYYY-MM/part-0000.parquet`. **The key is the simulation month in local time, not the UTC
month of `transaction_timestamp`**, and the difference is bounded and measurable rather than
incidental.

Activity is placed in each customer's local time and then converted to UTC. A transaction at local
00:30 on the 1st in a UTC+3 country (Kenya, Tanzania, Uganda) therefore carries a UTC timestamp in
the *previous* month while sitting in the current month's partition. The generator carries rows
forward past a partition's end but never backward, so the drift is one-directional.

Measured at 1,012,522 rows (tree `d85385f`): **463 rows (0.046%) have a UTC timestamp earlier than
their partition's start; none is later.** The first partition's earliest timestamp is
2023-12-31 21:08 UTC, before the dataset's nominal start of 2024-01-01. (The pre-PB-29 draw gave
447 rows, 0.044% — the same property, a different draw.)

**What a consumer must do.** Backward drift can never exceed the largest UTC offset in the dataset
(+3 hours), so it can never span a whole partition. To select every row whose *UTC* month is `M`,
read partitions `M` and `M+1` and filter on `transaction_timestamp`. Partition pruning on `month=`
alone is unsound for a UTC-timestamp filter. The bound is pinned by
`test_the_partition_key_is_the_simulation_month_and_its_drift_is_bounded`, which fails if a row is
ever carried forward or reaches back further than the maximum offset.

**Is any information missing?** Yes, by design. Fields that a real payment may lack are absent in
the same pattern for fraudulent and legitimate rows: `device_fingerprint` is null on channels that
carry no device, `agent_id` is null away from agent banking. The realism checks fail the dataset
if a null pattern occurs for fraud that never occurs for legitimate rows on the same channel,
because that would make missingness itself a label.

**Are relationships between instances made explicit?** Yes. Transactions carry the account and
counterparty tokens that link them, and `account_events` links to accounts by the same token.
Fraudulent transactions belonging to one incident share an account and fall inside one burst.

**Are there recommended data splits?** Yes, a temporal split (binding resolution D-07), computed
from the calibrated volume and reported with measured counts:

| Split | Rows | True fraud rate | Span (days) |
|---|---:|---:|---:|
| train | 797,013 | 0.861% | 602.34 |
| validation | 101,986 | 0.881% | 57.69 |
| calibration (last part of validation) | 40,877 | 0.881% | 25.37 |
| embargo (excluded) | 10,981 | 0.883% | 7.0 |
| test | 102,542 | 0.927% | 63.98 |

The seven-day embargo between validation and test exists so that a model cannot see the days
immediately before the test period. One fraud sub-variant occurs only in the test period, so that
generalisation to an unseen variant can be measured.

**Are there errors, sources of noise or redundancies?** Yes, deliberately. Labels carry noise in
both directions: 1.66% of true fraud is unlabelled and a further 1.67% of true fraud's worth of
legitimate rows are labelled fraud, which is what a real investigation backlog produces. The dataset is otherwise
internally consistent: no duplicate identifiers, no malformed values (checked on every row).

**Is the dataset self-contained?** Yes. It depends on no external resource, and the licence
travels with it.

**Does it contain data that might be offensive, or confidential, or that relates to people?** No.
Every customer, merchant, agent, device and account is simulated. No real personal data, and no
data derived from a real person, is present at any stage; there is nothing to de-identify because
nothing was identified. Locations are coarse coordinates around simulated home points, not
addresses.

## Collection process

**How was the data collected?** It was not collected. It is generated by
`fraudshield_dataset.generator` from parameters in `dataset/generator/params/*.yaml`, with a seed
(the verification run uses 20260917). Generation is deterministic: the same seed produces
byte-identical files, and the files do not depend on how work is batched, which is a test.

**Who was involved?** No human subjects, no crowdworkers, no third parties.

**Over what timeframe?** The simulated period is 2024-01-01 to 2025-12-31. The generator was
written in September 2026.

**Were ethical review processes conducted?** Not applicable: there are no human subjects. The
dataset exists so that development does not require real customer data.

**Where do the parameter values come from?** This is the dataset's main limitation and it is
documented parameter by parameter in `dataset/params_provenance.md`:

| Provenance | Count | Meaning |
|---|---:|---|
| SOURCED | 12 | taken from a document the author downloaded and read |
| ASSUMED | 55 | a modelling choice with a stated rationale and no source |
| CALIBRATED_TO_SRS_TARGET | 14 | set to meet a target in SRS section 7.1 or a binding resolution |

Twelve parameters are sourced, after a sourcing pass that read eight documents in full: the Bank of
Tanzania Payment Systems Annual Report for 2024, the National Bank of Rwanda Annual Report
2024-2025, the Fifth Rwanda Population and Housing Census 2022, FinScope Rwanda 2024, the World
Bank/IMF official exchange rate series, the IANA time zone database, the ISO 4217 currency list and a
Rwandan school calendar.
They cover the structural quantities: transactions per active customer, agents and merchant
acceptance points per customer, the urban share of customers, per-channel mean amounts, the split
between person-to-person and merchant payments, the cross-border share, volume growth, exchange
rates, currency codes, time zones and the school terms. `docs/research/sourcing_pass.md` records every document, every value it changed, and
what it could not establish; `docs/research/parameter_influence.md` records how the parameters were
ranked before the pass, so the effort went to the ones that move the dataset.

**Most parameters remain modelling choices, and no fraud parameter is sourced.** Of the 25
parameters in `fraud.yaml`, 21 are ASSUMED — scenario prevalence, the mix between scenario types,
incident length, attack timing, adaptation behaviour, the mule structure — and the other four are
calibrated to SRS targets (the overall and test-period fraud rates, the monthly intensity schedule
and the tolerance that guards it), which are design requirements rather than observations. No
publication found gives fraud incidence by type for these markets. The National Bank of Rwanda's annual report discusses a
Fraud Prevention Forum and consumer complaints about mobile money fraud without publishing incident
counts, and the Bank of Tanzania report does not break fraud out at all. Sourcing those numbers
weakly, from a vendor report or a global aggregate presented as regional, would be worse than
leaving them assumed.

The sourced levels are also not all Rwandan: Tanzania publishes a per-category breakdown of value
and volume and Rwanda does not, so the per-channel amounts, the transactions per user, the
person-to-person split and the growth rate come from Tanzanian aggregates while the dataset's
country mix is Rwanda-weighted. They are order-of-magnitude anchors rather than claims about one
market, and each citation says so. Nothing in this dataset should be read as a measurement of East
African payment behaviour, and results on it do not transfer to a production system.

## Preprocessing, cleaning and labelling

**Was any preprocessing done?** The generator writes the data in its final form. Values follow the
ingestion contract: tokens, merchant category codes, amount scale and coordinate precision are
checked on every row.

**How were labels obtained?** The generator knows which transactions it made fraudulent, so
`is_fraud_true` is exact. `is_fraud_observed` is that label after the noise described above, and
it is the label a model should train on. `label_available_at` records when an investigation would
have confirmed the label, so that a model can be trained without using labels from the future.

**Is the software available?** Yes, in this repository, under the Apache License 2.0.

## Uses

**What tasks could the dataset be used for?** Fraud detection on transaction streams: supervised
classification with a severe class imbalance, evaluation under temporal drift, calibration, and
the study of a novel fraud variant that appears only in the test period.

**What must a user know to avoid misuse?**

- It is synthetic. Model performance here does not transfer to a production system, and a
  detection rate measured on it is not a claim about real fraud.
- Twelve of 81 parameters are sourced; no fraud parameter is (21 assumed, 4 calibrated to SRS
  targets, see above). Conclusions about East African fraud behaviour cannot be drawn from it.
- The fraud rate is calibrated to a target of 0.87% overall and 0.91% in the test period, not
  measured from any institution.
- Amounts and locations are simulated; they carry no commercial or geographic information.
- **No single feature separates the classes beyond AUC 0.80** (D-08), measured as
  `max(AUC, 1 − AUC)` with out-of-fold encoding for categoricals. At 1,012,522 rows (tree
  `d85385f`) the strongest transaction column is `merchant_category_code` at **0.707**. The
  strongest channel in the dataset as a whole is **0.746**, and it is not a transaction column:
  joining `account_events` to the transactions by account token — which this datasheet invites
  above — gives the time from a SIM swap or device change to that account's next transaction, and
  that delay carries 0.746. (M2 published 0.706 and 0.758 from the pre-PB-29 draw.)
- Single-feature AUCs are computed with target encoding whose folds are **whole accounts**. Folding
  per row leaves the other rows of a fraud incident inside the estimate that scores it, which
  inflated `merchant_category_code` by 0.005 and `channel` by 0.006 before it was corrected. Any
  re-measurement that folds per row will read higher than the figures here.
  A benchmark result that uses the event join is therefore not comparable to one that does not, and
  which of the two was used should be stated.
- The event-delay signal is partly an artefact of the generator, not only of the scenario. The lead
  time is drawn from `fraud.takeover_lead_minutes = [5, 60]`, provenance `ASSUMED`, so every
  fraud-enabling event is followed by its drain inside a tight uniform window, with no long tail and
  no unexploited event. Real SIM swaps sometimes precede nothing. Do not read the strength of this
  signal as evidence about how quickly real takeovers follow a SIM swap.

**What is known to be unresolved about it?** Three things, stated rather than buried.

1. **Single-feature AUC depends on the size of the run by more than seed noise, and the cause is not
   known.** Ten seeds at 300,000 rows give a seed-to-seed standard deviation of 0.0055 for the
   strongest feature, while the range across sizes is 0.065 — twelve times that. It is not the
   fold-grouping defect corrected above: account-grouped folds remove the same amount at 300,000 and
   1,000,000 rows. Any comparison of feature sets, models or ablations must therefore use the same
   dataset size and say so; a figure quoted without its scale cannot be interpreted.
2. **The anti-leakage bands' inflation factor is measured only at 10,000–60,000 rows.** The value
   1.62 is derived from 45 clean datasets there, where per-scale estimates agree. Above 60,000 rows
   it is an extrapolation: four to ten seeds give estimates from 0.84 to 2.32, too wide to settle.
3. **The release-size run has not happened.** The target is 5,000,000 transactions; every figure in
   this datasheet comes from a 1,012,522-row run. ML-DATA-01 is recorded as
   `VERIFIED_AT_REDUCED_SCALE`, not as met.

**What do the M3 features go silent on?** One documented silence, with the measurement that says
what it costs on this dataset.

A *predecessor* is a transaction **strictly earlier** than the one being scored. Every windowed
feature uses that bound, and it is the only one the online path can implement: at scoring time a
transaction stamped the same instant may not have arrived, so a rule that counted it would make
the training and serving paths disagree precisely on simultaneous transactions — which is itself a
fraud pattern, a burst of drains inside one second. The consequence is that when an account's
**only** prior transaction shares the scored timestamp exactly, three features are NaN rather than
reporting a journey: `seconds_since_last_tx`, `distance_from_last_tx_km` and `implied_speed_kmh`.
A capped speed computed from a zero gap would be a number with no journey behind it.

**Measured on 201,243 rows at seed 20260917 (200,000-row run, tree `65c8351`): the case does not
occur.** Zero of 201,243 rows share an `(account_id, transaction_timestamp)` pair with another —
no two transactions on an account are ever stamped the same instant — so the three features lose
nothing here. The silence is a property of the rule, not a gap in this benchmark, and it is
recorded so that a consumer whose own data does contain simultaneous transactions knows what these
features will do with them.

**Simultaneous-burst detection belongs to the velocity group, and it can see the bursts this
dataset has.** Since no timestamps collide, the strictly-earlier bound excludes nothing from
`tx_count_60s`. In the same run, 6 consecutive same-account pairs are less than a second apart and
261 are less than a minute apart, out of 199,999 — so `tx_count_60s` is non-zero for roughly
**0.13%** of rows. That is a rare feature rather than a dead one, which is what a burst indicator
should be; but any claim resting on it is a claim about a few hundred rows at this scale, and it
should be quoted with that count rather than with an overall rate.

**Which of the 44 features can this dataset actually feed?** Thirty-six. Eight read reference
data that neither the dataset nor M1's schema holds — account and counterparty opening dates, KYC
tier histories, agent float and registered premises, and the currency's round denominations — so
they return NaN for every row until that data exists (PB-44). They are implemented and tested on
both paths; what is missing is the input. `days_since_sim_swap` is **not** among them:
`account_events` already carries `SIM_SWAP` rows, so it is computable as soon as the join is
wired.

This matters for reading any result computed here, because a feature that is NaN for every row is
indistinguishable in a training run from one that is merely often missing. A result quoted as
using "44 features" should say how many of them carried information.

**What does `implied_speed_kmh` mean at its cap?** Not "fast". The cap is 1,000 km/h, above
commercial cruising speed, so a value at the cap says one person cannot have been in both places
in that time — a proxy for a shared account, a credential used elsewhere, or a spoofed location.
It saturates rather than reporting 3,000 or 40,000 so that a model cannot split *inside* the
impossible range and learn a distinction with no meaning.

**Are there tasks for which it should not be used?** It must not be used to characterise real
customers, real fraud prevalence, or the behaviour of any real institution, and it must not be
presented as real transaction data. The scenario notes describe detection signals, not how to
commit fraud.

## Distribution

**How will it be distributed?** As a release directory built by `uv run fs-dataset export`,
containing the Parquet partitions, a CSV copy of each table, the manifest, the parameter
provenance, the realism report, the licence and a `SHA256SUMS` file covering everything. A
recipient verifies it with `sha256sum -c SHA256SUMS`.

**Under what licence?** The dataset is licensed CC BY 4.0. The legal code travels with the release
as `LICENSE-CC-BY-4.0.txt`, verbatim as published; its source URL, fetch date and SHA-256 are
recorded in `dataset/release/README.md`. The code in this repository is licensed separately under
the Apache License 2.0.

**Are there IP-based or other restrictions?** No. The data is synthetic and was not derived from
any third-party dataset.

## Maintenance

**Who maintains it?** The repository owner, through this repository.

**How can the dataset be regenerated or extended?** From the repository, with the parameters and
the seed:

```console
$ uv run fs-dataset generate --output out --rows 5000000
$ uv run fs-dataset report out --rows 5000000 --full
$ uv run fs-dataset export out --output release
```

The same seed reproduces the same bytes. Changing a parameter changes the dataset, so parameter
changes are commits with reasoning, and `dataset/params_provenance.md` is regenerated with them.

**Will it be updated?** When the generator changes, a new release is produced and the realism
report is regenerated with it. Older releases are identified by their manifest checksum.

**If others want to extend it?** By pull request against this repository, so that the parameters,
their provenance and the checks stay together with the data.
