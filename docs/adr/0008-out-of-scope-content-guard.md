# 0008 — Out-of-scope content guard (D-47)

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** MOB-PWA-08, MOB-TOUCH-03, MOB-TOUCH-04, MOB-TOUCH-08, MOB-DEV-01
- **Defects referenced:** D-47

## Context

SRS section 05B was copied from the author's separate health project (KinyaMed / HealthGuard AI):
it mentions patients, triage, clinics, hospitals, doctors, consultations, symptom submission and
QR scanning. Build prompt A.3 rule 9 and D-47 forbid importing any of that code, naming or concept
into FraudShield, and define the FraudShield equivalents (alert card, alert feed, CONFIRM FRAUD
haptic, bank operations workstation).

A rule that relies on attention alone will eventually be broken by copy-paste. The first
implementation of the guard avoided failures by rephrasing sentences that legitimately discuss the
defect, which hides exactly the context a reviewer needs (repository owner's review finding 5).

## Options considered

1. **Code review only** — no automated protection.
2. **Guard with rephrasing** — accurate text about D-47 has to be reworded to pass.
3. **Guard with an explicit, reasoned allowlist and a line pragma** — accurate text stays accurate;
   every exemption is visible and reviewed.

## Decision

Option 3, implemented by `tools/src/fraudshield_tools/scope_guard.py` (`fs-scope-guard`; CI job
`governance`; pre-commit):

- Terms (`scope_terms.py`): the two project names and patient, triage, clinic, clinical, hospital,
  health centre/center, symptom, doctor, consultation — matched case-insensitively at letter
  boundaries, both as written and with camelCase split (so `PatientCard`, `triage_queue` and the
  compound project names are all caught), in file contents and in file paths.
- File allowlist, each entry with its reason in code: the verbatim SRS and build prompt
  (`docs/srs/`, `docs/prompts/`), the generated traceability files, this ADR, review records
  (`docs/reviews/`, which quote findings verbatim), the term list, and the seeder's substitution
  table.
- The generated traceability YAML is allowlisted only because two other checks bound it: the seeder
  asserts that D-47 is the only row carrying these terms, and `fs-traceability-seed --check` proves
  the committed YAML equals the generated output.
- Line pragma `scope-guard: allow D-47` exempts a single line that discusses the defect (for
  example a defence question). A pragma without the defect ID does not exempt anything.
- The D-47 traceability row keeps its verbatim title.

## Consequences

- New exemptions require a code change to the allowlist with a reason, visible in review.
- Ordinary words that collide with the list (for example "clinical" in a statistics context) must
  use the pragma; this is accepted friction for a domain where the words have no legitimate use.
- Tests: `tools/tests/test_scope_and_registers.py` (repository clean; detection in content, paths,
  identifiers and compound names; allowlist and pragma behaviour; seeded rows).
