# Session state: M7 tagged; the plan now completes every milestone before the paper

Rewritten 2026-10-18 on `main`; branch facts corrected 2026-10-20; M9's merge and handover added
2026-10-22. Facts only; anything
unverified, assumed or open says so.

## Read this first

- **`main` is at `39b12d7` (2026-10-22), with no tag: M9 fast-forwarded onto it.** The next bullet
  describes `main` as of 2026-10-18; what landed since is in `git log a7f0528..39b12d7`. M9's
  handover is the section "M9 handover" below.
- **On 2026-10-18 `main` was at `a7f0528`, with no tag.** It moved on from `71b0ec9`
  (`m7-complete`) by fast-forward, with no merge commit: plan steps 1 and 2 below, the M4 tail and
  PB-69, M7's carries (`m7/carries`: M7's open rows filed, M8B added), and traceability
  re-renders.
  - The evidence on `a7f0528`, read at job level, is all green: ci 39119443227 (all 7 jobs),
    stack 39119443214 and devcontainer 39119443243, each with its real job executed.
  - `m7-complete` stays on `71b0ec9`; its tag message lists what is met and what is not.
- **`milestones.yaml` still says `current: M7`, and M7 is not in `completed`.** On the owner's
  decision of 2026-10-18, M7's open Must rows were re-filed where a later milestone closes them:
  NFR-SEC-03 and FR-06-06 to M8B, D-51 to M9.
  - **Seven rows have no later home:** FR-06-01, FR-06-02, FR-07-01, FR-07-06, NFR-REL-06, D-26
    and D-32. What each lacks is M7's own backend work (PB-104 to PB-112), so M7 stays uncompleted
    until they close. Table: `docs/parallel/M7_updates.md`, "Tagged, and the carries".
- **The API-key cache follows `docs/architecture/api-key-cache-contract.md`** (option 1: stale use
  up to 10 minutes from the cached read; R-13 accepted).
  - ADR 0073 records why option 1 replaced the approved option 2 (30 s): draft 3 of option 2's
    contract was found unsound, and the owner's stop rule, set in advance, required the switch.
  - Review history: `docs/reviews/M7/m7-cache-contract-2026-10-18.md`.
  - **Rule for any change to that cache:** amend the contract first, have it reviewed on its own,
    then change the code.
- **The deletion standard governs every claim:** a test is evidence for code only if deleting that
  code fails it.

## The owner's plan (2026-10-18): every milestone before the paper

M11 waits until M8, M9, M10 and the measurements are done, so it folds in the final numbers once.

| # | Step | Notes |
|---|---|---|
| 1 | **The M4 tail** | **Done**: on `main` |
| 2 | **PB-69** | **Done**: on `main` through `m6/pb69-on-main`; `m6/featurestore-fallback` itself was not merged |
| 3 | **M8** | Rebased, and eight analyst screens built against contract mocks; stopped at the owner's handover on 2026-10-19, not merged (`M8_updates.md` section 10, on `m8/frontend-rebase`) |
| 4 | **M9** | **Merged** into `main` at `39b12d7` (2026-10-22) with its gaps recorded; the milestone gate is not met. Handover below and in `docs/parallel/M9_updates.md` section 0 |
| 4b | **M8B** | The analyst API, alongside M9; on `main` before M10 starts (`docs/parallel/M8B_updates.md`) |
| 5 | **M10** | The verification campaign, once M6 to M9 are all on `main` |
| 6 | **M11** | The paper, with final numbers |
| 7 | **M12** | The final audit and v1.0.0 |

**The analyst API is M8B** (owner, 2026-10-18; `docs/parallel/M8B_updates.md`).
- It runs alongside M9 and must be on `main` before M10.
- M8 builds against mocks generated from the frozen contract. Those mocks are not evidence that the
  API works: M8's rows that depend on it say so and do not read DONE.

## Branches

