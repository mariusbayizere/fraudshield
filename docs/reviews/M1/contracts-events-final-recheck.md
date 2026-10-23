# Re-check of the events final review: m1/contracts-events

- **Branch / commit:** `m1/contracts-events` at `0781e6f428b1f2351b84abe8a85aadcf5576d551` (fixes in `fc4ccf6`)
- **Previous review:** `contracts-events-final-review.md` at `e5276e8` (CHANGES_REQUIRED: 1 MAJOR, 5 MINOR, 5 NIT)
- **Reviewer:** independent Principal Reviewer (build prompt Part I.2), in an isolated worktree
- **Scope (owner pace direction):** F-01 in depth, F-02 … F-06 briefly, BLOCKER and MAJOR only; new MINOR or NIT issues are backlog candidates
- **Verdict: APPROVED_WITH_MINORS** (0 BLOCKER, 0 MAJOR open; 5 backlog candidates)

## Checks run

| Check | Result |
|---|---|
| `uv sync --all-packages --locked` | exit 0 |
| `cd backend && ./mvnw -B -ntp verify -pl common` | BUILD SUCCESS, 1131 tests, 0 failures |
| `cd contracts && uv run pytest -q` | 487 passed, coverage 93.21% |
| `uv run pytest tests/test_proto_breaking.py` | 27 passed (buf ran; none skipped) |
| Java mutations on `DualControlWorkflow` (`-Dtest=DualControlWorkflowTest`) | 11 run, 10 caught (table below) |
| buf rule mutations on `buf.yaml` (F-04 spot check) | 3 run, 3 caught |
| Manual reading: `DualControlWorkflow`, `ConfigChange`, `ChangeStatus`, `ConfigAuditEvent`, `DualControlException`, `directionFrom` of both settings types, OpenAPI propose/withdrawal/approval/rejection paths and `ConfigChange` schema, authorisation matrix, ADR 0014 §3, ADR 0015, ADR 0016 | see F-01 analysis |
| `tools/bin/verify-branch-commits` | not run (optional; CI on the head is green) |
| CI job-level results for 0781e6f | all green (below) |

Docker was not used. After every mutation the worktree was restored with `git checkout -- .` and `git status` was clean.

## F-01 analysis

- **Emergency tightening cannot be blocked.** In `propose`, the only refusals after the role check are a stale `base_version`, `NO_CHANGE`, and `OPEN_CHANGE_EXISTS`, and the last applies only when the new change is a LOOSENING. An open loosening is superseded; an open unconfirmed tightening is folded. A stale version is a normal optimistic-concurrency retry, not a block.
- **Fold-in restores the true baseline.** The folded change takes `existing.previous()` as its baseline. Direction is computed element-wise against the settings in effect (the earlier tightening S1), and every element's order is total and monotone (thresholds and rate lower, volume lower, reset longer, `DECLINE_AND_VERIFY`; any window change is loosening), so S2 ≤ S1 < B0 implies S2 is a strict tightening of B0 as well. A revert by deadline (`revertOverdue`) or by rejection (`reject`) applies `change.previous()`, which is B0. The superseded change leaves the open set, so it is never reverted twice.
- **No unconfirmed tightening becomes permanent without a second officer**, except by the documented chaining: each fold starts a new 24-hour deadline, so one officer can keep a tightening in force by proposing a further tightening before each deadline. It can only become `CONFIRMED` through `approve`, which refuses self-review. `withdraw` cannot touch it (status must be `PENDING_APPROVAL`), so a withdrawal can neither freeze nor undo it.
- **A loosening never takes effect without a different officer.** A loosening is only ever `PENDING_APPROVAL`; it is applied only in `approve` after the self-review check. It cannot supersede or fold anything (refused while any change of its kind is open), and a fold can never contain a loosening element (such a proposal classifies as LOOSENING and is refused). Rejection of a tightening restores the dual-controlled baseline, which is the designed behaviour.
- **Withdrawal:** proposer only (`NOT_PROPOSER`, 403), RISK_OFFICER only, `PENDING_APPROVAL` only (`CHANGE_NOT_OPEN`, 409). Settings are not touched.
- **Audit events:** every transition emits one event: `PROPOSED`, `APPLIED_PENDING_CONFIRMATION`, `SUPERSEDED` (actor = the superseding officer, emitted after all validation), `APPROVED`, `CONFIRMED`, `REJECTED`, `REVERTED` (actor null for the deadline), `WITHDRAWN`. Complete, with the linkage gap noted as backlog B-3.
- **Contract and domain agree:** `ConfigChangeStatus` gains `SUPERSEDED` and `WITHDRAWN`; the direction-conditional status enums match the domain (tightening: APPLIED_PENDING_CONFIRMATION, CONFIRMED, REVERTED, SUPERSEDED; loosening: PENDING_APPROVAL, APPROVED, REJECTED, SUPERSEDED, WITHDRAWN). `POST /config-changes/{change_id}/withdrawal` documents 403 `not-change-proposer`, 404, 409 `change-not-open`; the matrix has `withdrawConfigChange` for RISK_OFFICER with the same rules; `not-change-proposer` is in the problem catalogue. `test_domain_refusals_match_the_contract` parses the Java refusals and checks them against the contract. ADR 0014 §3 describes the same rules.

