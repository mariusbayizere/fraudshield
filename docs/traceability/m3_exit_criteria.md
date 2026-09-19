# M3 exit criteria

M3 builds the feature pipeline on the M2 benchmark. The criteria below are the milestone's
definition of done: each is checkable, and the first two exist because M2 paid for them.

## Rules M2 paid for

Numbered E1, E2 and E12–E14. The identifiers are stable references, so they are not renumbered when
a rule is added; E12–E14 arrived after the feature-pipeline criteria were written.

### E1 — every fold, split, sample and target encoding groups by the feature's unit of history

M2 found the same defect three times, each a correct fix that stopped one step short of the
structural form of the problem (`docs/research/lab_notebook.md`, "The pattern"). The third instance
was the categorical encoding: it removed a row's own label from its own score and then assigned
folds **per row**, so the other rows of the same fraud incident stayed inside the estimate that
scored it. Fraud arrives as incidents sharing an account; a row is not an independent unit.

Required of every feature, metric and model in M3:

- fold assignment, train/validation/test splits, negative sampling and target encoding group by
  **account**, never by row;
- and respect time — no future row informs a past one, which the temporal split already enforces at
  the dataset level and which feature windows must not undo;
- each carries a test that **fails if the grouping is removed**, in the shape of
  `test_the_categorical_encoding_folds_by_account_not_by_row`: construct data where grouped and
  ungrouped genuinely disagree, and assert they do.

A test that passes under both groupings tests nothing; M2's first attempt at this test did exactly
that, because the constructed categories were purely fraud or purely legitimate.

#### Amendment 2026-09-19 — "by account" was the right rule for the wrong reason

The rule above says **account**, because the incident shares an account. Declaring `history_key` on
all 44 features showed that is true of 37 of them and false of seven. **Account grouping does not
isolate a feature whose aggregate is keyed by something other than the account**, because the leak
does not travel through the account: a ring moving money from ten victims into one mule produces ten
rows reading one counterparty-keyed object, and splitting those accounts across folds puts
validation rows inside the feature the training rows saw.

This is M2's M-8 defect one level up — M-8 folded per row when the unit was the incident; grouping by
account folds per account when the unit is the counterparty, the device, the agent or the cell. The
generalised rule is therefore stated **per `history_key`**, not globally:

| `history_key` | Grouping required | Why |
|---|---|---|
| `ACCOUNT` (37 features) | account-grouped folds, as originally written | the incident shares an account and nothing wider |
| `COUNTERPARTY`, `DEVICE`, `AGENT` (6) | ~~component folding~~ **see the correction below — measurement refuted this for two of the three** | the reasoning held; the measurement did not |
| `GEO_CELL` (1) | **component folding is NOT valid.** Use a **spatial-block split**: cells are assigned to blocks, blocks to folds, and a cell's estimate is computed only from rows outside the validation block | the account-cell graph is dense — every account in one city shares cells — so components degenerate toward the whole dataset and no validation set survives |

**Component folding and the training-fold restriction are not interchangeable, and the choice is not
a matter of taste.** Component folding preserves the feature's value exactly and fails when the graph
is dense. The training-fold restriction always works and changes the feature's value, because an
aggregate computed over a fold's rows is not the aggregate computed over all history that serving
will see — trading leakage for training/serving skew, which is the defect the parity suite exists to
prevent. Pick by graph density, and measure the density rather than asserting it.

#### CORRECTION 2026-09-19 — the measurement refuted the table above

The table was written from reasoning about which entity graphs *should* be sparse. Measured on
1,012,522 rows at tree `d85385f` (`docs/research/component_sizes.json`), against the 10% threshold:

| Key | Largest component | Component folding |
|---|---:|---|
| `counterparty` | **100%** (one component) | **invalid** |
| `agent` | **38.0%** | **invalid** |
| `geo_cell` | **100%** | invalid, as predicted |
| `device` | 0.0169% | valid — but **no device in this dataset is used by more than one account**, so there is nothing to fold |

