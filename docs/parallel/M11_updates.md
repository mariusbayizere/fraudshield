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

### Also from M5 (2026-09-23): a limitation worth stating about the verification effort

The lab notebook entry "A fake is a hypothesis about an API, and 537 tests can confirm it wrongly"
(2026-09-23) belongs in the limitations discussion beside the training–serving skew, and makes a
different point.

M5's scoring client was reviewed four times — a principal review, a re-review of the fixes, a
verification pass and an adversarial pass with mutation testing — which between them found eleven
defects in the model-promotion path, including a gate that could be bypassed in one command. None
of them found that the client misread how MLflow reports an unset alias, because all 537 tests ran
against an in-process double that encoded the same misreading. The only thing that caught it was
one test that starts a real MLflow container.

**What may be claimed:** mutation testing, adversarial probing and independent review all operate
inside the world the fixtures define, and cannot detect a boundary that is mis-drawn, because
every instrument agrees with every other. Contact with the real dependency is the only check
outside that world. **What may not be claimed:** that the verification effort reported here
establishes correctness against the services the system integrates with, beyond the three
`requires_docker` tests that touch real Redis and real MLflow.

## From M6 — five features unusable on this benchmark (owner decision, 2026-10-17)

ADR 0055 closes ML-DATA-07 as a deviation: **five of the 44 features have no source data on this
benchmark** and are NaN for every record: `account_age_days`, `counterparty_account_age_days`,
`kyc_tier`, `agent_float_utilisation_ratio` and `agent_distance_from_registered_km`. Every model
figure in the paper is trained and evaluated without them. A deployment fed by an institution's
account master, KYC records and agent ledger would supply them; the gap is the benchmark's, not the
design's.

**A sixth feature is a different limitation, and must be stated as one.** `round_sum_flag` is
computable only because the country packs supply `round_denominations`, whose values are ASSUMED
(plausible note and typing steps, not a sourced banknote series). An assumed value is not source
data: the feature produces a number for every record, but the number rests on the project's own
assumption. Write it as "five features with no source data, and one computed from assumed
denominations", not as "six missing" and not by leaving the sixth out.

