# FraudShield final report (M12 audit)

**Status: draft for the owner, 2026-10-25, on branch `m12/audit`. Not a release.** No `v1.0.0`
tag exists, and none is proposed here. The last milestone tag is `m7-complete`; M8, M8B, M9, M10,
M11 and M12 have no completion tag. Tags are the owner's decision after reading this report.

**What this report is.** An audit of what the repository records, at `main` = `15a5ad6`, plus the
unmerged branches named below. It does not re-run anything. The fresh-clone Principal Review that
build prompt Part I.4 asks of M12 has **not** been run; this report is the record that review would
start from, not a substitute for it.

**How to read it.**
- *Merged* means reachable from `main` at `15a5ad6`.
- *Verified* means a recorded evidence file, CI run or review result exists for the claim. It does
  not mean the claim was re-checked for this report.
- Register counts are from `docs/traceability/requirements.yaml` at `15a5ad6` (258 rows), by each
  row's `milestone` field. A row carried to a later milestone is counted under that milestone.
- Citations are `file:line` at `15a5ad6` unless a branch is named.
- Where two records disagree, both are given. Where nothing is recorded, the report says so.
- Facts read from GitHub rather than from the repository (the default branch, branch protection,
  pull requests) are marked as such, with the date they were read.

## 0. What the results rest on

### The benchmark is synthetic and velocity-separable

Every model figure in this report comes from FraudShield-EAC, the project's own synthetic dataset.
On the M4 gate rows, a single feature, `velocity_ratio_1h_vs_30d`, reaches 0.879 AUC on its own
(`docs/benchmarks/m4_gate_d8083dbc_v3.txt`, lines 8 and 35), and the benchmark is declared
"velocity-separable" (PB-46). The M4 review's first residual risk is that the thresholds "will be
easy to meet for that reason, not because the model is good" (`docs/reviews/M4/milestone-review.md:124-127`).
Any AUC below should be read against that baseline.

### Who verified

Every verification here is a test, a CI run, an evidence file, or a review on record. What the
review records say about their reviewers:

- **M0–M3:** the reviews give the role ("Principal Reviewer") and, for parts of M1 and M2,
  "independent". None says whether the reviewer was a person or an AI agent. M3's review does not
  name its reviewer at all (`docs/reviews/M3/milestone-review.md:3`).
- **M4:** "run in REVIEW MODE (I.1.1) by the same agent that wrote much of M4 … That does not
  substitute for a reviewer who did not write it" (`docs/reviews/M4/milestone-review.md:9-12`); the
  verdict repeats "the reviewer is still the author" (`:188`). No independent M4 review is recorded.
- **M5:** the milestone review "was written by the agent that built M5" (`docs/reviews/M5/milestone-review.md:13-15`);
  the principal reviews say "independent Principal Reviewer (did not write this code)". Passes 3 to 5
  exist only as entries in `docs/parallel/M5_updates.md` (`:606`, `:636`, `:728`), with no review file.
- **M6, M7, M8B, M9, M10:** reviewers are described as "fresh", "independent", or "wrote none of the
  reviewed work". Several state that they ran nothing:
  - M6 implementation-3: "Static only. Nothing was built or run"
    (`docs/reviews/M6/m6-decision-2026-10-17-implementation-3.md:27-28`);
  - M7 staff-auth: "The reviewer ran no builds … That deviates from I.1 rule 3"
    (`docs/reviews/M7/staff-auth.md:22-25`);
  - M9 images review 1: "I ran no Maven, Docker or Testcontainers" (`docs/reviews/M9/images-review-1.md:11`);
  - M8B's four P2 code reviews: "nothing was built and no tests were run" (branch
    `m8b/analyst-api`, `docs/reviews/M8B/p2-code-review-1.md:5-6`).
- **The project as a whole.** On `m11/paper`, the paper's disclosure section (text at
  `88f32d2:docs/research/paper/sections/10b-disclosure.tex`) stated that, by the author's account,
  every review in `docs/reviews/` was carried out by an AI agent, that the author raised individual
  findings in some of them (for example CR-19 of the M1 contracts review), and that "this work has had
  no independent human review". The section was removed at `5ce9965`, and the remaining mentions of
  AI agents at `5361d1c`, both recorded as "Owner, on the instruction of the owner's academic
  reviewer" (branch `m11/paper`, `docs/research/paper/REVIEW.md:44-45`). No record from that reviewer exists in the
  repository, and nothing records a human review of any part of the work.

**Consequence for this report:** "verified" below means verified by tests, CI and agent review. No
row is recorded as checked by an independent person.

## 1. Summary

