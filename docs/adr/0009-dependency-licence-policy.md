# 0009 — Dependency licence policy and inventory

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** OPS-CI-01, OPS-CI-04, RES-06
- **Defects referenced:** D-17 (deviation), plus build prompt E.12

## Context

Build prompt F.3 forbids "any non-Apache/MIT/BSD-compatible dependency without an ADR". The M0
review (finding 14) noted that no licence inventory existed, that JUnit is EPL-2.0 and that the
Maven quality plugins (Checkstyle, SpotBugs, FindSecBugs) are LGPL, none of which had been
assessed. The source code is Apache-2.0 and service images will be distributed.

The same finding flagged jqwik 1.10.1: on every test run it prints a notice addressed to
automated agents stating that they must not use the library. The build process for this
repository includes automated agents.

## Options considered

1. **Permissive-only everywhere** — rejects JUnit (EPL-2.0), the de facto JVM test framework,
   and the LGPL quality plugins, for no distribution benefit: none of them ship.
2. **Scope-based policy, enforced by a generated inventory** — permissive licences for anything
   that ships; weak copyleft additionally allowed for build/test-only dependencies.
3. **Manual review only** — not reproducible; drifts with every dependency update.

## Decision

Option 2, enforced by `uv run fs-licences` (`make licences`, CI job `licences`), which inventories
Maven (license-maven-plugin 2.7.1, per scope), npm (`pnpm licenses list`, prod vs all) and
Python (`importlib.metadata`, with runtime = non-dev dependencies of `fraudshield-ml`), and
uploads `build/licence-inventory.json` as a CI artifact.

| Scope | Definition | Allowed (SPDX) |
|---|---|---|
| runtime | Maven compile/runtime; npm `dependencies`; `fraudshield-ml` non-dev dependencies | Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, PSF-2.0, Python-2.0, CC0-1.0, BlueOak-1.0.0, Zlib, Unlicense |
| dev | build, test and developer tooling, never distributed | the runtime list plus EPL-1.0, EPL-2.0, MPL-2.0, LGPL-2.1-or-later, LGPL-3.0-or-later, CC-BY-4.0 |

- Dual-licensed packages pass if any option is allowed. A licence that cannot be identified
  fails the check; per-package exceptions require an entry with a reason in
  `fraudshield_tools.licences.EXCEPTIONS` and review.
- The Maven report parser verifies the dependency count the report declares, so a parsing
  failure cannot pass silently (it did once during development: 0 of 18 parsed).
- **Build plugins** are not dependencies of any artifact and are assessed here: Checkstyle
  (LGPL-2.1-or-later), SpotBugs and FindSecBugs (LGPL-2.1 / LGPL-3.0), JaCoCo (EPL-2.0), Spotless
  and google-java-format (Apache-2.0), license-maven-plugin (LGPL-3.0). All run only at build time
  and are acceptable under the dev rules.
- **jqwik is removed.** A dependency whose maintainer states usage terms that conflict with how
  this repository is built is not a stable foundation, regardless of the enforceability of those
  terms. Property-based tests in Java use JUnit 5 parameterized tests over cases generated from a
  fixed-seed `SplittableRandom` plus explicit edge cases (build prompt E.12 names jqwik; this is the
  recorded deviation). Hypothesis remains the Python property-testing library.

## Amendment 2026-09-19 — the first runtime dependency of `fraudshield-ml`

`fraudshield-ml` has had `dependencies = []` since M0, so the runtime scope this ADR defines has
been **empty the whole time it has been enforced**. `geo_cell_fraud_rate_30d` ends that: Part E.2
specifies the feature on an "H3 resolution 6 cell", and computing an H3 index from a coordinate
means taking `h3` as a runtime dependency of the ML package.

**Recorded because it was nearly not.** The feature registry is declarative and computes nothing, so
registering the feature added no import and triggered no licence check — the commitment to H3 lived
only in a prose definition string. A dependency decision that reaches the repository as prose is
exactly what this ADR exists to stop, and the inventory would not have caught it until the first
implementation commit.

- **Expected licence:** Apache-2.0 (both `h3-py` and the underlying H3 C library), which the runtime
  scope allows outright, so no exception entry is needed.
- **Not taken on trust.** The licence above is the expectation, not the record. `uv run fs-licences`
  resolves it from package metadata and **fails the build on a licence it cannot identify**; the
  inventory it produces is the evidence, and it runs when the dependency lands rather than now.
- **The alternative was rejected on sight.** Reimplementing H3's icosahedral projection to avoid a
  permissively licensed dependency would trade a one-line inventory row for a geometry
  implementation nothing else validates — and `geo_cell_fraud_rate_30d` is a label-derived feature
  already under the D-08 AUC ceiling, so a home-made cell index would put a bespoke spatial
  binning inside the feature most able to leak.

