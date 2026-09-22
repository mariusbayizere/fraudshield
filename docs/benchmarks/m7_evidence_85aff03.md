# M7 evidence at 85aff03 (final M7 state before merge)

- **Commit:** `85aff03` on `m7/staff-auth`. The tree was clean and was not edited during the run.
  This supersedes `m7_evidence_f1e9d48.md`. Since then:
  - `8f6bdba`: rows are locked by refresh;
  - `bac8cbb`: API-key rotation race test;
  - `85aff03`: licence elections and ADR text.
- **Machine and stack:** as in `m7_evidence_fe757fa.md`. **The laptop was heavily loaded:** load
  average 6.3 / 11.1 / 12.7 (1 / 5 / 15 min) at the start, from the owner's other work, against
  3–5 for the earlier runs.
- **Command:** `./mvnw -B -ntp -o -pl common,persistence,audit,auth,admin verify -fae`, run from
  10:51:40Z to 11:05:00Z on 2026-09-22.

## Result (`m7_verify_85aff03.txt`)

| Module | Tests | Failures | Checkstyle | SpotBugs | Coverage gate |
|---|---|---|---|---|---|
| common | 1,131 | 0 | 0 | 0 | met |
| persistence (V1-V12) | 60 | 0 | 0 | 0 | met |
| audit | 50 | 0 | 0 | 0 | met |
| auth | 134 | 0 | 0 | 0 | met |
| admin | 28 | 0 | 0 | 0 | met |

## M7 gate items

| Gate | `85aff03` | `f1e9d48` | `fe757fa` |
|---|---|---|---|
| Role × endpoint matrix (756 calls) | unlisted callers: 69 × 401 and 441 × 403; listed callers never refused except refresh without its cookie (9 × 401) | identical | identical |
| token_version invalidation < 5 s, with pub/sub | p50 7 ms, max 26 ms | 4 / 6 ms | 3 / 5 ms |
| same, announcement and Redis write lost | p50 2,009 ms, **max 4,620 ms** | 1,997 / 2,004 ms | 2,000 / 2,005 ms |
| Revoked API key → 401 < 5 s | with pub/sub max 103 ms; without, max 1,849 ms | 39 / 1,905 ms | 20 / 1,937 ms |

The worst-case invalidation passed, but with only 380 ms of margin. The expected value is about
2 s, the Redis TTL, which is where the p50 stayed (2,009 ms). The maximum is one outlier, delayed by
scheduling on an overloaded 4-CPU laptop. It is not a regression in the code: this
path's code has not changed since `f1e9d48`. CI should re-measure it on an unloaded runner before
the gate is cited.

Files: `m7_authorisation_matrix_85aff03.json`, `m7_token_version_invalidation_85aff03.json`,
`m7_api_key_revocation_85aff03.json`.
