# 0004 — Traceability model and progressive enforcement

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** all rows in `docs/traceability/requirements.yaml`
- **Defects referenced:** D-47, D-48

## Context

Build prompt D.2 requires rows for every FR, the SRS tables 4.1–4.3, 5.3, 5.4, 05B A.3–A.8,
7.1, 7.2, 8.1, 8.2, 9, 10 and every defect, and a CI job that "fails if any M requirement
lacks at least one tagged test". Read literally, that job fails from the first commit until
the last milestone, which contradicts "`main` is always green" (A.3 rule 7).

Non-FR SRS rows have no ID and no MoSCoW priority. The SRS lists 60 functional
requirements (FR-01: 7, FR-02: 10, FR-03: 8, FR-04: 12, FR-05: 7, FR-06: 7, FR-07: 9).
Build prompt rule 5 refers to "B.8" for local fakes; no B.8 exists — the rule is D-51 in B.7.

## Options considered

1. Enforce "every M row has a test" from day one and keep CI red — violates rule 7.
2. Enforce only for rows marked DONE — lets a milestone close with untested Must rows.
3. **Progressive enforcement by milestone**: each row carries the milestone that delivers
   it; once a milestone is recorded as completed, every Must row assigned to it or an
   earlier milestone must be in a final status and, if verified by test, have a tagged test.

## Decision

Option 3.

**IDs.** `FR-xx-xx` as in the SRS. Table rows are numbered in SRS order:
`NFR-PERF` (4.1), `NFR-SEC` (4.2), `NFR-REL` (4.3), `UX-REG` (5.3), `UX-DASH` (5.4),
`MOB-PWA`/`MOB-TOUCH`/`MOB-NET`/`MOB-PERF`/`MOB-COMP`/`MOB-DEV` (05B A.3–A.8),
`ML-DATA` (7.1), `ML-GATE` (7.2), `OPS-CI` (8.1), `OPS-OBS` (8.2), `TEST` (9), `RES` (10),
`D-xx` (defects). Rows are generated from the SRS extraction by
`tools/src/fraudshield_tools/traceability_seed.py`, so titles and criteria are not retyped.
Text copied into 05B from an unrelated project is rewritten per D-47 during seeding, and
the seeder fails if any out-of-scope term survives.

**Priorities.** FR rows use the SRS column. Rows from 4.x, 7.x, 8.x, 9 and 10, and all
defects, are Must: they gate deployment or are binding resolutions. 05B rows take the
priority of the functional requirement they serve (e.g. the MEDIUM timer badge follows
FR-03-02 = S; the behavioural fingerprint follows FR-04-07 = S; background sync and push are
S because D-29/D-41 forbid relying on them).

**Verification method.** Default `test`. Exceptions: `inspection` (D-48, RES-07 citation
file), `external` (NFR-SEC-09 data residency and NFR-SEC-10 penetration test, D-21/D-28;
NFR-PERF-09 99.9% monthly availability, which only a production month can show), `manual`
(MOB-DEV-07 Opera Mini/KaiOS physical-device check).

**Tests are discovered, not listed.** JUnit `@Tag("ID")`, pytest
`@pytest.mark.req("ID", …)`, and Vitest/Playwright titles starting `[ID]` or `[ID, ID]`.
An unknown tag fails the check.

**Milestone state** lives in `docs/traceability/milestones.yaml`. A milestone is added to
`completed` only in the commit that closes it after an approved milestone review.

## Consequences

- `fs-traceability check` rules are listed in the module docstring and unit tested in
  `tools/tests/test_traceability.py`.
- Moving a row to a later milestone is visible in review (diff of `requirements.yaml`) and
  must be justified.
- The matrix Markdown is generated; CI fails if it is stale.