**Consequence for the licence check:** this is the first time the runtime scope has been non-empty,
so `fs-licences` runtime path has never run against real input. The inventory's Python branch is
therefore itself unverified at the moment the first dependency arrives, and the Maven parser's
"0 of 18 parsed" precedent above is the reason not to assume it works. Assert the runtime scope
contains `h3` when the dependency lands, rather than reading a passing check as confirmation.

### Outcome, same day: the prediction held and the check failed on first contact

`h3==4.5.0` was added and `fs-licences` **failed immediately**:

```
ERROR python:h3@4.5.0 (runtime): unidentified licence ('Apache Software License',)
```

Not a false alarm — the correct behaviour. h3 publishes **no `License-Expression`**; its `License`
metadata field carries the full licence text rather than an identifier, and its only structured
signal is the classifier `License :: OSI Approved :: Apache Software License`, which is **ambiguous
between Apache 1.0, 1.1 and 2.0**. The tool refuses to guess, exactly as this ADR requires.

Resolved by an `EXCEPTIONS` entry naming the verification, which is the mechanism this ADR already
defines and which two packages (`jsonschema-path`, `pathable`) already use for the same classifier.
Verified 2026-09-19: the METADATA `License` field and `dist-info/licenses/LICENSE` both carry the
text headed "Apache License, Version 2.0". **Deliberately not fixed by adding an
`Apache Software License` → `Apache-2.0` normaliser**: that would silently accept Apache 1.1 from
any future package declaring the same classifier, trading a per-package verification for a pattern —
the failure shape this repository refuses in its gitleaks configuration for the same reason.

**The assertion this ADR demanded, performed:** `build/licence-inventory.json` contains
`{"ecosystem": "python", "name": "h3", "version": "4.5.0", "scope": "runtime", ...}`. The check now
passes **because a dependency was assessed**, not because the scope was empty.

**The general point, recorded because it outlives this dependency: a check that has only ever run
against an empty scope is untested.** From M0 until 2026-09-19 the Python runtime scope of
`fs-licences` was empty, and the check passed on every commit for the whole of M0, M1 and M2. Those
passes carried no information — an empty input satisfies almost any predicate, and a green check
over nothing is indistinguishable from a green check over something. The very first real input
failed it. The failure was correct and the tool was right, but its record of passing said nothing
until that moment, and nobody could have known which from the CI output alone.

This is the same shape as the Maven report parser's "0 of 18 parsed" incident recorded above, and
the same shape as the band floor and the parameter digest: **a protection whose reach is narrower
than its appearance.** Wherever a gate's scope can be empty, the gate should report the size of
what it checked, so that "passed" and "had nothing to check" are distinguishable without reading
the inventory.

## Deviation from D-17 and E.12 (amended during the M0 re-review, finding R-5)

Binding resolution D-17 says decision-engine tests use "JUnit 5 (plus jqwik property tests)", and
E.12 lists jqwik for property-based testing. Part B resolutions rank second in the build prompt's
precedence (A.4); **legal and safety constraints rank first**. The notice jqwik 1.10.1 prints on
every run is a statement of usage terms by its maintainer that excludes the way this repository is
built; relying on a dependency against its maintainer's stated terms is a legal-risk question, so
A.4 item 1 takes precedence over D-17's choice of library. The *intent* of D-17 and E.12 —
generative property testing of the decision engine — is kept:

- The M0 `MoneyTest` cases are deterministic examples drawn from a fixed-seed generator plus edge
  cases. They are **not** property-based tests in the full sense (no fresh inputs per run, no
  shrinking), and are not claimed as such.
- **M6 plan (decision engine):** a small JUnit 5 extension provides generative properties: each
  run draws a fresh random seed, logs it, and on failure reports the seed and the failing input so
  the case is reproducible with `-Dfs.property.seed=<seed>`; failing inputs are added as fixed
  regression cases. Properties cover tier monotonicity in score, half-open threshold boundaries,
  custom rules never lowering an ML HIGH, and idempotency. A maintained, permissively licensed
  JVM property-testing library may replace the extension through a new ADR if one is evaluated.
- When the M6 rows close, the D-17 traceability row records this ADR as a deviation
  (`DONE_WITH_DEVIATION`).

## Boundary cases of the policy (amended during the M0 re-review, finding R-4)