**Component folding is available for at most one of four relational keys, and that one has no
sharing to protect.** Density turns out to be a property of the dataset's scale and structure rather
than of the entity type: at 5,920 accounts sharing 199 agents, every account is a few hops from
every other, and the counterparty graph is fully connected. This is exactly why the rule is that the
decision is made against a measured distribution — the reasoning above was not careless, and it was
still wrong.

**Open, and not to be resolved by assertion a second time.** With component folding unavailable, the
candidates are the training-fold restriction (leakage traded for training/serving skew) or
**temporal separation**. The dataset already has a temporal split with an embargo, and for a strictly
backward-looking aggregate a validation row reading earlier cross-account rows is **not leakage** —
it is what serving does. The real exposure is narrower: **out-of-fold target encoding within the
training period**, where folds are random and account-grouped rather than temporal, which is where
M-8 lived. The likely rule is that out-of-fold encodings are computed over rows strictly earlier in
time than the row being encoded. Not adopted here pending an owner decision, because it changes M4's
evaluation design.

**Separately: `accounts_per_device_7d` has zero variance in this dataset** and the device-sharing
term of `synthetic_identity_score` is dead with it. That is a generator gap against ML-DATA-07, not
a feature defect, and is recorded as PB-40.

#### The density threshold is measured, not judged

**Required before any fold decision ships:** compute the **component-size distribution** for each
relational key (`counterparty`, `device`, `agent`, `geo_cell`) on the 1,006,249-row dataset, and
record it in `docs/research/lab_notebook.md` with the tree hash of the run, per E2's scale rule.
Report at minimum the largest component as a fraction of accounts, the median and the 99th
percentile.

**The threshold: component folding is invalid once the largest component exceeds 10% of accounts.**

The argument, stated here rather than only in the notebook, because this is where a future reader
will hit it. Component folding requires every account in a component to sit in the same fold. Once
one component holds more than a tenth of accounts, holding out a fold leaves exactly two options:
**split the component**, which is precisely the leak the grouping existed to prevent, or **place the
whole component on one side**, which surrenders at least a tenth of the data from training or
produces a validation set dominated by a single component. Neither is a fold. Below that share both
problems stay small enough to absorb.

**The 10% is a judgement, not a derived constant.** Nothing measures it; it is a considered opinion
about where the two failure modes above become intolerable, and a later reader is entitled to argue
with it — with an argument, and preferably with the component-size distribution in hand. What is
*not* negotiable is that the decision is made against **a measured distribution** rather than against
an author's sense of whether a graph "feels" sparse. That intuition is what produced the
`geo_cell_fraud_rate_30d` leakage note asserting a protection that did not exist.

#### The spatial-block split must be spatially contiguous

A "block" that is an arbitrary partition of cells is not a spatial block, it is a random cell
partition wearing the name — and it leaks, because **adjacent cells are highly correlated**. A fraud
cluster straddling two neighbouring cells lands partly in training and partly in validation, and the
validation estimate is optimistic by exactly the amount the split was meant to remove.

Required:

- **Blocks are contiguous by construction**, formed by coarse spatial tiling: cells are assigned to
  blocks by a grid an order of magnitude coarser than the cells themselves, so every cell in a block
  is spatially adjacent to others in it. Contiguity is then a property of the construction rather
  than something to verify afterwards.
- **A one-cell buffer is excluded at block boundaries.** Cells immediately across a held-out block's
  edge are dropped from training for that fold. Without it the split is contiguous but still leaks
  across the seam, which is the one place a contiguous split does not help.
- **Mutation (E14): assign cells to blocks at random rather than by tile, and assert the geographic
  feature's apparent AUC rises.** A rise is the correlation between adjacent cells being converted
  into validation optimism, and it is the only observable signature of losing contiguity. If the AUC
  does *not* rise, either the buffer is doing the work or the spatial correlation is weaker than
  assumed — both worth knowing, and both reportable rather than quietly passing.