## Mutation table

| # | Mutation (DualControlWorkflow.java) | Caught | Failing test |
|---|---|---|---|
| M1 | Tightening refused while a loosening is open | YES | `tighteningSupersedesPendingLooseningProposal` (error: OPEN_CHANGE_EXISTS) |
| M2 | Folded change keeps the current settings as its baseline instead of the old change's | YES | `secondTighteningFoldsInTheFirstSoOneRevertRestoresTheBaseline` |
| M3 | Withdrawal allowed for any open change (tightening withdrawable) | YES | `proposerCanWithdrawLooseningProposalButNotTighteningChange` |
| M4 | Non-proposer may withdraw | YES | `proposerCanWithdrawLooseningProposalButNotTighteningChange` |
| M5 | Loosening accepted while another change of its kind is open | YES | `looseningIsRefusedWhileAnotherChangeOfItsKindIsOpen` |
| M6 | Superseded change left open (status not updated) | YES | fold test and supersede test |
| M7 | `SUPERSEDED` audit event dropped | YES | `tighteningSupersedesPendingLooseningProposal` |
| M8 | `WITHDRAWN` audit event dropped | YES | `proposerCanWithdrawLooseningProposalButNotTighteningChange` |
| M9 | Tightening refused while an unconfirmed tightening is open (no fold) | YES | fold test (error) |
| M10 | Withdrawal not restricted to RISK_OFFICER | YES | `proposerCanWithdrawLooseningProposalButNotTighteningChange` |
| M11 | Rejection of a folded tightening restores the superseded change's settings instead of the baseline | **NO** | none; backlog B-1 (implementation is correct) |

buf rule mutations (F-04 spot check): excluding `MESSAGE_NO_DELETE` caught (1 failed); excluding `FIELD_SAME_ONEOF` caught (1 failed); removing `ENUM_VALUE_NO_DELETE_UNLESS_NAME_RESERVED` caught (1 failed).

## Status of earlier findings

