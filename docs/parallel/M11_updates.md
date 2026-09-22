# M11 updates (branch `m11/paper`)

Written by the M11 agent for the owner and the M4 agent. M11 owns `docs/research/paper/` except
`contributions.md` (M4's, read only). Everything else under `docs/` is read, not edited, from this
branch; what it needs is proposed here.

**Status: complete draft, audited, not reviewed.** Section 7 (2026-09-22, third pass): the v2
revision's changes applied by hand, since its archive was never delivered. The paper's own history
is now in `docs/research/paper/REVIEW.md`; sections 1-6 below are the record up to that point.

Original status line: **first complete draft, not reviewed.** `docs/research/paper/main.tex`, 13 sections,
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

## 6. Second pass, 2026-09-22 (after `m4-complete`)

**v2 is not integrated.** `~/Downloads/fraudshield-paper-v2.zip` does not exist, and no `REVIEW.md`,
`figures/make_figures.py` or `star2025mpesa` entry exists anywhere under the home directory.
`~/fraudshield-paper.zip` is byte-identical to this branch's paper at `961fbec`, and
`~/Downloads/fraudshield_paper.pdf` is a render of it. Everything below was done on the current
draft so it can be merged into v2 when v2 arrives: `audit_numbers.py` runs unchanged against v2's
`numbers.tex`, and each new macro carries its own audit source.

**Numbers.** `audit_numbers.py` reads every macro's evidence file at `m4-complete` (`72e7790`) with
`git show` and compares printed values exactly. 305 macros, 0 mismatches; one mismatch was found
and fixed at its source (the FNR target is 12.0%, printed as 12%). No evidence file changed
between the pin `a6b0c9e` and the tag, so every former `\prov` value matched and none is
provisional. Captions now state draw, rows, fraud and evidence commit.

**Learning curve (PB-67).** Not measured on any branch at the time of writing; the paper says the
recall miss is untested against training volume and marks the curve NOT YET MEASURED.

**Living-document procedure.** When a milestone produces a result the paper marks NOT YET
MEASURED: add a macro with its evidence file and commit to `numbers.tex`, add its source to
`audit_numbers.py`, run the audit at the new tag, replace the marker, and record the change. The
history belongs in the paper's `REVIEW.md`, which is part of v2; until v2 is integrated it is kept
here.

| Date | Change | Evidence |
|---|---|---|
| 2026-09-22 | All M4 values re-verified at `m4-complete`; daggers removed | `number_audit.md` at `72e7790` |
| 2026-09-22 | Recall-miss wording follows ADR 0031 (a finding at 30,000 training rows) | ADR 0031 at `m4-complete` |

**Discrepancies found upstream in this pass** (not fixed from this branch; owners in brackets):

1. `dataset/params_provenance.md` and `docs/research/sourcing_pass.md` cite Bank of Tanzania
   Annex H one page early: H1, H2, H3 and H5 are on printed page 48 (cited as 47), H8 on page 49
   (cited as 48). [dataset / M2]
2. `params_provenance.md` puts the NBR active-subscriber count on page 86; it is on page 87.
   [dataset / M2]
3. The sourcing pass cites the census table at "page 30", the PDF index; the printed page is 5.
   [dataset / M2]
4. The school calendar is hosted by **New Generation Academy** (nga.ac.rw); the sourcing pass and
   `params_provenance.md` say "Nu Vision Academy". The PDF names no school, and nothing read
   confirms that it implements the national calendar. [dataset / M2]
5. `docs/ml/datasheet.md` gives label noise 1.66% / 1.67%, the old 1,012,522-row report's figures;
   `dataset/realism_report.md` for the same draw `d8083dbc` gives 1.55% / 1.64%. [M4]
6. `docs/research/limitations.md` gives 25 fraud parameters (21 assumed, 4 calibrated);
   `params_provenance.md` at the tag gives 30 (26 assumed, 4 calibrated), and 124 parameters
   overall (33 sourced). [M4]
7. The claims register says the sourcing pass "read seven documents"; the pass lists eight
   (S-1 to S-8). It also says `dataset/realism_report.md` "now carries" fingerprint `6abde44e`; at
   the tag it carries `d8083dbc`. [M4]
8. The datasheet says the reversal scam added 68 test-period rows; the battery scores 66 fraud rows
   of that variant. Nothing read explains the two rows; the paper states both counts with their
   sources. [M4]
9. The SADC member-states page cited by `params_provenance.md` (CD and TZ bloc memberships) could
   not be opened on 2026-09-22 and is not cited. [dataset / M2]

## 7. Third pass, 2026-09-22: v2's changes applied by hand

The v2 archive was never delivered; its ten listed changes were applied to this branch, keeping
every audited value, table and caption. Commits: `2e4e4bd` (references), `36d835b` (title, cover
page, contents), `22d1ee7` (related work; Sections 3, 7, 8, 11), `bf867d1` (figures), and the
appendix, documentation and history commits that follow. Checks at the end of the pass:
`audit_numbers.py` 0 problems at `m4-complete`, `check_numbers.py` 0 direct numbers,
`make_figures.py --check` clean, Tectonic build with no undefined references and no overfull lines.

**Where this branch departs from v2's instructions, and why:**

1. **PaySim venue.** The instruction gives "EMSS 2016, Larnaca, pp. 249-255". Venue and pages are
   confirmed; "Larnaca" is not printed in the proceedings front matter read, so the entry omits
   the place rather than state it unconfirmed.
2. **Platt (1999) dropped.** No DOI or publisher page confirming the chapter could be opened;
   Platt scaling is cited through Niculescu-Mizil and Caruana (2005), which describes it.
3. **Doğan et al. is METADATA-ONLY.** Title, authors, venue, pages and DOI are confirmed from
   Crossref and the ACM page; the full text returned 403 and has no open copy or abstract. The
   Section 8 description of the forged-message form rests on the brief, and REVIEW.md lists it as
   an open point for the author to check against the paper.
4. **PaySim and geography.** PaySim's log has no channel, device, geographic or corridor field, as
   the paper says; its agents do occupy a simulated space, so "no geography" is stated as "no
   geographic field" rather than "no notion of location".
5. **`star2025mpesa`** was named in an earlier brief but never found or added; it is not cited.
6. **Lee et al. (SOUPS 2020)** studied five prepaid carriers in the United States; the paper says
   so rather than generalising to East African operators.
7. **The corrected reversal-scam figures stand** (9.1%, 6 of 66, E1 run `d40fca2`), not the
   superseded first run the earlier brief quoted (section 2, item 1).
8. **Email address.** The cover page gives `bayizeremarius119@gmail.com`, the contact the
   repository documents; the owner should confirm it is the address wanted on the paper.

**Process note.** During reference checking, the first few Crossref API requests carried the
owner's email address in the User-Agent header (Crossref's "polite pool" convention). This was
stopped at once; later requests carried no personal data.

