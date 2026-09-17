# 0009 — Dependency licence policy and inventory

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** OPS-CI-01, OPS-CI-04, RES-06
- **Defects referenced:** —

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

## Consequences

- Inventory at M0 (measured 2026-09-17): 187 dependencies — 144 npm, 25 Python, 18 Maven — all
  dev scope, 0 violations. The first runtime dependencies arrive in M1 and M3 and will be held to
  the runtime list.
- Adding a runtime dependency under a weak-copyleft licence (for example an LGPL JDBC driver)
  requires a new ADR.
- Property tests in Java are deterministic by construction; shrinking of failing cases is not
  available and is compensated by explicit boundary cases.
