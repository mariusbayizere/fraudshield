# M4 updates (branch `m4/generalisation`)

Written by the M4 agent for the owner and the M11 agent. M4 owns `ml/`, the M4 benchmarks and
`docs/research/paper/contributions.md`; everything else under `docs/research/paper/` is M11's and
is not edited from this branch.

## PB-67 is concluded (2026-09-24) — for the paper

The pre-registered learning curve (`d071147`) is complete. **The recall shortfall at the 0.60 flag
threshold is a property of this benchmark and model at the volumes measured, not of training
volume.**

| Training rows (frauds) | Recall at 0.60 [95% CI] | Five seeds | Recall at 1% FPR budget |
|---|---|---|---|
| 30,000 (259) | 0.795 [0.766, 0.818] | 0.767 ± 0.025 | 0.869 |
| 60,000 (533) | 0.799 [0.773, 0.823] | 0.772 ± 0.038 | 0.854 |
| 120,000 (1,066) | 0.798 [0.772, 0.821] | 0.779 ± 0.023 | 0.878 |
| 240,000 (2,132) | 0.783 [0.755, 0.808] | 0.795 ± 0.024 | 0.860 |

**What changed for the paper, and what did not.**

1. **The claim may now be stated.** Until today the wording had to be "recall 0.736 **at 30,000
   training rows**", never a property of the benchmark, because the cause was untested (ADR 0031).
   It is tested: across an 8× increase the five-seed mean moves 0.767 → 0.795 while the distance to
   0.88 is 0.085, and every interval at every size excludes 0.88. The paper may say the shortfall
   is a property of this benchmark and model **at the volumes measured** (30,000–240,000 rows of
   draw `d8083dbc`).
2. **The pre-registration was not edited**, and one of its predictions missed: recall at the 1% FPR
   budget was predicted to reach 0.87–0.91 at 240K; it read 0.860 at seed 1 (interval [0.838,
   0.881]) and 0.876 ± 0.013 over five seeds. It also did not *rise* — 120K already read 0.878.
   If the paper reports the prediction, it should report that.
3. **A larger effect than the one PB-67 tested — for the discussion, not a footnote.**
   At the **same 30,000 training rows**, this curve reads **0.795** and the declared gate reads
   **0.736**: a gap of 0.059, with intervals that only just fail to overlap ([0.766, 0.818] against
   [0.707, 0.765]). The four curve points, spanning an 8× change in row count, span only 0.016.
   **Which rows are used moves recall several times more than how many.**

   *Mechanism, as far as the evidence reaches.* The two 30K samples differ in the corpus they are
   drawn from: the gate's 30,000 rows come from a 400,000-row corpus covering the last ~128 days of
   the train period; the curve's come from a 340,000-row corpus reaching further back, and the 240K
   point from a 520,000-row one. A shallower corpus truncates each account's history, so the
   velocity, recency and account-profile features are computed over less past and are systematically
   flatter for the same account. That is PB-69: the features degrade with corpus depth, and the
   training rows inherit that degradation.

   *What is not yet known.* Two things, and the paper should say both.
   - **Whether the gate's row selection is representative.** We have two selections, not a
     distribution. 0.736 could be an unlucky draw, 0.795 a lucky one, or the difference could be
     systematic. Nothing measured so far separates those.
   - **What a like-for-like comparison would require.** Hold the test rows, the model, the seeds and
     the row count fixed at 30,000, and vary only the selection: several corpus depths (say 340K,
     400K, 520K) and several windows at each depth, so the result is a spread rather than two
     points. Report the truncation profile beside each — how much history each training row actually
     has — so the effect is attributed to the mechanism rather than inferred from it. That is a
     measured experiment PB-69 can carry; it does not exist yet.

   *Why it belongs in the discussion.* It qualifies **every single-number result in the paper that
   was measured at one sampling**, including the headline gate numbers: each is a measurement of one
   draw at one corpus depth, and this comparison shows that choice moving a headline metric by more
   than a quadrupling of data does. Stated as a limitation of the measurement, not as a defect in
   the model.

4. **Pinning.** `M11_updates.md` §1 pins M4 results at `a6b0c9e`. The curve's evidence is later:
   the four artefacts are `docs/benchmarks/m4_learning_curve_{30k,60k,120k,240k}_d8083dbc.txt`, the
   last committed at `c2862c5` on this branch. The three earlier points were already at `2a876d8`.
   Nothing in the declared gate run changed; the gate artefact is still
   `docs/benchmarks/m4_gate_d8083dbc_v3.txt`.
5. **Machine.** All four points ran on the laptop (i5-6200U, 4 cores, 7.6 GiB), recorded in each
   artefact's `# evidence-machine:` line. Accuracy results are admissible from any machine (ADR
   0010 restricts latency only); the 240K point was run here rather than in a Codespace on the
   owner's instruction.

`docs/research/paper/contributions.md` (M4's file) carries the updated finding, and ADR 0031's
2026-09-24 amendment carries the decision.