| Branch | State |
|---|---|
| `main` | `39b12d7`, untagged; `m7-complete` is `71b0ec9` |
| `m7/staff-auth`, `m7/carries`, `m4/generalisation`, `m6/pb69-on-main` | merged into `main` |
| `m6/featurestore-fallback` | not merged; PB-69 reached `main` through `m6/pb69-on-main` instead |
| `m8/frontend-rebase` | M8, stopped at the owner's handover (2026-10-19); not merged; `M8_updates.md` section 10 |
| `m8b/analyst-api` | M8B, in progress; P1 reached `main` before `39b12d7` (merge `749e457`); the rest not merged |
| `m9/infra` | M9, merged into `main` by fast-forward at `39b12d7` on 2026-10-22 |
| `m8/frontend` | superseded by `m8/frontend-rebase`; not merged |
| `m10/verification`, `m11/paper` | in progress on their own branches |

`m7/round2-wip` was deleted on 2026-10-18. The default branch is still `m0/bootstrap`, a repository
setting only the owner can change (GOV-9).

## M9 handover (2026-10-22)

The full lists are in `docs/parallel/M9_updates.md`, section 0.

- **How it merged:** by fast-forward at `39b12d7`, on the owner's decision to merge with its gaps
  recorded. An independent review covered only the three workflows and `k8s_policy.py` before the
  merge; the alert rules, the documents and the evidence table wait for PB-183. No M9 requirement
  is `DONE`, and the milestone gate is not met.
- **Functional:** the `infrastructure` workflow (rules and unit tests for all 13 alerts, routing, dashboards as
  code, the metric catalogue guard, kubeconform and `k8s_policy.py` on Deployments, StatefulSets
  and DaemonSets); the `security` workflow (Trivy on dependencies and on the rendered manifests);
  the `supply-chain` workflow's source SBOM, signed and verified only on `main`.
- **Not functional, with backlog ids:** the stranded spool (PB-171), no ML worker (PB-172), the
  disabled ML probe (PB-173), 6 alerts, 15 panels and the canary's error-rate check reading
  metrics nothing emits (PB-174 to PB-178; ADR 0093 accepted, E.10 not yet amended), the scorer's
  manifest (PB-179), the post-merge review (PB-183), the policy check's gaps (PB-184 to PB-189;
  PB-184 must close before, or in the same change as, the ML worker or any Job), the workflows'
  minor findings (PB-190) and D-51's WireMock stubs (PB-113). The canary design is
  withdrawn; nothing has been applied to a cluster; ZAP has never run; no image or deploy
  workflow exists.
- **What M10 needs from M9 that does not exist:** data-store manifests with the
  `fraudshield.io/datastore` label, Chaos Mesh in its own namespace, and, inferred from the
  campaign's scope, service images and a way to deploy them; also, from PB-171 rather than M10's
  list, the StatefulSet spool (PB-171) and ADR 0092 for the orphan-spool chaos case and the fsync benchmark.
- **M9's identifier reservation is used up:** PB-171 to PB-190 are all taken. ADR 0092 is claimed
  and not written.

## Things that will bite whoever picks this up

- **Memory is the constraint.** The laptop has 7.6 GB, often under 2 GB free.
  - Check `free -h` before any build, and stop below 1.5 GB.
  - Run one heavy job at a time.
  - Run Docker verification on GitHub Actions wherever a dispatch can do it.
  - There is no local Maven repository: Java is compiled and tested in CI only.
- **Read CI at job level.** `ci` runs on every push. `stack` and `devcontainer` are path-filtered on
  push, so dispatch them (`gh workflow run stack.yml --ref <branch>`) and check that each job ran
  rather than being skipped.
- **Traceability, in order.** After editing `requirements.yaml`, run `fs-traceability-seed`, then
  `fs-traceability render`, then `fs-traceability check`. A re-seed can re-wrap notes, which makes
  the generated matrix stale. That failed CI twice on 2026-10-18.
- **Commit messages.** Chain `fs-commit-msg` and `git push` with `&&`, never `;`: a failed check was
  once pushed anyway.
- **Several sessions share `/home/marius/fraudshield`.** Work in a worktree (`/home/marius/fs-wt/…`)
  with its own `UV_PROJECT_ENVIRONMENT`, and use absolute paths.
- **Claim an id before using it** (`docs/backlog/product.md`, claims table). ADR numbers, Flyway
  versions and backlog ids are global.
