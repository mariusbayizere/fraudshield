# Paper draft (M11)

LaTeX source of the paper *FraudShield-EAC: What a Synthetic East African Mobile-Money Fraud
Benchmark Can and Cannot Show* (specification Part E.13). Entry point: `main.tex`;
sections in `sections/`; every number in `numbers.tex`.

**Status: DRAFT, not reviewed.** Builds with Tectonic 0.17.0 (checksum-verified release binary, run
outside the repository; it runs BibTeX itself): `tectonic -X compile main.tex`. The build has no
undefined references and no overfull lines. The history of the paper, and the procedure for
replacing a "not yet measured" marker, are in `REVIEW.md`.

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
- `\notyet{Mx}`: a result a later milestone produces, printed in italics as "not yet measured (Mx)".
- No reference is invented. Every entry in `references.bib` was checked against the source itself
  (`reference_audit.md`: what was checked, where, and when); a source that cannot be opened and
  confirmed is removed. No "[reference needed]" placeholder remains.
- Every result is worded as a result on the FraudShield-EAC synthetic benchmark (D-08).

## Figures

`figures/make_figures.py` writes the three figures (`fig-*.tex`, pgfplots, drawn by LaTeX) from
the values in `numbers.tex` and nothing else, so a figure cannot show a number the audit has not
checked. After changing `numbers.tex`, run it again; `--check` fails if a figure is out of date.

## Checks before a commit

    python3 docs/research/paper/audit_numbers.py        # 0 problems
    python3 docs/research/paper/check_numbers.py        # 0 direct numbers
    python3 docs/research/paper/figures/make_figures.py --check
    tectonic -X compile --keep-logs main.tex            # no undefined references, no overfull lines

## Ownership

`contributions.md` belongs to the M4 agent and is read, not edited, here. Everything else in this
directory belongs to M11. Notes for other milestones: `docs/parallel/M11_updates.md`.