| # | Sev | Status | Evidence |
|---|---|---|---|
| F-01 | MAJOR | **RESOLVED** | Analysis above; mutations M1–M10 caught; contract, matrix and ADR 0014 §3 consistent with the domain |
| F-02 | MINOR | RESOLVED | `change_version` removed from `ConfigReviewRequest` and `ConfigRejectionRequest` (and examples); approval `comment` is passed to `reviewed(...)` as the review reason; `CHANGE_NOT_FOUND` 404 in domain and contract; `test_domain_refusals_match_the_contract` and `unknownChangesAreNotFound` pass |
| F-03 | MINOR | RESOLVED | 5 threshold and 8 breaker parameterised cases, each asserting direction, workflow status and (breaker) applied settings |
| F-04 | MINOR | RESOLVED | 7 new proto cases in `test_proto_breaking.py` (27 pass); 3 of the author's rule mutations re-run and caught; ADR 0016 wording corrected |
| F-05 | MINOR | RESOLVED (documented) | ADR 0015 states the selective per-commit argument and its limit; full CI on the head runs every suite |
| F-06 | MINOR | RESOLVED (documented); owner action open | ADR 0015 §2 no longer relies on the ruleset; owner to confirm the `main` ruleset exists |

F-07 … F-11 (NIT) were not re-checked in depth, per the pace direction; the response table records their resolutions, and F-11 is backlog GOV-15.

## New findings

No BLOCKER. No MAJOR.

| # | Label | Finding |
|---|---|---|
| B-1 | backlog (test gap) | Rejecting a *folded* tightening is not tested for restoring the true baseline (M11 survived), and confirming a folded change is not tested for keeping both tightenings. The code uses `change.previous()` on both revert paths, so behaviour is correct today. Suggest: extend the fold test with a rejection branch asserting `current == defaults()` and a confirmation branch asserting `current == bothTighter`. |
| B-2 | backlog (docs) | For a folded change, `previous` is the earlier baseline, not the settings at `base_version`. `ConfigChange.previous` javadoc still says "settings in effect when proposed", and the OpenAPI `ConfigChange.previous`/`base_version` properties have no description of this. An API or audit consumer could mis-read a folded change as a larger single step. |
| B-3 | backlog (audit) | Neither the `SUPERSEDED` event nor `ConfigChange` records which change superseded it; the link is only reconstructable by kind and timestamp. After the folded change is reverted, the superseded change stays `SUPERSEDED` with `effective_at` set and no `reverted_at`. Consider a `superseded_by` field. |
| B-4 | backlog (docs) | ADR 0014 §3 says a fold gets "a new 24-hour deadline" but does not state the consequences: (a) one officer can keep an unconfirmed tightening in force indefinitely by chaining small tightenings; (b) when officer B folds officer A's tightening, A may confirm B's change, so A's own tightening is kept with A's confirmation (acceptable, since B proposed the complete settings, but worth stating). Consider an alert or cap on chained folds. |
| B-5 | backlog (NIT) | `DOMAIN_OPERATIONS` in `test_authorisation_matrix.py` lists only a subset of operations per refusal (for example `ROLE_NOT_PERMITTED` omits `rejectConfigChange` and `proposeCircuitBreakerChange`; `SELF_REVIEW` is fine). Coverage of the refusal-to-contract mapping is partial. |

## CI evidence (unauthenticated GitHub REST API, head_sha 0781e6f)

| Run | Workflow | Conclusion | Jobs |
|---|---|---|---|
| 35210048396 | ci | success | frontend success; gitleaks success; traceability/defect register/scope guard/commit messages success; java (checkstyle, junit, spotbugs, jacoco) success; python (ruff, mypy --strict, pytest) success; pre-commit hooks success; dependency licence inventory success |
| 35210048377 | stack | success | detect stack input changes success; core compose stack smoke test skipped (no stack inputs changed) |
| 35210048455 | devcontainer | success | detect devcontainer input changes success; devcontainer build skipped (no devcontainer inputs changed) |

All jobs report `head_sha` 0781e6f. The skipped jobs are path-gated and were green at the previous review (N-01 evidence).

## Merge decision

No BLOCKER or MAJOR is open and CI is green for 0781e6f. **Fast-forward of `m1/contracts-events` to `main` is allowed.** B-1 … B-5 go to the backlog (docs/backlog/product.md PB-1 … PB-5); the F-06 owner action (confirm the `main` ruleset) remains open and does not block the merge.
