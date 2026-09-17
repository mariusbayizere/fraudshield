# 0001 — Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** all
- **Defects referenced:** D-48

## Context

The build prompt (A.3 rule 1) requires every new ambiguity to be resolved without asking
the author, using a fixed priority order, and recorded. Reviewers of this work (doctoral
committees, bank security teams) look first for internal contradictions and unsupported
choices, so the reasoning behind deviations from the SRS has to be retrievable.

## Options considered

1. **Decisions in commit messages and PR descriptions only** — cheap, but scattered and
   not linked from traceability rows.
2. **Lightweight ADRs in `docs/adr/`** (Nygard format, numbered, immutable once accepted,
   superseded rather than edited).

## Decision

Option 2. ADRs use `docs/adr/0000-template.md`, list the requirement and defect IDs they
affect, and are referenced from the `deviations` field of traceability rows. An accepted
ADR is never rewritten; a later ADR supersedes it.

Calendar dates in the SRS roadmap (section 11) are ignored (D-48); work is ordered by the
gated milestones M0–M12 of build prompt D.3.

## Consequences

- Deviations in traceability rows must be existing `docs/adr/NNNN-*.md` files, and
  `DONE_WITH_DEVIATION` requires at least one; `fs-traceability check` enforces both (tests in
  `tools/tests/test_traceability.py`, strengthened during the M0 review).
- D-48 is verified by inspection: requirement rows carry milestones, not weeks.
