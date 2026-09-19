# M3 exit criteria

M3 builds the feature pipeline on the M2 benchmark. The criteria below are the milestone's
definition of done: each is checkable, and the first two exist because M2 paid for them.

## Rules M2 paid for

Numbered E1, E2 and E12–E14. The identifiers are stable references, so they are not renumbered when
a rule is added; E12–E14 arrived after the feature-pipeline criteria were written.

### E1 — every fold, split, sample and target encoding is account-grouped and time-respecting

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