#### Every non-`ACCOUNT` feature names its control and a mutation

A feature whose `history_key` is not `ACCOUNT` must declare `cross_account_control` in the registry,
naming **both** what makes it fold-safe **and the mutation that would detect the leak if that control
failed**. `WindowContract.__post_init__` refuses the contract otherwise, and refuses a control that
only asserts safety.

That enforcement exists because of a specific failure: `geo_cell_fraud_rate_30d`'s first leakage note
claimed account-grouped folds kept validation labels out of cell estimates. The claim was false, it
cited the right requirement while drawing a conclusion the requirement does not support, and **a
reviewer auditing it would have been reassured**. An omission invites scrutiny; a confident wrong
answer consumes it. The check is deliberately crude — it requires the word "mutation" — because a
check that could be satisfied by writing a more convincing sentence is how the original note passed.

### E2 — every reported metric states the scale it was measured at

Single-feature AUC on this benchmark depends on dataset size by more than seed noise: ten seeds at
300,000 rows give a seed-to-seed sd of 0.0055, while the range across scales is 0.065. The cause is
**not** the fold-grouping defect — account-grouped folds remove the same amount at 300,000 and
1,000,000 rows — and it is **unexplained** (M2 milestone review, area 1).

Until it is explained:

- every metric that depends on target encoding is reported at a fixed, stated scale;
- comparisons between feature sets, models or ablations use the **same** dataset size, and say so;
- a metric quoted without its scale is not admissible as evidence, in the paper or the review record.

### E12 — every bound, absence or invariant test asserts its own precondition first

Twice in one session a test passed while proving nothing, and in both cases the cause was the same:
**the fixture did not contain the condition the test claimed to check.** The fold-grouping test
constructed categories that were purely fraud or purely legitimate, so grouped and ungrouped folds
agreed trivially. The partition-drift test used a fixture seed with zero drifting rows, so a bound on
how far rows may drift held over an empty set. Neither is visible by reading the test; both were
found only by mutating the thing under test and seeing the test still pass.

Required of every test that asserts a bound, an absence or an invariant:

- **assert the precondition explicitly, before the assertion it exists for** — `assert drifting > 0`
  before bounding the drift, `assert mixed_categories` before checking the encoding, `assert
  crossings > 0` before asserting window behaviour at a boundary;
- the precondition assertion carries a message saying what is missing, so a fixture that stops
  exercising the case fails loudly instead of silently passing;
- where the precondition is probabilistic in the fixture, pick a seed that makes it deterministic
  and say in the docstring why that seed. A precondition that holds one run in fifteen is a flaky
  test, not a precondition.

A precondition turns a vacuous pass into a loud failure without needing a mutation run, which is
what makes it structural rather than a habit someone has to remember.

### E13 — the 44 feature tests each exercise their feature non-trivially

Per Part E.2 each feature has unit tests with hand-computed expectations. Each must additionally
include at least one case where the feature is **non-trivially exercised** — non-zero history, a
window boundary crossed, a structural NaN present — with that precondition asserted per E12. A
velocity feature whose tests only ever see an empty history, or a distance feature that only ever
sees a single transaction, proves nothing about the feature and everything about the fixture.

### E14 — mutation checks run as the tests are written, and are recorded

The parity suite and the feature gate tests carry a mutation table naming each deliberate divergence
tried and the result, filled in **as each is implemented** rather than assembled at the end. The
table lives beside the design it tests (`docs/ml/training_serving_parity.md` for parity). A mutation
that passes is not a curiosity: it is the specification for a case the test is missing.

### E15 — no test mutates anything outside `tmp_path`, and a check enforces it

Three evidence runs were broken in one session, by three different causes with one shared property:
**a run's inputs changed from outside the run, and the run could not tell.**

1. Source edited while a suite ran — the suite reflected a tree that no longer existed.
2. `uv sync --reinstall` run mid-suite — it removed the workspace packages out from under pytest,
   and the suite died without a single source file changing.
