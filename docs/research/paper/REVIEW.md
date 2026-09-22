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

## Open review points

- The documented form's recipient being a new counterparty is the paper's reading of the two
  sources, not a statement in either; a source that says it would strengthen Section 8.
- The novelty claim (C-8) is not made and needs a documented dataset search.
- Razaq et al.'s "96 interviews" is confirmed from the paper's abstract as the publisher deposited
  it; the body is closed access (`reference_audit.md`).
- The ROC crossing of the recall target is read from the gate run's plotted ROC figure, which is
  drawn through 200 vertices, so it is bracketed (1.2% to 1.7% FPR) rather than exact. An exact
  figure needs the scores, which means a rerun.
- Not yet measured: the learning curve (PB-67), the 99th-percentile frontier, serving and
  end-to-end latency, five-million-row scale (M5, M6, M10), and the M4 follow-ups listed in
  Section 7.