| Milestone | On `main` | Tag | Register rows (status) | The gap that matters most |
|---|---|---|---|---|
| M0 bootstrap | yes | `m0-complete` | 2 DONE | The stack has never run on the reference laptop (owner decision); eleven governance items carry no closure |
| M1 contracts, data model | yes | `m1-complete` | 3 DONE, 1 DONE_WITH_DEVIATION | Four Must rows could not be met and were re-planned (ADR 0021) |
| M2 dataset | yes | `m2-complete` | 8 DONE, 2 VERIFIED_AT_REDUCED_SCALE, 1 REQUIRES_EXTERNAL_PARTY | 5M-row gate not met (about 1M rows); dataset not published; the tag's commit fails CI |
| M3 features | yes | `m3-complete` | 2 DONE, 1 DONE_WITH_DEVIATION | Exit criterion E3 not met (five engineered features above 0.80 AUC); the all-channels feature test claimed did not exist |
| M4 models, evaluation | yes | `m4-complete` | 14 DONE, 6 DONE_WITH_DEVIATION | 2 of the 11 reported gate metrics missed (ML-GATE-03, -06); reviewed only by its author |
| M5 scoring | yes | `m5-complete` | 6 DONE, 2 DONE_WITH_DEVIATION | Latency gate unmeasured; training–serving skew: served AUC 0.9611 against 0.9700 trained |
| M6 ingestion, decision | yes | `m6-complete` | 14 DONE, 7 DONE_WITH_DEVIATION | End-to-end latency recorded NOT MET (ADR 0059); the producers PB-70, PB-71 not started |
| M7 staff identity | yes | `m7-complete` | 6 DONE, 2 DONE_WITH_DEVIATION, 8 IN_PROGRESS | Tagged with 8 rows still open; 17 of 25 rows "proved in part" |
| M8 front-end | **no** (`m8/frontend-rebase`, 87 ahead) | none | 96 NOT_STARTED, 7 IN_PROGRESS on `main` | Gate not met; every screen runs on mocks; two CI gates red by the owner's choice |
| M8B analyst API | P1 only | none | 2 IN_PROGRESS | P2 unmerged; 34 of the 48 operations have no code |
| M9 infra, CI/CD, security | yes, except `m9/images`' last 2 commits | none | 24 NOT_STARTED, 4 IN_PROGRESS | Nothing applied to a cluster; data-store manifests not written; 15 dashboard panels and 7 alert rules not functional |
| M10 verification | yes (plan, harness, step 2) | none | 16 IN_PROGRESS, 18 NOT_STARTED | All 24 measured rows unmeasured; `verification_report.md` does not exist |
| M11 research | **no** (`m11/paper`, 97 ahead, 153 behind) | none | 5 NOT_STARTED, 1 IN_PROGRESS | The paper is not on `main`; no arXiv ID or submitted commit is recorded |
| M12 audit | this branch | none | none | Fresh-clone review (I.4) not run; this report is a draft |

No milestone from M8 on has met its gate.

## 2. Per milestone

### M0 — Bootstrap and governance

**Merged.** `main` was created by fast-forward to `83a9090` (`docs/reviews/M0/stack-gate-evidence.md:36`).
`m0-complete` is on `1b99782`.

**Verified.**
- "make up healthy": met "via CI (not reproducible on the reference laptop; owner decision)"
  (`docs/reviews/M0/milestone-review.md:101`), from seven consecutive green stack jobs (`:41-49`).
- CI green on the closing commits and on the tag (ci 35185686313, stack 35185686368, devcontainer
  35185686304; tag annotation).
- traceability-check: 258 rows, 0 errors (`milestone-review.md:30`, `:103`).
- Final verdict: "APPROVED_WITH_MINORS", with "0 BLOCKER, 0 MAJOR, 4 MINOR, 8 NIT" open (`:3`, `:12`).

**Unmet or open.**
- "The stack has never run on the reference laptop (owner decision)" (`milestone-review.md:197`).
- SeaweedFS replaces MinIO, a recorded deviation (ADR 0005).
- Governance items in `docs/backlog/governance.md` with no closure: GOV-1 (`:13`), GOV-2 (`:22`),
  GOV-3 (`:33`), GOV-4 (`:38`), GOV-5's default-branch half (`:46`), GOV-8 (`:71`), GOV-9 (`:74-76`),
  GOV-11 (`:89`), GOV-12 (`:96`), GOV-14 (`:106`) and GOV-15 (`:113`).
- **GOV-9, branch protection on `main`,** records that "the owner reported the `protect-main` ruleset
  and `main` as default branch; not verifiable from the build session". Read from GitHub on
  2026-10-25: the default branch is `main`; no ruleset exists; and `main` had no branch protection
  until this audit applied it (section 4). No repository record says when the default branch changed.

### M1 — Contracts and data model

**Merged.** Three contract and database branches, then `m1/close`; `m1-complete` on `a7e6896`
(ci 35235506707, 7/7 jobs).

**Verified.** OpenAPI 3.1 validates; `fs_app` cannot UPDATE or DELETE the append-only tables
(mutations MU1, MU3 killed); RLS isolation (MU4 killed) (`docs/reviews/M1/milestone-review.md:62-77`).
No mutation is recorded for the OpenAPI check. First verdict CHANGES_REQUIRED, 2 MAJOR, with two
mutations (MU5, MU6) that "SURVIVED" all 53 tests (`:66-67`); delta re-check "APPROVED_WITH_MINORS"
(`:240`), with MU5 and MU6 then killed (`:248-249`).

**Unmet, deviated or open.**
- D-31 DONE_WITH_DEVIATION: TimescaleDB forbids compression with RLS, so hypertables use an insert
  guard and security-barrier views, and `outbox_events` has no RLS (ADR 0017).
- Four Must rows could not be met and were re-planned (ADR 0021): D-20, NFR-SEC-03, D-32, D-49.
- Defects later traced to M1 work: the continuous aggregates were "implemented-but-untested" and the
  defect reached the feature layer (PB-36, open); `account_activity_hourly` refreshes 8 days while a
  30-day feature reads it (PB-38); no durable first-seen table (PB-37). Of the contract's 89
  operations, 48 had "no implementation and no owner" at M7's close (PB-121,
  `docs/backlog/product.md:1462`); they are now M8B's.