| Case | Rule | Test |
|---|---|---|
| SPDX `A OR B` | allowed if any branch is allowed | `test_evaluate` |
| SPDX `A AND B`, and several Maven `<license>` entries | every part must be allowed | `test_evaluate` |
| `X WITH exception` | only exceptions that do not change the base licence (`LLVM-exception`); otherwise unidentified, or not allowed if the base is not allowed | `test_evaluate` |
| Python metadata | most authoritative field only: `License-Expression`, else classifiers (dual licences), else the first line of `License`; fields are never unioned | `test_collectors_classify_scope` |
| Classifier list with an unrecognised member | unidentified, so an unreadable licence cannot hide behind a permissive one | `test_classifier_lists_are_dual_licences_but_unknown_members_block` |
| GPL, AGPL, SSPL, BUSL | recognised and **not allowed** in any scope | `test_normalise`, `test_evaluate` |
| LGPL-2.1/3.0 (only / or-later) | allowed for dev only | `test_evaluate` |
| Bare "BSD" / "BSD License", "UNLICENSED", "SEE LICENSE IN …" | unidentified; needs a verified exception | `test_normalise` |
| Same package in runtime and dev | classified runtime (stricter list) | `test_collectors_classify_scope` |
| Exceptions | keyed by ecosystem, name **and version**; an upgrade removes the exception and forces review | `test_exceptions_are_version_pinned` |

Current exceptions, verified from shipped licence files on 2026-09-17: `nodeenv@1.10.0`
(BSD-3-Clause), `com.tngtech.archunit:archunit@1.5.0` (Apache-2.0 AND BSD-3-Clause for shaded
ASM), and, added with the M1 contract tests, `jsonschema-path@0.5.0` and `pathable@0.6.0`
(Apache-2.0) and `openapi-schema-validator@0.9.0` (BSD-3-Clause). ADR 0020 (M1 database module) allows unmodified
EPL-2.0 binaries at runtime and adds `logback-classic` and `logback-core@1.5.38` (EPL-2.0 OR
LGPL-2.1-only) and `jakarta.annotation-api@3.0.0` (EPL-2.0 OR GPL-2.0-only WITH
Classpath-exception-2.0).

## Consequences

- Inventory at M0 (measured 2026-09-17): 187 dependencies — 144 npm, 25 Python, 18 Maven — all
  dev scope, 0 violations. The first runtime dependencies arrive in M1 and M3 and will be held to
  the runtime list.
- Adding a runtime dependency under a weak-copyleft licence (for example an LGPL JDBC driver)
  requires a new ADR.
- Property tests in Java are deterministic by construction; shrinking of failing cases is not
  available and is compensated by explicit boundary cases.

## Amendment 2026-09-22 — `lightgbm`, and what its transitive dependencies dragged in

C-6 claims "ensemble reduces variance 12% vs single model" and cannot be measured against a single
booster. `fraudshield-ml` already carries `xgboost`; the SRS's own production model is
0.55·XGBoost + 0.45·LightGBM (E.4), so `lightgbm==4.7.0` is the second half of a model that was
already designed rather than a dependency taken on for one measurement.

**`lightgbm` and its own transitive dependency `narwhals` both declare `License-Expression: MIT`**
— a clean SPDX field, no exception needed, the check resolves them on the spot.

**What was not clean: `xgboost`'s own transitive dependencies, discovered only now.** `xgboost`
has been a runtime dependency since M3's pipeline smoke test, and `fs-licences` runs in the `ci`
Makefile target, not the commit hook — so, like the empty-scope gap this ADR's first amendment
found, this check had not run against `xgboost`'s full dependency closure until asked for
directly. `uv sync --all-packages` (needed to make `lightgbm` importable at all — a package-scoped
`uv sync` left it unresolved) surfaced two packages `xgboost` had been pulling in the whole time:

```
ERROR python:scipy@1.18.1 (runtime): unidentified licence ('BSD License',)
ERROR python:nvidia-nccl-cu12@2.31.2 (runtime): unidentified licence ('LicenseRef-NVIDIA-Proprietary',)
ERROR python:xgboost@3.0.2 (runtime): unidentified licence ('Apache Software License',)
```

Three failures, one mechanical and two worth reading in full.

**`xgboost` and `scipy`** are the `h3` shape exactly: an ambiguous classifier, no
`License-Expression`, resolved by reading the bundled text. `xgboost` ships no `LICENSE` in its
wheel at all, so the source repository at the installed tag (`github.com/dmlc/xgboost`, `v3.0.2`,
`LICENSE`) was read instead, and it is the Apache License, Version 2.0 in full. `scipy`'s
`dist-info/licenses/LICENSE.txt` is headed "Copyright (c) 2001-2002 Enthought, Inc. 2003, SciPy
Developers" with the 3-clause BSD disclaimer, confirmed against `github.com/scipy/scipy` at the
installed tag `v1.18.1`. Both entered `EXCEPTIONS` the same way `h3` did.