**Page count:** 35 pages (cover, abstract, contents, 14 sections, references, Appendices A-D).

**Update, later on 2026-09-22.** Departures 3 and 5 are resolved. Doğan et al. was read in full
from the third author's copy and is now VERIFIED-SOURCE; The Star's explainer of 27 March 2025 was
read and added as `star2025mpesa`. Section 8 attributes each claim to the source that states it;
the reading that the documented scam pays a new counterparty is marked as the paper's own.

## 8. Senior review of `801d60b`, 2026-09-22

Thirteen items applied on this branch. Two touch the record upstream owners keep:

1. **Discrepancy 8 of section 6 is resolved** (datasheet says the reversal scam added 68 test-period
   rows; the battery scores 66). Reading the draw itself: all 68 rows are in the test period and are
   fraud by their true label; 2 carry the benchmark's missed-fraud label noise, and the evaluation
   reads `is_fraud_observed`, so those 2 are scored as legitimate rows. Evidence:
   `docs/research/paper/evidence/reversal_labels_d8083dbc.txt` (script at `6fe4bf0`, clean tree,
   fingerprint `d8083dbc` recomputed with the generator's function at the tag). No upstream fix is
   needed; the datasheet and the battery are both right about different labels. [M4]
2. **ML-GATE-05 reads 0.000 in the gate file at three decimals, and is not zero.** `metrics.json`
   gives 0.0003170702706987436, which is 32 of the 100,924 legitimate test rows. A reader of the
   text file alone would conclude no legitimate row is blocked at the block threshold. Suggested
   fix: print the count, or a fourth decimal. [M4]

The paper now also states that 9 of the **11** gate metrics measurable at this milestone pass, of
the **13** in `requirements.yaml`; ML-GATE-12 and 13 are filed under M5 (with M10's hardware for the
latency percentile), as ADR 0031 says.

## From M5 — a result for the discussion and limitations (owner decision, 2026-09-22)

Appended by the M5 agent from `m5/scoring`. This file also exists on `m11/paper`; keep both sides
at merge, nothing here replaces M11's own content.

The owner asked for this to go in the paper: **a measured case of a system whose ranking metric
barely moves while its decisions change materially.** The lab notebook entry is
"2026-09-22 · AUC moved 0.009; a fifth of the detections disappeared"; ADR 0034 records the
decision; `docs/reviews/M5/principal-review.md` finding 1 is where it was raised.

**The table, exactly as measured** (M4's gate model rebuilt from `features_gate_d8083dbc.parquet`
at seed 1, scored over the whole test period: 101,909 rows, 985 frauds; each row replaces one piece
of state with what the deployed system actually supplies):

| Serving state | Test AUC | Risk-tier changes | Frauds reaching 0.60 |
|---|---|---|---|
| as trained | 0.9700 | — | 725 |
| outcomes missing (no labels consumer) | 0.9619 | 235 | 585 |
| SIM swaps missing (no topic exists) | 0.9699 | 25 | 712 |
| **both, as M5 ships** | **0.9611** | **254** | **563** |

**What may be claimed:** AUC fell 0.0089 — about one half-width of the gate's own 95% interval
(±0.0075), overlapping it heavily, and not a drop any AUC-expressed gate would refuse — while 162 of 725
frauds (22%) stopped reaching the 0.60 flag threshold. Four *trained* features were served as
constants because nothing deployed writes the state they read. Measured independently twice.

**What may not be claimed:** that this is a property of AUC in general, or a general result about
production ML. It is one model, one benchmark, one degradation, at one threshold. It also does
**not** mean the gate figures are wrong: they describe the trained model correctly. The claim is
about what a ranking metric can fail to show about decisions.

**Suggested framing:** a limitation of gate metrics rather than a contribution — "our own gate
would have passed a deployment that lost a fifth of its detections, which is why the serving path
carries its own acceptance test and a metric". If it is used as a contribution, it needs the
caveats above beside it.

**Do not write** that the served system detects 22% less fraud in operation: no deployment has run.
The measurement is what the served *feature set* does to the evaluated model on the test period.
