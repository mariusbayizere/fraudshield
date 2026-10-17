# Session state — M6 complete; M7 assembled with M6, awaiting the owner's review

Rewritten 2026-10-17 on `m7/staff-auth`. Facts only; where something is unverified, assumed or open
it says so. The previous version described the state at M4's close (2026-09-22) and was not updated
by M5 or M6.

## Read this first

- **`main` is at `b77008e`, tagged `m6-complete`.** M0 to M6 are complete
  (`docs/traceability/milestones.yaml`, `current: M7`). Each was closed on the owner's decision, with
  its open Must rows carried to the milestones that close them.
- **M7 (`m7/staff-auth`) is not merged and not tagged.** `main` is merged into it (`8c8cf48`). The
  merge round's review found that nobody assembled the API application and that M6's audit events
  never reached the hash chain; the owner gave both to M7, and this round did them
  (`docs/adr/0073-assembling-the-api-application.md`). Everything M7 owes is in
  `docs/parallel/M7_updates.md`, which is the resume point.
- **Owner decisions (2026-10-17):** D-15 resolved as option B (a verified key admitted for up to 10
  minutes of a PostgreSQL outage; R-13 records the residual risk); `DemoUserEntity` kept, with the
  reason in ADR 0068; the last-active-administrator guard kept as defence in depth (PB-104).
  **Open:** where the analyst API goes, which no milestone builds: 48 contract operations, scoped in
  M7_updates.md ("Unowned work") and PB-121.
- **The deletion standard governs every claim** (master prompt): a test is evidence for code only
  if deleting that code fails it. M7's rows are 8 of 25 proved in full and 17 in part; what is not
  proved is in the backlog (PB-102 to PB-124), one row each.

## Branches

| Branch | State |
|---|---|
| `main` | `b77008e`, `m6-complete` |
| `m7/staff-auth` | M7, merged with `main`; this round's commits on top |
| `m7/round2-wip` | scratch branch for this round's CI iterations; delete once the round is on `m7/staff-auth` |
| `m8/frontend`, `m9/infra`, `m10/verification`, `m11/paper` | in progress on their own branches |
| `m6/featurestore-fallback` | PB-69, not merged (its Java CI was red when last read) |
| `m4/generalisation` | the M4 tail with PB-76 (`bb1d17a`), not merged |

The default branch is still `m0/bootstrap`, a repository setting only the owner can change (GOV-9).

## Things that will bite whoever picks this up

- **Memory is the constraint** on this laptop (7.6 GB, often under 2.5 GB free). Check `free -h`
  before any build; one heavy job at a time; run Docker verification on GitHub Actions where a
  dispatch can. This machine has no local Maven repository, so Java is compiled and tested in CI.
- **Read CI at job level.** `ci` runs on every push; `stack` and `devcontainer` are path-filtered on
  push, so dispatch them for evidence (`gh workflow run stack.yml --ref <branch>`), and check that
  each job executed rather than being skipped.
- **Several sessions share `/home/marius/fraudshield`.** Work in a worktree (`/home/marius/fs-wt/…`)
  with its own `UV_PROJECT_ENVIRONMENT` and absolute paths.
- **`requirements_matrix.md` is generated** (`uv run fs-traceability render`), never merged by
  hand; `requirements.yaml`'s milestone column comes from `traceability_seed.py`, whose carries say
  why each row moved.
- **Claim an id before using it** (`docs/backlog/product.md`, claims table): ADR numbers, Flyway
  versions and backlog ids are global, and parallel branches have taken the same number twice.
- **A dependency's declared licence is not its licence**, and a root cause is proved by the
  mechanism, not by the fix working (lab notebook, 2026-10-17).