3. A test inside the suite edited the repository's `geography.yaml`, restoring it in a `finally`
   that did not run when the body raised. The suite then ran against a country share naming a pack
   that did not exist.

The third is the one that makes this structural rather than a discipline: the mutation came from
**inside the suite being measured**, so no amount of care about what the operator does outside it
would have prevented the failure.

Required:

- **No test may write outside `tmp_path`.** A test needing a different parameter tree copies it into
  `tmp_path` and loads from there — `load_parameters()` takes a directory for exactly this reason.
- **Never restore shared state in a `finally`.** A `finally` does not run when the process is killed,
  and it runs too late when the body raises inside a fixture other tests have already used. Copying
  has no cleanup path to get wrong.
- **A session-scoped fixture hashes the parameter and configuration trees before and after the
  suite and fails if they differ, naming the changed file.** It catches the mutation even when
  cleanup does not run, which is precisely the case a `finally` cannot cover.

**What the fixture does not do, stated here so nobody later assumes otherwise.** It compares digests
at session start and session end, so it fires *after* the mutating test and every test that ran
behind it. It therefore **prevents a bad run being cited, not a bad run happening**: by the time it
fails, any test that read the mutated file has already produced a result, and those results are
discarded wholesale rather than individually identified.

This matters because the fixture is the kind of guard that invites a false inference — "shared state
is checked automatically, so the mutation checks are redundant". The opposite holds. The fixture is a
**detector of a contaminated run**, and E14's mutation records remain the only evidence that a test
can fail for the reason it claims. Neither substitutes for the other, and a reviewer who accepts the
fixture in place of a mutation record has accepted strictly less than before it existed.

**Mutation record (E14), 2026-09-19.** A temporary test that appends a line to
`dataset/generator/params/geography.yaml` — the exact mutation that broke a real run — was added and
the suite run. Result: the guard fired, naming `modified: dataset/generator/params/geography.yaml`,
and the session exited **1**. The mutation was removed and the file restored by copy from a
pre-image, with `git status` verified clean afterwards rather than trusted to a `finally`.

**One thing that record exposes.** pytest reports a session-fixture teardown failure as an *error*,
not a failure, so the summary line read `1 passed, 1 error` — the mutating test itself is still
counted as **passed**. Anyone scanning output for the word "failed" will not find it. The exit code
is correct and CI is therefore correct; a human reading a terminal may not be. The guard's message
says results "cannot be cited" for this reason.

## Feature pipeline

- **E3** — the 44 engineered features are re-checked against the D-08 single-feature AUC ceiling of
  0.80, with account-grouped encoding, at a stated scale.
- **E4** — feature computation is deterministic from the dataset and a seed, verified byte-identical
  across batch sizes, as the generator is.
- **E5** — no feature reads a column the anti-leakage gates exclude by construction (identifier
  bytes, sub-second timestamp parts, row position, label delay), and a test asserts the feature
  set's columns are disjoint from that list.
- **E6** — the `account_events` join is available to features, and any feature derived from it is
  measured and reported alongside the transaction features. The event-to-transaction delay separates
  the classes at 0.758 and is the dataset's strongest single channel; a feature set that uses it is
  not comparable to one that does not, and each must say which.

## Prerequisites landing in the same branch

- **E7** — PB-26: the `month=` partition key is the local month while timestamps are UTC. Fixed
  **before** any feature reads those partitions, or time-window features are silently distorted.
- **E8** — ADR 0023's country packs and `corridor_class` generalisation land here rather than in a
  separate branch: M3 features consume them, and splitting would cost a full review cycle. No
  country, currency or bloc is hard-coded in feature code.

## Governance

- **E9** — every new parameter carries provenance, and `fs-dataset provenance --check` passes.
- **E10** — the traceability register covers every M3 requirement with a tagged test, and the
  rendered matrix is current.
- **E11** — a milestone review covering: leakage under the new features, determinism, the grouping
  rules above, and whether every claim in the M3 walkthrough is backed by a test or a measurement.
