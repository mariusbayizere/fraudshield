# Paper draft (M11)

LaTeX source of the paper *FraudShield-EAC: What a Synthetic East African Mobile-Money Fraud
Benchmark Can and Cannot Show* (specification Part E.13). Entry point: `main.tex`;
sections in `sections/`; every number in `numbers.tex`.

**Status: DRAFT, not reviewed.** Builds with Tectonic 0.17.0 (checksum-verified release binary, run
outside the repository; it runs BibTeX itself): `tectonic -X compile main.tex`. The last build
(2026-09-22) had no undefined references and no overfull lines; the draft has not been compiled since
2026-10-19's changes, because no TeX engine is installed on the machine that made them. The history of the paper, and the procedure for
replacing a "not yet measured" marker, are in `REVIEW.md`.

## Rules every number and claim obeys

The owner's standing rule (2026-10-19), above everything else: every number and every claim is
traceable to evidence in the repository; nothing is written because it sounds right or because an
earlier document said it. Where evidence and a document disagree, the evidence wins and the document
is corrected. A claim that rests on inference says so in its own sentence. A claim that cannot be
supported is removed or stated as not established, never softened until it survives.

- The claims register gate (`docs/research/claims_register.md`): a number is **MEASURED** (commit
  and run, raw output under `docs/benchmarks/`) or **SOURCED** (a citation to a document read in
  full). Nothing else is stated.
- Sections never type a number. Each number is a macro in `numbers.tex`, audited against the
  file it comes from at a named commit. `python3 check_numbers.py` fails on a decimal, a percentage,
  a grouped or bare integer, or a number written as a word (`eight`, `thirteenth`, `fivefold`)
  typed into a section; identifiers, dates, citation locators and layout are stripped first, and
  `one`, `first` and `second` are exempt. Counts that are not results (the number of items in a
  list) are written without a number instead.
- Generated tables are not typed: Appendix B's feature definitions are written from the registry at
  `m4-complete` by `appendix_features.py` (`--check` fails if out of date), and Appendix D's evidence
  index is written by `audit_numbers.py` from the files it read.
- `\meas{}`: measured results (M2 to M4, tag `m4-complete`, `72e7790`; results that postdate the tag,
  such as the learning curve, name the commit they are read at in `numbers.tex`).
  `\prov{}`: a result from a milestone not yet tagged, printed with a dagger. **None is in use**:
  every M4 value was re-verified at `m4-complete` and moved to `\meas{}` on 2026-09-22.
- `python3 audit_numbers.py` checks every macro against its evidence file at the tag, or at the
  later ref its source names, and writes
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
    python3 docs/research/paper/appendix_features.py --check
    tectonic -X compile --keep-logs main.tex            # no undefined references, no overfull lines

## Ownership

`contributions.md` belongs to the M4 agent and is read, not edited, here. Everything else in this
directory belongs to M11. Notes for other milestones: `docs/parallel/M11_updates.md`.
