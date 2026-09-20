# Session state — M3 closed, held for the owner's tag

Rewritten 2026-09-20. Facts only; where something is unverified, assumed or open it says so.

## Where the work is

- **Branch:** `m3/features`, pushed. **Tag `m2-complete`** is at `ed7a8d9` on `m2/generator`.
- **Milestone register:** `current: M3`, `completed: [M0, M1, M2]`. **M3 is not tagged** — the owner
  holds that.

| Commit | What |
|---|---|
| `65c8351` | `corridor_class` from the packs (PB-30); the `categories` contract field |
| `ae41c88` | the dataset fingerprint (PB-41); export refuses a report about other data |
| `01af5b6` | velocity, amount, temporal, geographic — 21 features |
| `083f92e` | counterparty, device, profile, agent, synthetic identity — 19 features |
| `fad43dd` | `computable` declarations and the computability check (PB-44, PB-43) |
| `4e16e11` | E3 run, and failed — five features beat the D-08 ceiling |
| `2c80ef6` | five unbounded features stopped asking a corpus what it cannot know (M3-2) |

**Verified at `2c80ef6`:** ml **270 passed**, 96.30% branch; dataset **126 passed** in 30 minutes,
94.01% (at `fad43dd`, unchanged since); tools 177; contracts 490; `mypy` clean over 122 files; ruff
clean; governance **258 rows, 655 tagged tests, 0 errors, 0 warnings**; scope guard 422 files;
gitleaks clean.

## The two things a reader must know before anything else

**1. The benchmark is velocity-separable, and that is now a reported result rather than a caveat.**
`velocity_ratio_1h_vs_30d` reaches `max(AUC, 1−AUC)` of **0.894 ±0.032**; four more single features
exceed the D-08 ceiling of 0.80. The generator injects fraud as incidents, so recency and rate
features find it. **Owner decision (PB-46): keep the data, keep the ceiling, change what is
reported.** No model metric may be quoted without its single-feature baseline and best trivial rule
beside it, as a margin — a gate on ML-GATE-01 to -04, -07 to -09 and -13, inadmissible on the same
terms as a metric without its scale. Full statement:
`docs/benchmarks/single_feature_baseline.md`.

**2. Implemented is not the same as fed.** All 44 features exist on both paths; **38** can be
computed on this benchmark. Six read reference data nothing produces (PB-44) and two are constant
(PB-47). `fs-features computability` fails when a declaration disagrees with the data in any of six
directions.

## What M4 must start from

- **Train on the features that carry information and report that number with every metric.** Never
  "44 features" where fewer were used.
- **Leave-one-country-out and the novel SIM-swap sub-variant carry the weight the headline AUC no
  longer can**, and the register rows say so. Burstiness is not country-specific, so a velocity
  threshold transfers trivially; a variant absent from training separates a model that learned the
  shape of fraud from one that learned its rate.
- **`corridor_class` is evaluated over two of its four classes** and a model trained here has never
  seen the other two (PB-43, owner decision: keep them, do not broaden the simulated set).
- **PB-40 is due before training** — no device is shared, so `accounts_per_device_7d` is constant
  and `synthetic_identity_score` is a term short.

## Open items

| Item | What |
|---|---|
| **PB-44** | six features have no source data; `round_sum_flag` is the cheapest and pairs with PB-41's regeneration |
| **PB-41** | the committed `realism_report.md` carries no fingerprint until its next regeneration; export refuses to bundle it meanwhile |
| **PB-45** | the exit-criteria table has no checker; "met" is still an author's edit except for E3 and E6 |
| **PB-46** | velocity separability — resolved by reporting gate, carried into M4 |
| **PB-47** | `CONSTANT` shipped; `dormancy_reactivation_flag` measured and **not** constant |
| **PB-36/37/38** | untested DB fallback; no per-account durable table; 8-day refresh under a 30-day feature |
| **PB-25** | the 5M run, still blocked on the default branch being `m0/bootstrap` |
| **E11** | review done, `docs/reviews/M3/milestone-review.md`; both MAJOR findings fixed in place |

## What this session learned that outlives it

Four entries in `docs/research/lab_notebook.md` are worth reading before touching M4:

- **A control measured on the inputs is not a control on the system** — fourth instance. The test
  that finds it: name the object the check ranges over, then read the sentence it justifies; if the
  nouns differ, it does not justify it.
- **A true comment can defend a wrong value.** `_first_seen`'s docstring was correct about complete
  corpora and attached to a function only ever called with truncated ones. Second written claim of
  safety with nothing behind it. The rule: *an aggregate over a window is corpus-derivable; a
  statement about all of history is not.*
- **Fixtures built to be small rather than built to contain the condition** — three instances, and
  then I made a fourth while writing the entry about it. Name the property first, construct for it,
  let size follow.
- **Knowing a failure mode does not immunise against it**, which is why every lesson should carry
  the question *what would enforce this?*, and the ones with no answer are known-weak rather than
  settled.

## Things that will bite whoever picks this up

- **One heavy job at a time.** Five evidence runs were lost this session to five different causes:
  source edited mid-run, `uv sync --reinstall` mid-run, a test mutating shared parameters, memory
  pressure from two runs started in parallel, and an **invented scratch path** that was reaped. An
  evidence run owns its tree, its environment, its machine and its output location — put artefacts
  in the scratchpad the harness names, never a path you compose.
- **Do not commit while a suite runs.** `pre-commit` stashes unstaged changes and regenerates the
  traceability matrix against the reduced tree, so it disagrees and governance fails. Recovery:
  `git stash push -u` the unrelated work, re-render, commit, pop.
- **Clear `.mypy_cache` after adding or removing an `__init__.py`.** A transient one poisoned the
  cache and produced five plausible, unrelated errors in the dataset package.
- **`ml/tests` must not be a package** — a top-level `tests` module collides with `dataset/tests`
  under the workspace's single mypy invocation. Shared fixtures go in `conftest.py`.
- **An exit code from a piped pytest is `tail`'s.** Use `${PIPESTATUS[0]}` or drop the pipe.
- **The commit hook caps the subject at 72 and wraps the body at 72** — rewrap per paragraph, not
  per line, or words end up orphaned.
- **The feature pipeline is O(corpus) per feature without the index.** `CorpusIndex` makes a
  meaningful corpus affordable; `test_the_corpus_index_changes_no_value` asserts it changes nothing.
- Fast subset ~10 s (`ml/tests` + ruff + mypy); dataset suite **30 minutes**; a 1M generation ~35;
  `fs-features auc` at 200k/20k ~20.