**`nvidia-nccl-cu12` is a different shape, and worth stating plainly: its own declared licence
field is wrong.** `xgboost`'s wheel lists it as a plain (non-extra) dependency on
`platform_system == 'Linux' and platform_machine != 'aarch64'`, for a GPU collective-communication
code path this project never invokes — `ldd` on the installed `libxgboost.so` shows it links no
NVIDIA library, and nothing in this repository sets `device='cuda'`. The package's own
`License-Expression` metadata field says `LicenseRef-NVIDIA-Proprietary`. Read the actual bundled
file instead — `dist-info/licenses/License.txt` — and it is the 3-clause BSD licence text for NCCL
(NVIDIA CORPORATION / Lawrence Berkeley National Laboratory / U.S. Department of Energy, under DOE
subcontract 7078610), which matches the upstream NCCL project's own stated position
(`github.com/NVIDIA/nccl`, `LICENSE.txt`: "parts of the project retain their original BSD
license"). **The wheel's metadata claims a licence more restrictive than the text it ships**, which
is the opposite direction every other ambiguity in this table has run in — every prior exception
resolved a vague or missing field, not a field asserting something the bundled text contradicts.
Recorded in `EXCEPTIONS` as `BSD-3-Clause`, on the text, not the label — the same "read the file,
not the classifier" rule this ADR has used since `h3`, applied to a case where trusting the label
would have been not merely imprecise but wrong.

**Not investigated further: whether NVIDIA intends `License-Expression` to describe some other
component of the wheel** (a CUDA driver stub, a proprietary header) that this reading missed. The
file actually bundled and installed under `dist-info/licenses/` is what a licence check has to
answer for, and it is the BSD text. If a future NVIDIA release changes what ships under that path,
the pinned exception (`nvidia-nccl-cu12@2.31.2`) stops applying at the next version bump and the
check fails again, exactly as every other pinned exception in this table is designed to.

**The general point repeats, one version further.** A package's own declared licence field is not
self-verifying, and here it was actively wrong rather than merely absent — the same lesson this
project has learned about hand-written commit provenance (PB-52/PB-53) and about a benchmark's
column-level control being quoted as a claim about its features (D-08's family). The fix in every
case is the same: read the artefact the check is actually about, not a label attached to it.

## Amendment 2026-09-22 — `scikit-learn`, for four things the SRS specifies

The M4 milestone review (`docs/reviews/M4/milestone-review.md`, M4-2 and M4-1) found D-06's
Isolation Forest, D-05's isotonic calibration and E.5's logistic-regression and random-forest
baselines all unbuilt. Each is a scikit-learn estimator in the SRS's own description, and the
Isolation Forest is also what E.4's ONNX export expects. `scikit-learn==1.8.0` is taken on once
for all four; the boosted models stay on their native booster APIs, so `smoke.py`'s reason for
avoiding the scikit-learn wrappers still holds for them.

It brings three transitive packages. `scikit-learn`, `joblib` and `threadpoolctl` declare
`License-Expression: BSD-3-Clause` and resolve without an exception; each bundled licence file
was read and is the BSD 3-clause text.

**`cloudpickle` (via `joblib`) needed one**, for a mechanical reason rather than a doubtful one: its
metadata carries only the classifier `BSD License` and the old-style `License: BSD-3-Clause` field,
which the policy does not read. Its `dist-info/licenses/LICENSE` is the full 3-clause text
(Cloudpickle contributors, Regents of the University of California, PiCloud). It was read from the
installed wheel, and unlike the `scipy` and `xgboost` entries it was not checked against the
upstream source; the exception says so.

Run in a worktree without `frontend/node_modules`, `fs-licences` also reports every npm package as
`Unknown`: the check reads licences from the installed packages, so an uninstalled front-end is
not a clean result. CI installs it, and CI's result is the one that counts.

## Amendment 2026-09-22 — the ONNX export chain

E.4 requires ONNX exports of the boosters with a parity test, and the M4 review found none (M4-4).
`onnx==1.23.0`, `onnxruntime==1.30.0` and `onnxmltools==1.16.0` are taken on for that alone: the
converter, the format, and the runtime the parity test and M5's scoring service run on. They bring
`skl2onnx`, `flatbuffers` and `ml-dtypes`.

Four resolve from their SPDX fields. Two needed exceptions, both for a bare classifier:

- **`skl2onnx`** bundles the full Apache 2.0 text and a `NOTICE` crediting Microsoft. Apache 2.0
  §4(d) requires that notice to travel with any redistribution, which matters at M9 when images are
  built, not here.
- **`flatbuffers`** ships no licence file in its wheel, the `xgboost` shape. Rather than the
  upstream repository, the installed artefact itself was read: all ten of its modules carry
  Google's Apache 2.0 header.