- Review conduct: the author removed the database reviewer's worktree mid-review, and the planned
  mutation tests were then run by the author (`docs/reviews/M1/database-response.md:9-15`).

### M2 — Dataset generator

**Merged.** `m2-complete` on `ed7a8d9`; `main` reached it by fast-forward on 2026-09-22
(`docs/research/lab_notebook.md:2261-2262`).

**Verified.** Distribution targets within ±0.5 pp and the realism checks, from the report on `main`
(`dataset/realism_report.md:34-53`); determinism across chunk sizes, tested at 6,000 rows only
(`docs/reviews/M2/milestone-review.md:145-169`); datasheet (RES-02 DONE).

**Unmet, deviated or open.**
- **The 5M-row gate is not met.** ML-DATA-01 and RES-01 are VERIFIED_AT_REDUCED_SCALE at about 1M
  rows. The full run is PB-25, recorded as blocked until a dataset workflow can be dispatched from
  the default branch (`docs/backlog/product.md:200`). The default branch is now `main` (GitHub,
  2026-10-25), so that blocker no longer holds as written; no dispatch is recorded.
- **The dataset is not published.** ML-DATA-08 REQUIRES_EXTERNAL_PARTY: HuggingFace and Zenodo need
  the owner's accounts.
- **The tag's commit fails CI.** `m2-complete` "sits on a commit whose python CI job fails"; "the tag
  was placed with no CI result on its commit at all" (`lab_notebook.md:2259-2290`).
- **A gate claim was true of the wrong thing.** "No single feature AUC > 0.80" held for dataset
  columns; M3 found five engineered features above it, and the claim (C-9) is marked refuted
  (`lab_notebook.md:1408-1412`). D-08 still reads DONE with no note of this.
- The delta re-check judged that the excluded event-delay channel "should be REJECTED as stated …
  gated by nothing" (`docs/reviews/M2/delta-recheck.md:27`); on `main` it is still "reported, not
  gated", at AUC 0.726 (`dataset/realism_report.md:38`).
- The M2 milestone review has no overall verdict line.
- **Defects found after the dataset review had closed, since fixed or guarded:** the M2 review files' provenance note is marked
  "Withdrawn 2026-09-21", and PB-52 records that the 1,012,522-row explanation "was never true",
  from a dirty working tree (`docs/backlog/product.md:864`); the dataset fingerprint read three
  columns of fourteen (PB-54, "Resolved: same day"); the datasheet's figures "match no run the
  repository can produce" (M-1), now guarded against the committed report (RES-02's notes).
- Register evidence is stale in several rows: ML-DATA-01, ML-DATA-02, ML-DATA-03, ML-DATA-05 and
  RES-01 cite row counts and shares from earlier draws than the report on `main`.

### M3 — Feature pipeline

**Merged.** `m3-complete` on `f8885d6`; `main` fast-forwarded to it on 2026-09-22. The tag preceded
the register: at the tag, six M3 rows still read NOT_STARTED, and PB-50 records that "the tag
records the owner's judgement; the register records the gate, and the two disagree".

**Verified.** `docs/features.md` generated; the 44 features computed, two of them constant (tag
annotation). The review found "Two MAJOR findings. No BLOCKER", both fixed at `2c80ef6`
(`docs/reviews/M3/milestone-review.md:7-14`). No verdict line exists.

**Unmet, deviated or open.**
- **The gate's "all 44 feature unit tests pass for all 6 channels" had no test.** M4's review found,
  as a MAJOR: "'All 44 features on all 6 channels' had no test. FR-02-02's evidence claimed one; no
  feature test used BANK_TRANSFER or ONLINE" (`docs/reviews/M4/milestone-review.md:146`, M4-15).
  It was fixed in M4 at `e5959f1`. FR-02-02's evidence entry still makes the claim.
- Exit criterion **E3 NOT MET**: five features above 0.80 AUC, strongest 0.894
  (`docs/traceability/m3_exit_criteria.md:322-336`). E2, E12 and E13 are "author-asserted".
- Redis/DB fallback: not met at the time; the fallback did not exist (PB-36). It reached `main` later
  as PB-69 (M6).
- p95 < 10 ms: never measured; carried to M10 (PB-51).
- FR-02-02 DONE_WITH_DEVIATION: six features with no source data (PB-44); `corridor_class` reaches
  two of its four classes (PB-43).
- E3 and E6 were first marked met "while the evidence they cited did not exist"
  (`m3_exit_criteria.md:306-311`). The author found three further instances of the review's main
  defect that the review had missed (`lab_notebook.md:1722-1729`).

### M4 — Models, calibration, explainability, evaluation

**Merged.** `m4-complete` on `72e7790`, tagged on the commit whose CI ran green on `main`.

