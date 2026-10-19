# Paper history and review record

The paper is a living document: results that later milestones produce replace its "not yet
measured" markers. This file records every change to what the paper states, with its evidence.

## Procedure for a new result

1. Add a macro with its evidence file, commit and draw to `numbers.tex`.
2. Add its source (file and pattern, or a derivation) to `audit_numbers.py`.
3. Run the audit at the new tag (`--ref <tag>`); it must report zero problems.
4. Replace the `\notyet{Mx}` marker in the section with the macro, and state any prediction that
   was registered for the result beside it (for example PB-67's learning curve, Section 7).
5. If the value is plotted, run `figures/make_figures.py`.
6. Build with no undefined references and no overfull lines, and add a row below.

## History

| Date | Change | Evidence |
|---|---|---|
| 2026-09-22 | First complete draft; M4 values pinned at `a6b0c9e` and marked provisional | `docs/parallel/M11_updates.md` |
| 2026-09-22 | Every M4 value re-verified at `m4-complete` (`72e7790`); daggers removed; one mismatch fixed at its source (FNR target printed as 12.0%) | `number_audit.md` |
| 2026-09-22 | Recall-miss wording follows ADR 0031: a finding at 30,000 training rows, untested against training volume (PB-67) | ADR 0031 at `m4-complete` |
| 2026-09-22 | Dataset-at-a-glance and per-channel and per-country test-slice tables | `number_audit.md` |
| 2026-09-22 | Bibliography of 10 generator sources, each checked against the document itself | `reference_audit.md` §1 |
| 2026-09-22 | Revision "v2" applied by hand (its archive was never delivered): new title, cover page, contents; 34 scholarly references verified from DOI or publisher pages, every "[reference needed]" replaced; related work rewritten; reversal-scam section cites Doğan et al. and states the variant as a modelling choice; corridor classes; ensemble and frontier cautions; three figures generated from `numbers.tex`; conclusion, availability statement and Appendices A–D; `\notyet` printed in italics | `reference_audit.md` §2–4, `number_audit.md` |
| 2026-09-22 | Doğan et al. read in full and upgraded to verified; The Star (27 March 2025) read and added as `star2025mpesa`. Section 8 rewritten sentence by sentence: forged confirmation messages, malicious reversal requests and disputed mistaken transfers attributed to Doğan et al. (§5.1.1, §2, §5.3.3); the "sent by mistake, please return it" story and Safaricom's do-not-refund advice attributed to The Star; "the recipient is a new counterparty" stated as the paper's reading, since neither source says it. Related work, Section 11 and the conclusion follow; `check_numbers.py` accepts a citation's locator | `reference_audit.md` §2, §4 |
| 2026-09-22 | Senior review of `801d60b` applied. Correctness: the recall miss restated as "no threshold with FPR at or below the budget reaches the target", with the ROC vertices either side of the target; the synthetic-identity composite described as it runs here (two of four terms always zero); the card-style ablation rewritten so the reasoning holds at its size, the largest in the paper. Consistency: abstract reframed to the question, with the training volume and the explanation-cost growth; the sourcing pass's document count and the two later bloc pages; two test-only sub-variants; the block threshold as a count of legitimate rows; 9 of the 11 gate metrics measurable at this milestone, of 13; the 66-of-68 denominator explained from the draw. Added a benchmark audit protocol table (Section 9.3) and moved internal identifiers into parentheses | `number_audit.md`, `evidence/reversal_labels_d8083dbc.txt` |
| 2026-10-19 | Rebased onto `main` (`a7f0528`). Folded in: PB-67's conclusion (Section 7.2 and Table `tab:volume`, the prediction as a seventh row of Table `tab:predictions`, the recall-miss wording in Sections 7, 11 and 13); the row-selection finding (Section 9.2), reported at seed 1 only because on five-seed means the difference is 0.023, inside the seed spread, with its mechanism marked as inferred and its two-selection limit; ADR 0055 in Section 11 beside the burst-separability paragraph, and in Sections 5, 7 and Appendix B (five features with no source data, `round_sum_flag` from assumed denominations, 38 trained features); M7's "a fix whose test never reached it" (Section 9.1); M8's console note (Sections 6, 7.5 and 10: no template catalogue, a ranked feature table run only against contract mocks, no latency-budget claim); ADR 0058's assumed ingest budget (Section 3). 39 new macros; learning-curve files read at `3dacca6`, notebook entries at `488c5b4`, ADR 0055 at `m6-complete`. Not compiled: no TeX engine on this machine | `number_audit.md` (362 macros, 0 problems) |
| 2026-10-19 | Two independent reviews of the change above applied: the learning-curve claims scoped to seed 1 or to the five-seed means as each holds (the means rise at every step, each step within a standard deviation, 0.028 in all); the budget-recall comparison carries the operating-point caveat; the single-cache 30K-120K step named as the clean volume evidence; the 38-feature statement limited to the full models; the pre-registration record, the M7 mechanism, the C-3 citation and the console wording corrected | `number_audit.md` (367 macros, 0 problems) |

| 2026-10-19 | The owner's evidence rule applied to the whole paper after two independent read-only sweeps (one per half). `check_numbers.py` now fails on bare integers and number words; every number left is an audited macro (447) or a generated quotation (Appendix B from the registry, Appendix D from the audit). Claims the evidence contradicted were corrected: the floor's draw (an unfingerprinted earlier draw, not `6abde44e`; margins now use the gate floor), home locations, agent float, local time, decimals, the parity tolerance and independence test, the velocity-ratio defect, the novel variant's differences, the reversal variant's payee property, amount rule and unverified novelty, the seed-variance quantity, DeLong as no detected difference, the precision ceiling, ablation p-values, the fingerprint being a row sample, the PB-29 re-draw claim, the test-set log, evidence stamps, enforcement of the discussion's rules. Inferences are marked in their sentences. Upstream: the parity document's tolerance claim and the claims register's C-14 draw corrected | `number_audit.md`, `docs/parallel/M11_updates.md` §9 |

| 2026-10-19 | Owner's answers applied. CI gates the paper (`paper.yml`: checks and a Tectonic compile on every push). The paper compiles with no undefined references or overfull lines. M5's skew table and fake-MLflow case are in the limitations, sourced to their review records. ADR 0028 is amended and the paper states the known-counterparty choice as ours where it is introduced. Contributions 3 and 4 say what M10 would add. The evaluation draw was regenerated at `m4-complete` (fingerprint `d8083dbc` matched); the label count was re-run on it and the reversal novelty counted: 19 of the 66 scored rows still carry counterparty novelty. The earlier unlogged read is back-filled; both evidence scripts log their reads | `evidence/regeneration_d8083dbc.txt`, `evidence/reversal_novelty_d8083dbc.txt`, `number_audit.md` (474 macros) |

| 2026-10-19 | External review, group 1 (correctness). The gate model and the diagnostic battery were reproduced on GitHub Actions from a regenerated draw (run 39273972313; every headline matched) and account-clustered intervals added beside the row ones (Table `tab:clustered`, Sections 6, 7, 8, 11, cover note). The velocity group's 0.860 below its own member's 0.879 is measured: the untuned diagnostic model overfits (peak 0.875 near round 25, 0.860 at round 200), so group figures are lower bounds and no group ranking is claimed. Small numeric fixes: seed spread from per-seed AUCs (+0.62%), sign wording, rounded vs unrounded deltas and margins, 259 vs 260 | `evidence/clustered_intervals_d8083dbc.txt`, `number_audit.md` (503 macros) |

| 2026-10-19 | External review, group 2 (claims). "Pre-registered" replaced by "pre-specified in repository history": git orders 4 of the 7 recorded predictions before their evidence and not the other 3 (one first committed with its refutation, two after the code holding their outcome); Table `tab:predictions` shows both groups and `audit_numbers.py` checks every commit pair and author date in both directions, the table's group counts and malformed uses. Disclosure section added (Section `sec:disclosure`, cover note): sole author using AI coding agents for implementation, review and drafting; reviews are agent reviews by the author's account, with what the records themselves say. Related work: BankSim, BAF, Altman et al. and Platt added, each with what was verified. C-8 refuted by a recorded dataset search: Xente (Zindi, Uganda) and MoMTSim (Makerere) predate this benchmark; no narrower novelty is claimed. One fresh review applied | `docs/research/c8_dataset_search.md`, `reference_audit.md` §5, `number_audit.md` (505 macros) |

## Open review points




- The documented form's recipient being a new counterparty is the paper's reading of the two
  sources, not a statement in either; a source that says it would strengthen Section 8.
- The novelty claim (C-8) is not made and needs a documented dataset search.
- Razaq et al.'s "96 interviews" is confirmed from the paper's abstract as the publisher deposited
  it; the body is closed access (`reference_audit.md`).
- The ROC crossing of the recall target is read from the gate run's plotted ROC figure, which is
  drawn through 200 vertices, so it is bracketed (1.2% to 1.7% FPR) rather than exact. An exact
  figure needs the scores, which means a rerun.
- Not yet measured: the 99th-percentile frontier, serving and end-to-end latency, five-million-row
  scale, and the M4 follow-ups listed in Section 7. ADR 0035 and ADR 0059 moved the M5 and M6
  latency rows (and the shadow-mode AUC delta) to M10; the paper's markers still name M5 and M6.
- How much the headline figures vary across selections of training rows (PB-76): two selections
  exist, so the variation is unmeasured.
- Which of the 66 reversal-scam rows the battery caught is not recoverable: per-row scores were not
  kept, so the part counterparty novelty plays in the miss is not isolated.
