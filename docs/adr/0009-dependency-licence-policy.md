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
(BSD-3-Clause) and `com.tngtech.archunit:archunit@1.5.0` (Apache-2.0 AND BSD-3-Clause for shaded
ASM).

## Consequences

- Inventory at M0 (measured 2026-09-17): 187 dependencies — 144 npm, 25 Python, 18 Maven — all
  dev scope, 0 violations. The first runtime dependencies arrive in M1 and M3 and will be held to
  the runtime list.
- Adding a runtime dependency under a weak-copyleft licence (for example an LGPL JDBC driver)
  requires a new ADR.
- Property tests in Java are deterministic by construction; shrinking of failing cases is not
  available and is compensated by explicit boundary cases.