**Verified.** The gate run `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (clean tree): 11 gate metrics
with bootstrap intervals, "9 of 11 gate metrics pass"; ONNX parity max diff 9.02e-07; baselines,
ablations, five seeds, LaTeX tables and figures. SHAP additivity within 0.001 (ML-GATE-10).
First pass "CHANGES_REQUIRED — 5 BLOCKER, 5 MAJOR"; final verdict "APPROVED_WITH_MINORS. Every
finding is fixed; the reviewer is still the author." (`docs/reviews/M4/milestone-review.md:6`, `:188`).

**Unmet, deviated or open.**
- **The gate names 13 metrics; 11 were reported.** ML-GATE-12 and -13 were left to M5, and are now
  open under M10.
- **Two gate metrics missed.** ML-GATE-03: recall at 0.60 is 0.736 against 0.880. ML-GATE-06: FNR
  0.264 against 12%. Both read DONE_WITH_DEVIATION by the owner's decision, which overruled ADR 0031's
  own objection that the status "reads as 'met, with a difference', which it was not"
  (`docs/adr/0031-closing-m4s-rows-and-the-two-that-fail.md:54-55`).
- FR-02-03, TEST-02, TEST-14 and D-06 are DONE_WITH_DEVIATION, each waiting on later milestones.
- **The gate figures describe the trained model, not the served one:** "no gate figure may be quoted
  as production behaviour" (`docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md:137`).
- The single-feature baseline is 0.879 AUC (section 0). The test period "has been looked at many
  times", so the gate run "is not a first look" (`milestone-review.md:122-130`).
- Records disagree on PB-67: ADR 0031's amendment and `M4_updates.md:17-21` call it concluded
  ("training volume is not the cause"); the ML-GATE-03 and -06 notes still call it untested, and the
  backlog entry has no closure.
- **Reviewed only by its author** (section 0).

### M5 — Scoring service

**Merged.** `m5-complete` on `0547539`; `main` fast-forwarded from it.

**Verified.** Six DONE rows (FR-02-01, -05, -08, -10, D-11, D-50), with ci 35950113744 (ml suite 545
passed). Memory growth 0.54 MB against 50 MB, from a laptop file that itself says "NOT GATE EVIDENCE
unless run on the dedicated machine"; ADR 0035 counts the memory clause as passing anyway. The
author's milestone review gave CHANGES_REQUIRED, 3 MAJOR (`docs/reviews/M5/milestone-review.md:8-11`);
the fifth and last pass gave "APPROVED, no BLOCKER, no MAJOR" (`docs/parallel/M5_updates.md:726-730`).

**Unmet, deviated or open.**
- **Latency gate not met.** FR-02-07, ML-GATE-12, TEST-10 and D-16 are open under M10. At 200 in
  flight on the laptop, client p50 was 1,164 ms at 183 req/s (`m5_serve_laptop_4af4e5e.json`); later
  laptop runs 647 and 670 ms. None is a gate figure (ADR 0010).
- **Training–serving skew.** Served AUC 0.9611 against 0.9700 as trained; 254 risk-tier changes;
  "162 of 725 frauds no longer reach the 0.60 flag threshold, a 22% fall in detections" (ADR 0034).
  Production skew is recorded as "**larger**", because three features could not be degraded in the
  measurement (ADR 0034:142-144). FR-02-09 open under M10; PB-72 requires a re-measurement that
  finds zero. PB-70, PB-71 and PB-72 have no backlog entry body (`docs/backlog/product.md:21-22`).
- ML-GATE-13, the shadow AUC delta, "does not yet exist": it needs a deployment.
- FR-02-06 and TEST-08 DONE_WITH_DEVIATION, their remaining clauses carried to M6/M7.
- Records disagree on M5's counts: ADR 0035 says "Nine rows are met" and "four open" but lists six
  met; the tag says six DONE and six IN_PROGRESS.

### M6 — Ingestion and decision engine

**Merged.** `m6-complete` on `b77008e` (ci 39022514824, stack, devcontainer green). PB-69's records
disagree on its route to `main` (`docs/parallel/M6_updates.md:507` against
`docs/walkthrough/SESSION_STATE.md:42`, `:61`).

**Verified.** Idempotency (100 identical submissions, 1 scored; 10,000-duplicate storm scored once);
ML-down and Kafka-down chaos tests (NFR-REL-01, -02 DONE); the ordering contract, "16 rows proved in
full and 22 in part"; last code delta check "APPROVED. 0 BLOCKER, 0 MAJOR, 0 MINOR, 1 NIT"
(`docs/reviews/M6/m6-decision-2026-10-17-implementation-3.md:471`), in a review that ran nothing.

**Unmet, deviated or open.**
- **End-to-end latency NOT MET** "on the hardware available" (ADR 0059:36-37), measured with a scorer
  double, so no model inference is in it. Laptop client p95 107–121 ms at 25–50 req/s, 392 ms at
  200 req/s. The one passing figure (42.1 ms) could not be reproduced by the reviewer (325.3 ms).
  Carried as PB-73; FR-01-01, FR-03-01, FR-03-03, TEST-05, D-13 and NFR-REL-05 open under M10.
- Batch row FR-01-06: "30.20 s once at load ~7 … not evidence" (PB-74).
- Seven DONE_WITH_DEVIATION rows, among them ML-DATA-07 ("NOT met in substance: five of 44 features
  are missing for every record"), FR-03-04 ("no real Africa's Talking delivery") and FR-03-05
  (exercised only by tests that issue the link directly; its test is the intermittent PB-158, seen
  four times).
- D-15 reads DONE, but "the forked-JVM kill test D-15 asks for is not built", and it holds for
  outages up to 10 minutes.
- PB-70 and PB-71, the producers that would end the skew, are owned by M6 and not started.
- Partly proved ordering invariants PB-80 to PB-101, due M10; PB-77 (a second SMS is possible), PB-79
  (an outage means the customer is never told).
- The September review rounds: "five fix rounds, four of which introduced a new defect"
  (`M6_updates.md:363`).
- **Defects later found in M6's work:** the M7 merge review found that the assembled API application
  "still refuses to start outside tests", an assembly neither M6 nor M7 had done, and that "M6's
  audit never reaches the D-32 chain": "M6 writes no `audit_events` rows at all"
  (`docs/reviews/M7/m7-merge-2026-10-17.md:60-61`). Both were fixed by M7 in `7282659`.

### M7 — Staff identity, authorisation, admin, audit

**Merged.** `m7-complete` on `71b0ec9` (`docs/parallel/M7_updates.md:381-383`). A status line
written before the tag still says M7 "is not merged and not tagged" (`:406`).
`docs/traceability/milestones.yaml` keeps M7 as current and not completed by the owner's decision of
2026-10-18, until its seven Must rows with no later home close (`:14-22`).

**Verified.** 84 operations × 9 callers = 756 calls answered as expected
(`docs/benchmarks/m7_evidence_fe757fa.md:31`), but the test takes its expected answers from
`ContractPolicy` itself (PB-104); bcrypt cost-12 > 100 ms; token-version invalidation max 5 ms, 2,005
ms with both signals lost, on a laptop and "not a statement about a production cluster" (`:36-38`).
Reviews ended APPROVED_WITH_MINORS after CHANGES_REQUIRED rounds (`docs/reviews/M7/staff-auth.md:9`,
`m7-merge-2026-10-17.md:7`, `m7-round2-2026-10-17.md:7`, `m7-round3-delta-2026-10-17.md:7`, `:70`,
`:80`). Under the deletion standard, "8 of 25 proved in full, 17 in part" (`M7_updates.md:163`).

**Unmet or open.**
- **Tagged with 8 rows IN_PROGRESS**: FR-07-01 (PB-104), FR-07-06 (PB-105), FR-07-08 (PB-106),
  FR-06-01 (PB-107; the Google set-up link "not built"), FR-06-02 (PB-108), NFR-REL-06 (PB-112), D-26
  (PB-110: "the behaviour is missing, not merely untested"), D-32 (PB-111). Seven of them are Must
  rows with "no later home" (`M7_updates.md:400-404`).
- Carried out: FR-06-06 and NFR-SEC-03 to M8B; D-51 to M9; D-19 to M10; UI clauses to M8.
- Tag annotation, "Not met": P28's two-filter sequence "proved by construction, not by a test";
  R2(b)'s counter check "rests on review".
- Defects later found in ApiKeyLifecycleTest, evidence for the DONE row FR-01-05: it "checks less
  than it claims" (PB-180), and is safe today only by an accident of the encoding (PB-181). PB-182:
  wall-clock assertions in gate tests, among them the bcrypt lower bound, which a faster runner breaks.
- Accepted residual risk R-13: an unannounced revocation can be missed for up to 10 minutes during a
  database outage.

### M8 — Front-end

**Not merged.** `main` has only a 14-file front-end skeleton. The work is on `m8/frontend-rebase`
(87 ahead, 153 behind; handover 2026-10-19: "M8 stops here … not merged", "The M8 gate is **not**
met"). Also unmerged: `m8/system-status-proposal` (ADR 0081, Proposed) and the superseded
`m8/frontend`, `m8/frontend-pre-rebase-40f5bdc` and `m8/first-paint-experiment` (whose one commit
not on `m8/frontend-rebase` is the same patch as one that is).

**Built on the branch.** Design tokens and eight components with Storybook and axe in jsdom; i18n in
en, rw, fr, sw; the generated API client; app shell, routing, PWA; sign-in, registration and recovery
pages; eight analyst screens, each through a review round. The last recorded test count, 482
component tests on the laptop at `5877a4f` (2026-09-22), predates the analyst screens; the handover
records CI at `1ec7ef1` with no count.

**Also on the branch only.** SRS v5.0 reviewed by the owner and adopted as a source for the sections the owner accepted (branch `docs/srs/v5_decisions.md`); defects D-52 to D-54 added; and
a new milestone, **M13 — Operational reporting**, added to the build prompt's D.3 for FR-08 (branch
`docs/parallel/M8_updates.md:56-68`). None of this is on `main`.

**Unmet.**
- **Every screen runs against mocks** (MSW): "A mock is not evidence." No row may leave IN_PROGRESS
  until M8B serves the operations on `main`.
- Five Playwright journeys on three browsers: not met. The SRS journeys on `main` (`tests/e2e/`) say
  "NOT YET EXECUTED" (`tests/e2e/README.md:3`).
- Mobile emulation matrix: no run recorded.
- Axe: in jsdom, which "does not measure contrast … or target size"; in Playwright on three browsers,
  but only over the shell and right-to-left pages, not the analyst screens.
- **Bundle budget red**: 209,715 B against 200 KB. The handover records that an earlier gate
  measurement "excluded the landing route".
- **3G first paint red**: 2.76 s against 2.5 s (PB-133). The owner keeps both gates red.
- SHAP chart < 500 ms: no chart exists; ADR 0084 makes it a table, accepted as "sufficient, not
  equivalent". Alert feed with 500 alerts < 1 s: not measured.
- Risk-officer, admin and the other shell sections are placeholders; no Google sign-in button; no
  sign-out control (PB-140, open part).
- No milestone review verdict: "Verdict: **not yet given.**" (branch `docs/reviews/M8/m8-frontend.md:4`).
- **The register on `main` shows none of M8's progress**: 96 NOT_STARTED, 7 IN_PROGRESS. The branch
  register has 139 M8 rows (36 IN_PROGRESS, 2 BLOCKED, 101 NOT_STARTED, none DONE), 36 of them absent
  from `main`. M8's backlog rows PB-131 to PB-140 exist only on the branch.

### M8B — Analyst API

M8B was created by the owner on 2026-10-18 for the 48 contract operations no milestone built
(PB-121), with the rule "It must be on `main` before M10 starts" (`docs/parallel/M8B_updates.md:18`).

**Merged.** P1, the seven alert and account reads; the scope (`docs/parallel/M8B_scope.md`, approved
2026-10-20). One P3 operation, `getDeploymentEnvironment`, is on `main` too, built by M9 rather than
M8B (`b745c83`, `docs/parallel/M9_updates.md:129`); M10's harness depends on it
(`backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/web/EnvironmentController.java:41`).

**On an unmerged branch.** `m8b/analyst-api` (53 ahead, 116 behind, last commit 2026-10-22): P2's six
decision operations, and three contract additions: the real-time channel (ADR 0115), reassignment
(ADR 0116) and team performance. P2's four code reviews found 6, 3, 1 and 0 MAJOR in turn; all four
were static ("nothing was built and no tests were run"), and the third adds "The new integration
tests have not run anywhere I can see". Four code commits landed after the answers to the fourth
review (`1433c5f`, `065c27d`, `289c1c3`, `e22c423`); none has a review. Two local branches,
`m8b/p2-assign` and `m8b/p2-figures`, were never pushed.

**Never started.** 34 of the 48 operations: the rest of P3 (system health, failed webhook
deliveries), P4 dual control and thresholds, P5 rules, P6 analytics and campaigns, P7 SAR, P8 models
and retraining. None has code on any branch. P6 needs campaign detection, P7 a SAR draft generator,
P8 a retraining orchestrator; none exists.

**Flagged to M12 by the owner (2026-10-22): an identity recorded but not proven.** The
decision-owner contract's operator exits, `resolve_contested_decision` and `release_failed_claim`,
take the acting risk officer's `staff_user_id` from their caller "and cannot authenticate it". The
database login is recorded beside it. "The officer named on a resolution has not been shown to be
the person who acted"; accountability rests on M9's runbook giving each operator an individual
login. The record asks M12 to decide whether that is acceptable evidence; if not, the remedy is an
authenticated operator endpoint, a frozen-contract change (branch `docs/parallel/M8B_updates.md:153-174`).
**Not decided:** this audit has not made that decision; it is recorded as open.

**Register.** FR-06-06 and NFR-SEC-03 are IN_PROGRESS under M8B.

### M9 — Observability, infrastructure, CI/CD, security

**Merged.** `m9/infra` by fast-forward at `39b12d7` (2026-10-22); the images work is on `main`.
`m9/images` has 2 unmerged commits: the ZAP findings PB-191 to PB-194 and review 5's re-check.

**Verified.** Five Grafana dashboards generated and compared in CI; alert rules with promtool tests
for 13 alerts; Kubernetes manifests validated offline; ZAP: "0 High-risk alerts; gate passed" (run
39897867857), unauthenticated, with 69 of 89 operations answered 401 and the management port not
scanned; gitleaks; Trivy "0 HIGH/CRITICAL with a fix" for dependencies and configuration (findings
with no fix are not counted); the source SBOM signed and verified.

**Unmet or open.**
- **Nothing has been applied to a cluster**; no deploy workflows; the CI stages lack integration,
  ML gate, staging and production.
- **Data-store manifests not written** (PostgreSQL/TimescaleDB, PII vault, Kafka, Redis, MLflow,
  object store). Without them, M10's chaos cases 02 to 04 "select nothing".
- **15 dashboard panels and 6 alert rules read metrics nothing emits** (PB-174 to PB-178); a 7th
  alert cannot fire because the ML health endpoint is disabled (PB-173). No ML worker exists (PB-172);
  the scorer manifest "cannot start the scorer" (PB-179).
- Argo canary: "never applied to a cluster; the design is withdrawn by the owner"; its analysis
  "fails every rollout" (PB-174).
- Not started: Loki and the collectors, the edge image, the scorer image (owner: "not now"),
  admission signature verification, Chaos Mesh.
- The alert rules, documents and evidence table have had "no independent review" (PB-183, open).
- M9 proposed status changes the register does not carry (`M9_updates.md:418-435`).

### M10 — Verification campaign

**Merged.** Everything M10 has done is on `main` (`m10/verification` = `main` = `15a5ad6`): the plan
(`docs/benchmarks/m10_plan.md`), the machine specification (`docs/benchmarks/m10_machine_spec.md`,
a two-machine shape), the ingest load harness and step 2 (`docs/parallel/M10_updates.md:212-462`).
Step 2 ran on `main` at the owner's request (`:218-221`).

**Verified: the ingest harness can fail, in one scenario.** Step 2's ten cases (eight cases plus two
mutants: copies of the harness with two fixed defects put back) all passed as specified, on a shared
runner at 2 requests a second, in Locust's local mode (run 39942490462 and later; `M10_updates.md:369-380`).
The wrong key, `synthetic_data: false`, an environment without the flag, an environment answering 503
and nothing listening each refused with exit 3 before any ingest; a key revoked mid-run stopped with
exit 4; the two mutants exited 5 and 6. Records on `fs.transactions.raw` are now matched one by one
against what the harness sent (PB-198; run 39968916860, all ten passed). **None of this is a
measurement** (ADR 0010).

**Step 2 is not finished** (`M10_updates.md:436-462`):
- Ingest: only the `sustained` scenario has run. The batch, duplicate-storm, idempotency-conflict,
  hold-follow-up and validation-mix scenarios have never run (PB-199). Distributed mode is not proved:
  refusals in a worker would not reach the master, which was found by reading Locust's source, not
  observed (PB-197).
- Chaos: its one experiment cannot run without a cluster with the manifests applied and Chaos Mesh,
  neither of which exists; cases 02 to 04 also need the data-store manifests, which are not written.
- Journeys: cannot run; they need M8's screens and M8B's operations on `main`.
- Open: the +119 of run 39942490462 has no established cause (PB-198); late records pass by the
  owner's decision, not on evidence.

**All 24 measured rows are unmeasured.** The plan allows no measured row before each harness has
been proved able to fail (`m10_plan.md:133-135`); the chaos and journey harnesses have not been. That
holds whatever the hardware, for every row except 24, the dataset run, which uses none of the
harnesses. Beyond it, the dedicated benchmark machine
has never been provisioned, which blocks every row that needs it, and it is not the only thing
missing. For rows 1 to 12 the plan's §2 lists each row's needs; rows 13 to 23 have no needs column
in the plan, and their needs below are read from its §1 and §3 order, as marked.

| Row | Needs | State |
|---|---|---|
| 1, 2 scoring and inference latency | benchmark machine, deployed scorer (§2) | neither exists; the scorer image is not started (`docs/parallel/M9_updates.md:190`) |
| 3 long scoring run | benchmark machine (§2) | not provisioned |
| 4 shadow AUC delta | a deployment running the shadow comparator for the full window; "**not** a short run" (§2) | no deployment |
| 5 skew re-measured to zero | deployed scorer, M6's producers PB-70 and PB-71, a verification set scored through the service and compared per feature, and the counter check (§2) | no scorer deployed; producers not started |
| 6 end-to-end decision latency | benchmark machine, whole system with the real scorer, `DecisionLatencyBenchmark` opted in (§2) | none exists |
| 7 full-size batch | benchmark machine; also the `batch` load scenario (§2) | not provisioned; the scenario has never run (PB-199) |
| 8 high-risk fan-out | whole system under load (§2) | no deployment |
| 9 account-profile fallback bound | deployed database with realistic history (§2) | no deployment |
| 10 event size at Kafka throughput | measured during row 11 (§2) | not run |
| 11 spool append latency | benchmark machine, the real storage class (§2); also the StatefulSet spool (PB-171, ADR 0092) | none exists |
| 12 orphan-spool chaos case | cluster, load, `tests/chaos/06-api-pod-loss-with-spool.yaml` (§2); also PB-171, ADR 0092 | no cluster |
| 13, 14 ingest throughput, error rate | read from §2's introduction (benchmark machine), §1 (the integrated system) and §3 step 6 (the specified duration, consumer lag, reconciliation) | none exists |
| 15 auto-block latency | read from §3: benchmark machine, the real scorer | none exists |
| 16 ensemble scoring latency | read from §3 (with rows 1, 2): benchmark machine, deployed scorer | none exists |
| 17 feature engineering latency | read from §2's title and §1: benchmark machine and a deployed feature store, which is Redis (FR-02-09), one of §1's data stores whose manifests are not written | none exists |
| 18 NFR-PERF-05 to 08 | read from §2's title and §1: benchmark machine and the console | not provisioned; console unmerged |
| 19 five failure cases (C.4) | read from §3 step 8 and §1: cluster, Chaos Mesh, data-store manifests, traffic | none exists |
| 20 duplicate storm | read from §3 step 7: load; the scenario has never run (PB-199) | not run |
| 21 five journeys on three browsers | read from §1 and §3: M8's console, deployed, with real data; M8B too, by the owner's direction, which the plan does not list (`M10_updates.md:455-457`); also dedicated hardware (`tests/e2e/README.md:3`) | unmerged |
| 22 device matrix | read from §3: the deployed console | unmerged |
| 23 security suite | read from §3: the shared environment | it does not exist: nothing has been applied to a cluster (M9, above) |
| 24 release-size dataset run | a runner, dispatched from the default branch (PB-25); **not** a benchmark-machine item (§2) | the default branch is now `main` (GitHub, 2026-10-25); no dispatch recorded |

`docs/benchmarks/verification_report.md`, M10's output, does not exist. The register's 34 M10 rows
are 16 IN_PROGRESS and 18 NOT_STARTED; none is final. The plan's own prerequisite table (§1) is
stale: it still says `main` carries only M0–M5.

Two owner directions conflict: M8B's record says M8B "must be on `main` before M10 starts", and the
owner asked for step 2 on `main` before M8B was there.

### M11 — Research artifacts

**Not merged.** The paper is on `m11/paper` (97 ahead of `main`, 153 behind, tip `5361d1c`). `main`
holds only `docs/research/paper/contributions.md` and an older `docs/parallel/M11_updates.md` (314
lines; the branch's is 526).

**Verified, on the branch.** Every number in the paper's text is a macro checked against its evidence
file: 1,051 macros, 0 problems (`audit_numbers.py`); no number typed into a section
(`check_numbers.py`). Numbers without evidence were cut, among them the 13 skew figures, three
sibling-leak figures and the 270 rows/s rate (`ff52933`, `a1ffbf2`). The audit's recorded limits
(branch `docs/parallel/M11_updates.md:500-523`): "more drift of the same kind is likely and has not
been looked for"; "The macro class is not checked … classification errors pass every check"; "The
audit cannot catch its own pattern errors"; and `contributions.md`, the one paper file on `main`,
"contradicts the paper".

**Unmet or open.**
- **The paper is not on `main`.** Merging it brings 97 commits that also change workflows, the
  dataset generator, ADRs 0028, 0031 and 0084, the README and the backlog.
- **No submission is recorded.** No arXiv ID, date or submitted commit is in the repository.
- **The disclosure was removed.** On 2026-10-19 the owner kept the disclosure "unchanged … including
  that the work has had no independent human review" (branch `docs/research/paper/REVIEW.md:41`). On
  2026-10-24 it was removed whole (`5ce9965`), and the remaining mentions of AI agents with it
  (`5361d1c`), each recorded as "Owner, on the instruction of the owner's academic reviewer"
  (`REVIEW.md:44-45`). No record from that reviewer exists.
- Register: RES-03 to RES-07 NOT_STARTED; D-09 IN_PROGRESS, its claims needing "primary sources only
  the author can supply". `CITATION.cff` exists on `main` while RES-07, the citation file, reads
  NOT_STARTED; the register and the file are not reconciled.
- Open: PB-143 (no regression test for the evidence-index fix), PB-144 (Appendix E's reword, open
  until merged), the skew re-run, and the latency figures, which are not measured for the gate.
- No milestone review exists. The paper's history and its reviews, all by agents, are in branch
  `docs/research/paper/REVIEW.md`.

### M12 — Final audit and handover

**Not done.** This report is its first part, on `m12/audit`. Not done:
- the full-system Principal Review from a fresh clone (Part I.4), and its record
  `docs/reviews/FINAL_SYSTEM_REVIEW.md`;
- this report, reviewed and accepted by the owner;
- the decision M8B flagged to M12 (the unauthenticated operator identity, above);
- the `m12-complete` and `v1.0.0` tags, the release notes and the `CHANGELOG.md` update.

## 3. Records that disagree with the repository

These were found while auditing. None is corrected on this branch.
- `docs/walkthrough/SESSION_STATE.md:68-69`, GOV-5 (`docs/backlog/governance.md:46`) and
  `docs/parallel/M9_updates.md:77` say or assume the default branch is `m0/bootstrap`; GitHub reports
  `main` (2026-10-25).
- GOV-9 is open, and records an owner-reported `protect-main` ruleset that does not exist (GitHub,
  2026-10-25). Branch protection now applies (section 4); the item has not been updated.
- `docs/parallel/M7_updates.md:406` says M7 is unmerged and untagged; it is both.
- `docs/benchmarks/m10_plan.md` §1 says `main` carries M0–M5 only.
- The register on `main` carries none of M8's branch progress.
- FR-02-02's evidence cites `test_completeness.py` for "all 44 computed for every channel"; that file
  has no channel test. The all-channels test M4 added is in `ml/tests/features/test_vector.py`
  (`e5959f1`).
- D-08 reads DONE with no note that its claim was refuted for engineered features.
- RES-07 reads NOT_STARTED while `CITATION.cff` exists.
- M9's gate table says ZAP "not run" (`M9_updates.md:527`, also `:430`, `:68`); ZAP has run (`:162-164`).
- The repository README points to "the paper's disclosure section" (`README.md:15`), which
  `m11/paper` no longer has.

## 4. Repository state (2026-10-25, read from GitHub)

- **Default branch:** `main`.
- **Branch protection on `main`:** applied by this audit on 2026-10-25 (observed in this session;
  GitHub records no date). The seven `ci` jobs, which
  run on every push, must pass on the commit, and the branch must be up to date; administrators are
  included; force-pushes and deletion are refused. Pull-request reviews and linear history are not
  required: `main` moves by fast-forward, and merging `m11/paper` needs a merge commit. As first
  applied, it also required the `infrastructure` job, which runs only when certain paths change and so
  would have refused every other commit; that was removed the same day, before anything was merged.
- **Pull requests:** #1, #2 and #3 (all against `m0/bootstrap`, opened 2026-09-22) closed without
  merging on 2026-10-25; every commit on their heads is on `main`. #4 (`m6/housekeeping`) was merged
  on 2026-10-17. No PR is open.
- **Unmerged branches with work not on `main`:** `m11/paper` (97), `m8/frontend-rebase` (87),
  `m8b/analyst-api` (53), `ml/full-volume` (13), `m6/featurestore-fallback` (11),
  `evaluate/pb66-full-volume` (11), `m8/system-status-proposal` (2), `m9/images` (2); superseded:
  `m8/frontend` and `m8/frontend-pre-rebase-40f5bdc` (37 each), `m8/first-paint-experiment` (no work
  not on `m8/frontend-rebase`) and `m11/paper-pre-rebase-af54957`. The paper's evidence and
  reproduction branches are contained in `m11/paper`.
