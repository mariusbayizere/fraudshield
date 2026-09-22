# Paper draft (M11)

LaTeX draft of the FraudShield paper, per build prompt Part E.13. Entry point: `main.tex`;
sections in `sections/`; every number in `numbers.tex`.

**Status: DRAFT, not reviewed.** Compiles to an 18-page PDF with Tectonic 0.17.0 (checksum-verified
release binary, run outside the repository): `tectonic -X compile main.tex`. The only warnings are
the intentional `TODO-verify` citations, which render as `[?]` until verified references exist.

## Rules every number obeys

- The claims register gate (`docs/research/claims_register.md`): a number is **MEASURED** (commit
  and run, raw output under `docs/benchmarks/`) or **SOURCED** (a citation to a document read in
  full). Nothing else is stated.
- Sections never type a result. Each number is a macro in `numbers.tex`, whose comment names the
  file, commit and draw it comes from. `python3 check_numbers.py` fails on a decimal, percentage
  or grouped integer typed into a section.
- `\meas{}`: measured results (M2 to M4, tag `m4-complete`, `72e7790`).
  `\prov{}`: a result from a milestone not yet tagged, printed with a dagger. **None is in use**:
  every M4 value was re-verified at `m4-complete` and moved to `\meas{}` on 2026-09-22.
- `python3 audit_numbers.py` checks every macro against its evidence file at the tag and writes
  `number_audit.md`; it must report zero problems before a commit.
  `\notyet{Mx}`: a result a later milestone produces, printed as "NOT YET MEASURED -- Mx".
- No reference is invented. `\cite{TODO-verify}` marks every place one is needed;
  `references.bib` stays empty until the author has read and verified each source.
- Every result is worded as a result on the FraudShield-EAC synthetic benchmark (D-08).

## Ownership

`contributions.md` belongs to the M4 agent and is read, not edited, here. Everything else in this
directory belongs to M11. Notes for other milestones: `docs/parallel/M11_updates.md`.
