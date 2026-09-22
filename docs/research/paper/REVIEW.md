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

## Open review points

- Doğan et al. (COMPASS '25) is cited for the documented form of the reversal scam, but its full
  text could not be opened from here (`reference_audit.md`): the author must confirm the
  description of the forged-message form against the paper before submission.
- The novelty claim (C-8) is not made and needs a documented dataset search.
- Not yet measured: the learning curve (PB-67), the 99th-percentile frontier, serving and
  end-to-end latency, five-million-row scale (M5, M6, M10), and the M4 follow-ups listed in
  Section 7.
