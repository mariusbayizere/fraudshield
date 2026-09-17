# M2 deferred-work check

Owner direction (2026-09-17): at the start of every milestone, list the work deferred into it,
estimate its size, and propose a split in an ADR before writing code if the milestone is overloaded.

## Deferred into M2

| Item | Source | What remains | Size |
|---|---|---|---|
| GOV-5 | M0 delta review DR-7 | Devcontainer workflow triggers for `pyproject.toml`, `tools/**`, `package.json`; the default-branch part is an owner action (GOV-9) | S (≤ 1 h) |
| GOV-6 | M0 delta review DR-9 | Scope-guard pragma inside fenced Markdown code blocks: ignore it, or document it in ADR 0008 | S |
| GOV-7 | M0 re-review R-8 | Reject tool-attribution trailers (`Assisted-by:`, `Co-developed-by:`, "Made with …") in `fs-commit-msg`, with tests | S |
| GOV-10 | M0 milestone review F-4 | Automated CI evidence verification checks the named job ran, not only the run conclusion | M (½ day) |
| PB-24 | M1 delta re-check | Migration guard: compare with `git hash-object --path`, reject out-of-order versions, retag its tests | S |

ADR 0021 moved nothing into M2: its rows went to M6, M7 and M9.

Deferred total: about 1–1.5 days of governance and tooling work, independent of the generator.

## M2's own scope

The traceability rows with milestone M2 are ML-DATA-01 … 08, RES-01, RES-02, D-07 and D-08, plus
Part E.3 and the owner's M2 directions:

- parameter provenance files;
- a streaming generator under 2 GB peak RSS;
- byte-identical output across chunk sizes;
- executable anti-leakage and shortcut checks;
- eight scenario design notes.

Rough size: 8–12 working days.

## Assessment

M2 is **not overloaded by deferred work**. The deferred items are about 10% of the milestone, and
none blocks or depends on the generator. **No split is proposed.** The deferred items are scheduled
after the generator core, so a slip in them cannot delay the gate.

Three row criteria cannot be met by the generator alone as written, and are recorded now rather than at the gate:

- **ML-DATA-08 / RES-01 (publication).** Publishing on the HuggingFace Hub and archiving on Zenodo
  with a DOI need the owner's accounts. M2 delivers the release artefacts (Parquet, CSV export,
  checksums, CC BY 4.0 licence, datasheet); publishing is an owner action.
- **ML-DATA-07 (44 features ≥ 98 % computable).** The 44 features are built in M3. M2 measures
  completeness of the raw fields each feature needs and reports structural NaNs separately (D-04).
  The M3 gate re-measures it on the features.
- **ML-DATA-06 ("final 1.5 months") conflicts with D-07,** which defines the test set as the most
  recent contiguous period of about 520K rows, after a 7-day embargo. Its span is measured and
  reported. D-07 is binding, so the row closes as DONE_WITH_DEVIATION, citing the generator ADR.
