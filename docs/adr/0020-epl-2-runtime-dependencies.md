# 0020 — EPL-2.0 runtime dependencies

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** OPS-CI-04 (licence policy), build prompt F.3
- **Amends:** ADR 0009 (runtime allow-list)

## Context

ADR 0009 allows only permissive licences for anything that ships and says a weak-copyleft runtime
dependency needs a new ADR. The first shipped Java module (`backend/persistence`, M1) brings Spring
Boot's standard runtime:

| Dependency | Declared licence | Verified from |
|---|---|---|
| `ch.qos.logback:logback-classic` and `logback-core` 1.5.38 | EPL-2.0 **or** LGPL-2.1-only, licensee's choice | `LICENSE.txt` at tag `v_1.5.38` |
| `jakarta.annotation:jakarta.annotation-api` 3.0.0 | EPL-2.0 **or** GPL-2.0-only with Classpath-exception-2.0 | the jar's `META-INF/NOTICE.md` (SPDX line) |

Every Spring Boot service in later milestones depends on the same Jakarta APIs, so replacing them
module by module is not practical. Replacing logback with Log4j 2 would only move the question.

## Decision

1. **EPL-2.0 is allowed at runtime for unmodified third-party binaries.**
   - EPL-2.0 is weak, file-level copyleft. Its obligations apply to the EPL-licensed files
     themselves, not to FraudShield code that links against them.
   - This is the Apache Software Foundation's "Category B" position: such binaries may ship in an
     Apache-2.0 distribution when they are not modified and their licence and source location are
     noted.
   - EPL-1.0, MPL-2.0 and every LGPL variant stay dev-only. GPL, AGPL, SSPL, BUSL and Elastic stay
     denied.
2. **Dual-licensed packages are recorded as `OR` expressions** in the version-pinned
   `EXCEPTIONS` of `fraudshield_tools.licences`, because Maven lists every licence as a separate
   `<license>` entry, which the tool conservatively reads as "all apply". Each exception names the
   file it was verified from. An upgrade removes the exception and forces a new review.
3. **Licence names the tool did not recognise** are added to its patterns:
   - "The MIT License" maps to MIT.
   - "Eclipse Distribution License 1.0" maps to BSD-3-Clause, because EDL-1.0 is the BSD 3-Clause
     text (SPDX has no separate identifier for it).
4. **Obligations when images are distributed (M9):**
   - Do not modify EPL-licensed jars.
   - Keep their licence files, which stay inside the jars.
   - List them with their source locations in the image's third-party notice (backlog PB-6).

## Consequences

- `make licences` passes with 39 runtime dependencies (measured 2026-09-17), including the EPL-2.0
  components above.
- Any modification of an EPL-2.0 component, or a runtime dependency under any other weak-copyleft
  licence, needs a new ADR.
- Tests: `test_evaluate`, which checks that EPL-2.0 is allowed at runtime and that EPL-1.0, MPL-2.0 and
  LGPL are not; `test_dual_licensed_runtime_exceptions_rely_on_the_epl_option`, which checks that an
  upgraded logback falls back to "all apply" and fails.
