# Paper draft (M11)

LaTeX draft of the FraudShield paper, per build prompt Part E.13. Entry point: `main.tex`;
sections in `sections/`; every number in `numbers.tex`.

**Status: DRAFT, not reviewed, not compiled.** No TeX toolchain is installed on the development
laptop; the sources are checked structurally (below), not rendered.

## Rules every number obeys

- The claims register gate (`docs/research/claims_register.md`): a number is **MEASURED** (commit
  and run, raw output under `docs/benchmarks/`) or **SOURCED** (a citation to a document read in
  full). Nothing else is stated.
- Sections never type a result. Each number is a macro in `numbers.tex`, whose comment names the
  file, commit and draw it comes from. `python3 check_numbers.py` fails on a decimal, percentage
  or grouped integer typed into a section.
- `\meas{}`: M2/M3 results on `main`.
  `\prov{}`: M4 results from `m4/generalisation` pinned at `a6b0c9e`, printed with a dagger and
  **PROVISIONAL** until `m4-complete` exists; each is then re-verified against the tag.
  `\notyet{Mx}`: a result a later milestone produces, printed as "NOT YET MEASURED -- Mx".
- No reference is invented. `\cite{TODO-verify}` marks every place one is needed;
  `references.bib` stays empty until the author has read and verified each source.
- Every result is worded as a result on the FraudShield-EAC synthetic benchmark (D-08).

## Ownership

`contributions.md` belongs to the M4 agent and is read, not edited, here. Everything else in this
directory belongs to M11. Notes for other milestones: `docs/parallel/M11_updates.md`.
