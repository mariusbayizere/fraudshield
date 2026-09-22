# M11 updates (branch `m11/paper`)

Written by the M11 agent for the owner and the M4 agent. M11 owns `docs/research/paper/` except
`contributions.md` (M4's, read only). Everything else under `docs/` is read, not edited, from this
branch; what it needs is proposed here.

**Status: first complete draft, not reviewed.** `docs/research/paper/main.tex`, 13 sections,
compiles to 18 pages with Tectonic 0.17.0 (run outside the repository). Every number is a macro in
`docs/research/paper/numbers.tex` naming its evidence file, commit and data draw;
`check_numbers.py` finds no number typed directly into a section; every macro value was checked
verbatim against its raw evidence file on 2026-09-22.

## 1. Sources and pinning

| Results | Source | Marked |
|---|---|---|
| M2, M3 | `main` at `f8885d6` (`m3-complete`) | unmarked (`\meas`) |
| M4 | `m4/generalisation` pinned at `a6b0c9e` | dagger, PROVISIONAL (`\prov`) |
| Specification values | build prompt Part B, SRS 7.2 | `\srcd` |
| Later milestones | none | `NOT YET MEASURED -- Mx` |

## 2. Discrepancies found in the sources (for the M4 agent and the owner)

The paper resolves each as stated; the source documents are not edited from this branch.

1. **Reversal-scam figures in the M11 brief are the superseded run.** The brief gives 6.1%
   [2.4%, 14.6%] vs 94.2% [92.4%, 95.6%]; those are `m4_battery_pb61.txt` (evidence commit
   `dbda585`), made with the random-fold encoding E1 forbids. `contributions.md` states the
   restated run: **9.1% (6/66) [4.2%, 18.4%] vs 94.4% [92.7%, 95.8%]**
   (`m4_battery_d8083dbc_e1.txt`, `d40fca2`). The paper leads with the restated figures and
   reports the first as a correction.
2. **Three contribution figures in `contributions.md` hold on the earlier draw only.** On
   `6abde44e` (`m4_battery.md`): four groups each >= 0.845 alone, leave-one-country-out costs
   <= 0.002, the novel variant is caught at 100% vs 95.1%. On the E1-corrected run of the draw
   `d8083dbc`: **>= 0.829** (geographic), **up to 0.006** (UG), **98.6% vs 94.4%**. The findings
   hold on both; the numbers do not. The paper quotes the `d8083dbc` figures and names the earlier
   draw's where it cites them. Suggested fix for `contributions.md` and the claims register's
   "Claims this benchmark cannot support": state the E1 figures, or both with their draws.
3. **Three single-feature floors are in circulation.** 0.894 (M3, `2c80ef6`, 20,000 scored rows
   of `6abde44e`), 0.893 (`contributions.md`, the first battery's complete-coverage floor on
   `6abde44e`) and 0.879 (the M4 gate's floor on its own test rows, `d8083dbc`). They are
   different measurements on different rows, not a conflict. The paper uses 0.894 for the general
   statement and 0.879 for the gate margins, each with its rows.
4. **`main`'s `dataset/realism_report.md` is the 1,012,522-row report** that the M4 claims register
   and the lab notebook identify as produced from a dirty working tree. The paper does not cite it;
   dataset figures come from the claims register (`6abde44e`) and the pinned realism report
   (`d8083dbc`).
5. **`docs/research/limitations.md` still lists "a like-for-like comparison ... against an unseen
   fraud variant"** under "What the benchmark does establish", which the null result in PB-61
   contradicts. The paper does not make that claim. Suggested fix: drop or qualify the bullet.
6. **The lab notebook's prediction register still says "four recorded, one partially right".** Two
   more were recorded later (PB-56, confirmed; the ADR 0028 reversal scam, refuted below range).
   The paper's Table of predictions counts six from the outcomes the notebook records.
7. **Reversal-scam AUC.** The notebook gives 0.656 and notes it was not restated; the E1 evidence
   file gives **0.708**. The paper uses 0.708 and reports 0.656 with the first run.
8. **ADR 0028 cites no source for the reversal-scam typology** ("a documented mobile-money fraud
   pattern"). The paper carries `\cite{TODO-verify}` there; the author needs a source read in full
   before publication, or the typology claim must be softened.

## 3. When `m4-complete` exists

1. Re-read every `\prov` macro's evidence file **at the tag** (the file names are in the comments of
   `numbers.tex`); a value that moved is restated in the paper with the reason.
2. Switch `\prov` entries that match to `\meas`, which removes the dagger, and update the front
   note in `main.tex`.
3. If the M4 evidence is regenerated on another draw, every figure moves with its fingerprint (E2).

## 4. Results the paper marks NOT YET MEASURED

| Result | Owner |
|---|---|
| Five-million-row scale (ML-DATA-01) | M10 |
| Full-volume training (evaluated models used 30,000 rows) | M10 |
| Feature latency p95 (M3 target), scoring p99 frontier on a dedicated machine | M10 |
| Shadow-mode AUC delta, serving latency | M5 |
| End-to-end decision latency and throughput | M6, M10 |
| Precision at a fixed alert budget; per-month performance (C-5); cost curve; leave-one-fraud-type-out and Isolation Forest routing (E.5 item 5); fairness report (E.5 item 7) | M4 follow-up |
| `make reproduce` | M11 (this milestone, not started) |

## 5. Still owed in M11

- **Related work**: every reference is `\cite{TODO-verify}`; the author reads and verifies each
  source before it enters `references.bib`. No reference, DOI or arXiv ID has been added.
- **Novelty claim** (C-8): not made; needs the author's documented dataset search.
- **Figures**: the M4 gate's ROC and reliability plots exist as SVG on `m4/generalisation`
  (`docs/benchmarks/m4_gate_d8083dbc_v3/figures/`); they go in once M4 is tagged.
- **`make reproduce`**, publishing scripts and checklists, `CITATION.cff` (no DOI).
- **Compilation in CI**: the draft compiles locally with Tectonic; a CI job belongs to whoever owns
  `.github/workflows/`.