The owner's instruction: this belongs **in the limitations section beside the two generalisation
nulls, not in a footnote**. State the count (five of 44, about 11 %), what each needs, and that the
reported metrics are therefore those of a 38-feature model on this benchmark *(corrected by M11 on
2026-10-19 from "39": the registry at HEAD declares 38 COMPUTABLE, 5 NO_SOURCE_DATA and 1 CONSTANT,
`just_below_limit_flag`, and the gate's feature cache log reads "computing 38 features")*. The README's M3 line ("36
of them computable") is an earlier count and differs from ADR 0055's; quote ADR 0055's, which the
registry's computability check backs, and reconcile the README line before the paper is final.

## 8. Fourth pass, 2026-10-19: rebased onto `main`, PB-67, row selection, ADR 0055, M7, M8

`m11/paper` rebased onto `main` (`a7f0528`). The only conflict was this file, which `main` already
carried with M5's and M6's sections appended; `main`'s copy was kept. What the paper now states, and
where, is in `docs/research/paper/REVIEW.md` (row of 2026-10-19).

**Folded in:** PB-67's conclusion; the row-selection finding; ADR 0055; M7's notebook entry "A fix
whose test never reached it"; M8's note on the console's explanations (from `m8/frontend-rebase`,
not yet on `main`: ADR 0084 and PB-132 are cited as defined on that branch); ADR 0058's assumed
ingest budget, which the paper states without its number.

**Not folded in (not in this pass's work order):** M5's two sections above (the AUC-versus-decisions
table and the fake-API limitation), which the owner asked for on 2026-09-22; M10's ADR 0100 note
(on `m10/verification`, not on `main`).

**Discrepancies found upstream in this pass** (not fixed from this branch; owners in brackets):

1. **The row-selection comparison holds at seed 1 and not on the five-seed means.**
   `M4_updates.md`, ADR 0031's amendment, `contributions.md` and the notebook compare the curve's
   30K (0.795) with the gate's (0.736): a gap of 0.059 against a curve range of 0.016, "several
   times more". All are seed-1 values. On five-seed means the gap is 0.7669 − 0.7440 = 0.023
   (`m4_learning_curve_30k_d8083dbc.txt`, `m4_gate_d8083dbc_v3.txt`, SEEDS sections), smaller than
   either SD (0.0245, 0.0410) and smaller than the five-seed gain across the curve (0.028). The
   paper states the comparison at seed 1 only, and says this. Suggested: qualify the sentence in
   each of the four documents. [M4]
2. **60K fraud count.** ADR 0031's amendment, `M4_updates.md` and the notebook's concluding table
   give 533; `m4_learning_curve_60k_d8083dbc.txt` and the notebook's interim table give 532. [M4]
3. **Realised FPR at the 1% budget.** The notebook's concluding table gives 0.30% (30K) and 0.55%
   (60K); the evidence files give 0.0068 and 0.0030, as the interim table did. The paper does not
   print these. [M4]
4. **Trained feature count.** The M6 section above says the metrics are "those of a 39-feature
   model". The evidence says 38: `m4_evaluate_gate_d8083dbc.txt` "computing 38 features", the
   battery's "full model, 38 features", and the gate's "without agent features 36 feat". The 39
   leaves out `just_below_limit_flag`, which the registry marks CONSTANT (PB-47). The paper says
   38. [M6 / owner]
5. **README's "36 of them computable"** (M3 line) still differs from ADR 0055, as the M6 section
   noted. Not in the paper. [owner]
6. **Stale "not yet measured" owners.** ADR 0035 carried M5's latency rows and shadow-mode AUC delta
   to M10; ADR 0059 did the same for M6's end-to-end latency. Sections 3, 7 and 13 still print
   "not yet measured (M5 …)" and "(M6 and M10)". Left as they were; proposed: name M10 and cite
   the two ADRs. [M11, awaiting the owner]

## 9. Fifth pass, 2026-10-19: the owner's evidence rule applied to the whole paper

Owner's rule: every number and claim traceable to evidence; evidence beats documents; inferences
marked in the sentence; unsupported claims removed or stated as not established. Two independent
read-only sweeps checked every sentence; the paper was then corrected section by section. What the
paper now says is in `docs/research/paper/REVIEW.md`.

**Corrected upstream from this branch, because the evidence contradicts the document** (each with
a dated note in place):

1. `docs/ml/training_serving_parity.md` Decision 1 said the relative tolerance is stricter than
   1e-9 "at every magnitude in the table"; it is stricter only below 999 and looser at the table's
   last three rows (arithmetic on the table's own magnitudes).
2. `docs/research/claims_register.md` header said every MEASURED figure is from `6abde44e`; C-14
   (the single-feature floor, commit `2c80ef6`, 2026-09-20) predates that draw and records no
   fingerprint.
3. (Earlier today, commit `378fc55`) ADR 0031, `M4_updates.md`, `contributions.md`, the lab
   notebook (correction entry), the README and §M6 above: 532 frauds, realised FPRs, the seed-1
   scope of the row-selection effect, 38 trained features.

**Flagged, not corrected here** (each is a document that overstates its evidence; owners in
brackets):

1. ADR 0028 says the reversal variant's "known counterparty" property comes from the typology; the
   sources read (`reference_audit.md`, dogan2025grift and star2025mpesa) do not say so. [owner]
2. `test_the_reversal_scam_variant_is_one_transaction_to_a_listed_payee_in_test_period` (formerly `..._to_a_known_payee_...`) checks
   payee-list membership, not `counterparty_is_new_for_account`; the feature's value on the 66 rows
   is unmeasured. A read-only count would settle it. [M4]
3. `ml/src/fraudshield_ml/features/batch.py` docstring of the synthetic-identity score still says
   the device-sharing term is constant (PB-40), which PB-40's closure (2026-09-21) superseded. [M4]
4. Claims register C-12 says "every AUC reported before this correction is inflated by about that
   much"; it was measured on three raw columns only. C-11 says "the rest is the scenario"; the new
   lead distribution is itself assumed. [M2 / M4]
5. `test_no_fraud_parameter_is_keyed_by_country`'s docstring calls fraud "country-invariant by
   construction"; CD's test-period fraud rate is 0.28% against 0.93–1.07% elsewhere. [M2]
6. The notebook's own header and history show backfilled and amended entries; the paper no longer
   calls it never-edited. [owner, for the notebook's rule]
7. `docs/benchmarks/test_set_access.jsonl` has no entry for the read made by
   `docs/research/paper/evidence/reversal_labels.py`. [M11: log it, or leave the paper's statement]

**Harness finding.** In Claude Code's Bash tool, `set -euo pipefail` does not stop a command chain
(`set -euo pipefail; false; echo X` prints X); a checkpoint commit on this branch was made past a
failing lint before it was noticed and amended. Gating chains here now use `&&`.

## 10. Sixth pass, 2026-10-19: owner's answers

1. **CI** — `.github/workflows/paper.yml`: the paper checks and a checksum-verified Tectonic 0.17.0
   compile on every push, not path-filtered; it fails on a typed number, a stale audit, appendix or
   figure, an undefined reference or an overfull line, and uploads the PDF.
2. **M5 items folded in** (Section 11), each sourced to its record (ADR 0034 at `151e796`, the
   notebook at `488c5b4`) and saying in the sentence that no stamped raw output exists for it.
3. **ADR 0028 amended**: "a known counterparty" is the project's modelling choice; the cited sources
   do not support it. The paper says so where the choice is introduced.
4. **Unlogged read back-filled** in `docs/benchmarks/test_set_access.jsonl`; both paper evidence
   scripts now log their reads through `access.record`.
5. **Reversal novelty measured** on a regenerated draw (`d8083dbc` reproduced at `m4-complete`,
   `evidence/regeneration_d8083dbc.txt`): `counterparty_is_new_for_account` is true on **19 of the
   66** rows scored as fraud. The variant removes counterparty novelty from 47 rows only. Upstream:
   `test_the_reversal_scam_variant_is_one_transaction_to_a_listed_payee_in_test_period` asserts
   payee-list membership, which does not imply the feature; whether the generator should be changed
   is an owner decision and is not done here. [M2 / owner]
6. **Pre-rebase commits preserved**: `origin/m11/paper-pre-rebase-af54957` keeps the commits whose
   hashes earlier evidence stamps name (`6fe4bf0`, `3559f8c`).

## 11. Numbering reserved by this branch

Claimed 2026-10-19, after reading every branch: the highest backlog id in use or reserved anywhere is
PB-140 (`m8/frontend-rebase`, M8's block `PB-131`–`PB-140`).

| Kind | Reserved for M11 | Used |
|---|---|---|
| Backlog | `PB-141`–`PB-150` | `PB-141` (the reversal-scam test checks payee-list membership, not novelty), `PB-142` (evaluations keep per-row scores) |

## 12. PB-141 decided, 2026-10-19

Owner: keep the reversal-scam variant as generated and describe it accurately everywhere; do not
change the generator (it would re-draw `d8083dbc` and move every figure, with 474 macros to
re-audit). Done here, with no change to the draw: the generator's comment and docstring, the
scenario's test (renamed `..._to_a_listed_payee_in_test_period`, its docstring and assertion
message saying what it does not show), the datasheet, the model card and ADR 0028 now say "a
transfer to a listed payee", with the 19-of-66 result. PB-141 stays open as a known inaccuracy, with
the reason in its row. The lab notebook's 2026-09-22 pre-registration keeps its original wording
(append-only); its 2026-10-19 entry records the finding.

## 13. Owner decisions on the GitHub Actions scope, 2026-10-19

1. **Alert budget:** precision is reported across a range of daily alert budgets, stated as assumed
   and why: no budget is defined, and D-10's capacity model (`docs/ml/capacity_model.md`) does not
   exist, so a single number would be invented.
2. **Cost curve:** raw axes only (fraud amount caught against legitimate amount blocked and reviews
   required). No currency figure: the costs have no source.
3. **Full volume:** a cache-only job measures memory and time first. **Not built on this branch:**
   `origin/ml/full-volume` (PB-66, `cef3839`, pushed 2026-10-19) already holds a two-job workflow
   with its own pre-registration document whose first job is exactly this cache build, plus PR-AUC, precision at assumed
   budgets, by-month figures, a cost curve and a country/channel fairness report, awaiting the owner's
   approval. A second job here would duplicate it. M11 reads its evidence when it lands. A drafted
   cache-only workflow (`088cfd2`, peak RSS, swap and a streamed memory curve; reviewed clean) is kept
   out of the repository; PB-66's job 1 records the cache build's peak RSS and elapsed time but no
   memory curve or swap use, which the owner may want added before it runs.
   Difference to resolve: PB-66's primary cost curve is Drummond-Holte normalised cost, the owner's
   decision for the paper is raw axes (fraud amount caught against legitimate amount blocked and
   reviews required).
4. **Per-row scores:** not committed (G.5: scores tied to account tokens are close to dataset
   content). Their hashes and the regenerating workflow are the provenance.
5. **Fairness:** country and channel only; the paper says KYC tier and urban/rural are not in the
   released data, so the report cannot cover the specification's full set.
6. Latency percentiles stay unmeasured: a shared runner cannot give them.

## 14. Owner decisions after group 2, 2026-10-19

1. **The external review** behind groups 1 and 2 was carried out by an AI model, not a person. The
   disclosure section and cover note say so, and that the work has had no independent human review.
2. **Search agents:** no personal identifiers in outbound query parameters, ever, even where an API
   documents them as polite (after the OpenAlex `mailto` leak, recorded in
   `docs/research/c8_dataset_search_pass2.md` §8).
3. **PB-66 overlap:** M11 uses PB-66's workflow (`origin/ml/full-volume`) and does not duplicate it.
   Drummond–Holte stays its main cost-curve presentation; **the paper also reports the raw axes**
   (fraud amount caught against legitimate amount blocked and reviews required), because a normalised
   curve hides the absolute amounts. **Asked of PB-66:** add a memory curve and swap use to the cache
   job; peak RSS alone cannot say why a run died. [PB-66 owner]
4. **C-8 search closed** at two passes (about 27 minutes); its open gaps are recorded in the pass-2
   record, §8. No further searching: the claim is already refuted.

## 15. Paper-readiness work order, 2026-10-19

The owner's work order (Phases 0–4) and the decisions taken on it the same evening:

- **PB-66 (Phases 0 and 2):** done by PB-66's owner session on `ml/full-volume`, which M11 does not
  write to. Owner: add leave-one-fraud-type-out (numeric predictions, pushed, confirmed by the owner
  before any run; at 30,000 rows if eight full-volume refits do not fit) and per-channel reliability
  diagrams (descriptive); `account_id` and the variant may go into the uncommitted per-row artifact.
- **M8 is not merged** (owner). ADR 0084 and PB-132 are copied onto this branch with provenance
  notes; the paper states the console is not part of this release and is not evaluated.
- **Phase 3 fixes that change no number are done now** (owner): the introduction's opening and its
  C-8 contradiction, "gate" defined, "untuned and overfit", one list of deferred measurements
  (Appendix E), and Table 12's rebased learning-curve prediction moved to "not established".
- **Audit:** fails on a cited path missing from the checkout, on an ADR, PB, D or C identifier not
  defined in the repository, and on a rebased pair whose rewrite date is wrong; every cited evidence
  file is indexed in Appendix D.
- **Cover note** (owner, 2026-10-19): the disclosure sentence is removed from "How to read the
  numbers"; the disclosure stays whole in Section 11, including that the work has had no
  independent human review.
- **Repository visibility:** already public since 2026-09-17, so "do not make it public" cannot be
  applied; the pre-publication scan was run anyway (findings in the owner report).

## 16. Second work order, 2026-10-19: what the paper must say once PB-66's run is in

PB-66's full-volume run started 2026-10-19 18:24 UTC (workflow run 39302165997, commit `fcec52c`,
branch `evaluate/pb66-full-volume`), after the owner confirmed the LOFTO ranges recorded there.
The owner directed, through PB-66's session, that two statements go into the paper's text (owner's
words):

1. "LOFTO measures what never fitting on a type costs, not what never seeing it costs, because the
   remaining rows' features were computed over a history that includes it."
2. "The test set has been scored 23 times before, 11 at full size. The paper must say the run is
   pre-specified, not blind."

M11's own round-two evidence is fixed in `docs/research/paper/evidence/round2_plan.md` before its
first run (`.github/workflows/paper-evidence.yml`); its analyses are pre-specified, not blind either.
