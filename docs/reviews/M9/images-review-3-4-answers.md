# M9 images, reviews 3 and 4: answers

Review 3 (`images-review-3.md`, `ca3653b` and `146d987`: 1 BLOCKER, 2 MAJOR, 6 MINOR, 5 NOTE) and
review 4 (`images-review-4.md`, `a150edc`: 0 BLOCKER, 0 MAJOR, 4 MINOR, 4 NOTE). The owner decided
review 3's blocker and the port on 2026-10-23, and ordered the coverage floor kept exactly as
written.

| # | Finding | Answer |
|---|---|---|
| R3 B1 | ZAP's import fails on the self-referring RuleExpression | Fixed in `a150edc` (owner's decision): ZAP scans a copy of the served contract with the three nested references cut; the step fails unless exactly three are cut |
| R3 M1 | The coverage floor does not show "every path reached" | Not changed (owner: keep the floor exactly as written). What it reads, and the 6 paths off the scanned address, are now stated in `M9_updates.md` 0b. The job's name still says "every path reached"; that wording is the owner's to revisit |
| R3 M2 | "Imports every operation" stated as fact; "not run yet" stale; 401/429 make "reached" weak | Fixed in the job comment and in 0b, which now gives both runs and the limits |
| R3 m1 | `-O` with the relative server is unverified | Settled by run 39897867857: the requests carry `/api/v1` |
| R3 m2 | Nothing bounds the active scan's duration | Not changed: the scan took about 1.5 minutes in run 39897867857, inside the job's 60-minute limit |
| R3 m3 | Commits attributed to the wrong pieces | Fixed in 0b's table |
| R3 m4 | Review 2's majors called "fixed" | Fixed: 0b says the answers are the author's and not re-reviewed |
| R3 m5 | 0b's opening overclaims | Fixed |
| R3 m6 | Stale "ZAP never ran" statements | PB-190 item 2 and R-18 updated (R-18 corrected again after review 5, M1). The stale lines in `M9_updates.md` sections 5 and 7 and in `SESSION_STATE.md` are left as written; 0b supersedes them on this branch |
| R3 N5 | "Carries NCCL" is loose | Fixed: NCCL is a separate wheel |
| R3 N1-N4 | Notes | Recorded |
| R4 m1 | The `-P 8090` comment gives the wrong cause | Fixed: the warning came from an add-on reinstall, the daemon was on a free port; `-P` kept |
| R4 m2 | The operation list; /rules/preview's body is a bare RuleExpression | Fixed in the comment and the step's summary |
| R4 m3 | Two mechanisms stated as fact | Marked INFERRED |
| R4 m4 | NOT TESTED only in a YAML comment | Fixed: written to the run's summary, and the diff kept in the artifact (`zap/contract-scan.diff`) |
| R4 n1 | The checks are not a cycle check | Said in the step's comment |
| R4 n2 | The rule operations need a bearer token and have no handler on this branch | Recorded: the cut removes nothing this unauthenticated scan reaches past a 401 (INFERRED) |
| R4 n3 | The OpenAPI add-on is updated at run time (v58.0.0), not pinned by the image digest; review 3 named v57.0.0 | Recorded; the add-on version is not pinned |
| R4 n4 | Facts about the floor | Recorded |

## Review 5 (`images-review-5.md`, `d522d74`: 1 BLOCKER, 2 MAJOR, 4 MINOR, 5 NOTE)

`d522d74` was never merged. It was rebuilt without the wheel as the commit that carries these
answers; `d522d74` stays only on the remote branch until it is replaced.

| # | Finding | Answer |
|---|---|---|
| B1 | A 3.8 MB shellcheck wheel committed at the repository root | Removed by rebuilding the commit, so it never reaches `main`'s history. It came from a `pip download` fallback run in the worktree, then `git add -A` |
| M1 | 0b's opening and R-18 say no run scanned an API operation | Fixed: run 39897867857 reached all 89 operations; 0b's opening quotes section 0's four clauses correctly |
| M2 | `/verify/{token}` was scanned; the actuator paths were reached on 8080 | Fixed in 0b |
| m1 | The 500's trigger misdescribed | Fixed: `?=` (an empty parameter name) and the POST; the same token with `?lang=en` got 200 |
| m2 | Alert 100001's source | Fixed: ZAP's script list; the contract declares `text/html` |
| m3 | 70 vs 69 + 1 | Fixed: 69 answered 401, `POST /auth/refresh` 403 |
| m4 | The R3 m6 answer named the wrong sections | Fixed: sections 5 and 7 |
| n1 | Both Medium alerts are rule 10055 | Fixed |
| n3 | Cite the measured scan time | Fixed in R3 m2 |
| n4 | Wording not shown by the artifact | "JSON" dropped from the CORP line; 429s stated as observed; PB-190's "skipping" corrected |
| n2, n5 | Notes | Recorded |
