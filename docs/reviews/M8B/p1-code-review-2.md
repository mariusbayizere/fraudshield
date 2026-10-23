# Review 2: P1's code for the analyst reads, the fixes to review 1

- **Subject:** `git diff 5298825 93332fe -- backend contracts docs/adr/0110-p1-contract-changes-for-the-analyst-reads.md`
  (commit `e89a43b`, "answer P1's code review, round 1"; `93332fe` only re-renders the matrix).
- **Branch / commit:** `m8b/analyst-api` at `93332fe`
- **Reviewer:** independent; wrote neither the code nor review 1. Read-only: no Maven, no Docker,
  no tests run. Every claim comes from reading the diff, the migrations (V1, V3, V4, V5, V80), the
  contract and its Python tests, `backend/pom.xml` and the module's sources. Checkstyle 14.1.0's
  `google_checks.xml` was not available offline, so the Checkstyle verdict below rests on the
  documented rule settings and on CI's own count of 12 warnings at `5298825`.
- **Date:** 2026-10-20
- **Paths:** `.../analyst/` = `backend/analyst/src/{main,test}/java/io/github/mariusbayizere/fraudshield/analyst/`

## Verdict: APPROVE_WITH_CHANGES

Both MAJORs are resolved in the code:
- every seeding write now sets the tenant inside one transaction, which satisfies V1's guard;
- the twelve Checkstyle sites are fixed, and the new code adds none.

Five of the seven MINORs are resolved, and the new assertions match what the code produces for
the seeded data. Approval has two conditions:
1. The module's tests and Checkstyle run green in CI at `93332fe` (or a later commit). The seeding
   has still never executed, and this review cannot run it.
2. Findings 6 and 8 are fixed, or recorded as deferred (new finding N1). The contract addendum
   gets the owner's acceptance (new finding N2).

## Findings of review 1

| # | Sev. | Finding | Status | Evidence |
|---|---|---|---|---|
| 1 | MAJOR | Seeding rejected by `tenant_insert_guard` | RESOLVED | See 1 below |
| 2 | MAJOR | Checkstyle (google_checks, warnings fail) | RESOLVED (CI to confirm) | See 2 below |
| 3 | MINOR | Anomaly-queue test seeds no fallback | RESOLVED | See 3 below |
| 4 | MINOR | Network 404 for a receive-only account | RESOLVED | `AccountQueries.java:145-152` checks `account_token = ? OR counterparty_token = ?`; `AnalystApiTest.java:439-442` covers it |
| 5 | MINOR | Explanation omits the anomaly score | RESOLVED | `AlertQueries.java:261,297-300`; see 5 below |
| 6 | MINOR | V80 index build locks ingest; compressed chunks | NOT RESOLVED | V80 and ADR 0112 point 8 unchanged; no deferral recorded (N1) |
| 7 | MINOR | 400/422 undeclared on the reads | RESOLVED | Contract and ADR 0110 point 8; see 7 below and N2 |
| 8 | MINOR | `InMemoryPorts` sets a base value without SHAP | NOT RESOLVED | `backend/decision/src/test/.../testing/InMemoryPorts.java` still returns `-4.25, -3.81` for every model score; no deferral recorded (N1) |
| 9 | MINOR | Network test asserts neither degree 2 nor edges | RESOLVED | See 9 below; traced through the code |

### 1. The `tenantWrite` helper

`AnalystIntegrationTest.java:255-273` does the following:
1. opens one `DB.superuser()` connection and sets `setAutoCommit(false)`;
2. runs `SELECT set_config('fraudshield.institution_id', ?, true)` (transaction-local);
3. runs the statement on the same connection;
4. commits. On an exception the connection closes uncommitted, so the transaction rolls back.

Checked against the migrations:
- **V1's guard is satisfied.** `forbid_other_institution()` (V1:57-67) compares
  `NEW.institution_id` with `current_institution()`. That function reads the same setting with
  `missing_ok` (V1:9). In every call site the tenant argument equals the row's `institution_id`:
  - `Tx.write` and `Tx.then` pass `institution`;
  - `Alert.write` and `label` pass `tx.institution`;
  - `AnalystApiTest.java:237-245` passes `BANK` for a `BANK` alert.
- **The guarded tables are covered.** `enable_tenant_view_isolation` puts the trigger only on
  `transactions` and `fraud_scores` (V3:48,78), and both now go through the helper.
- **The other seeded tables do not need it, but do no harm.** `transaction_ids`,
  `decision_states`, `alert_queue_entries`, `alert_decisions` and `label_events` use
  `enable_tenant_isolation` (RLS only), and the superuser bypasses RLS, so the tenant setting only
  makes the seeding uniform. None of them has an insert trigger that reads another setting
  (V4:108-110 are on the commit and undo tables, which are not seeded).
- **No unguarded write remains.** A grep of `backend/analyst/src/test` finds no `superuser(` call
  and no `DB.superuser()` outside the helper. Every `INSERT` (AnalystIntegrationTest:76, 85, 109,
  135, 154, 224, 286; AnalystApiTest:240) goes through `tenantWrite`. The remaining superuser
  writes are M7's `createAccount` into `users` and `DB.createInstitution`, which `auth`'s green
  suites already exercise; neither table has the guard.

### 2. Checkstyle

`backend/pom.xml:158-183` runs `google_checks.xml` on main and test sources, with
`violationSeverity=warning` and `failOnViolation=true`. The fix covers 12 sites, which matches
CI's count:
- **VariableDeclarationUsageDistance (8 sites).** Made `final`; google_checks sets
  `ignoreFinal=true`.
  - `AlertQueries` `base` and `margin`;
  - `AccountQueries.links` `sql`, a site review 1 missed;
  - `AnalystApiTest` `medium80`, `medium71`, `smallAlert` and `fallbackAlert`;
  - `AnalystInspectorTest` `alert`.
- **MissingSwitchDefault (1 site).** `default -> throw` was added at `AlertQueries.java:360`.
- **GoogleMethodName (2 sites).** The two `aXxx` methods were renamed.
- **AbbreviationAsWordInName (1 site).** `...AndACursor...` was renamed to `...AndEachCursor...`,
  a site review 1 missed.

I checked the new code for new warnings:
- **Usage distance.** Every new local is used within the allowed distance: `paged`, `path`, `one`,
  `unscored`, `unscoredAlert`, `edges` and `receiver`, plus `tenantWrite`'s resources.
- **Line length.** No line in the module exceeds 100 characters.
- **Names.** No identifier in the module has two consecutive capitals, apart from JDK
  `randomUUID` calls, which are not declarations. No method name starts with a single lowercase
  letter followed by a capital.
- **Javadoc.** `tenantWrite` (protected) has Javadoc with a summary sentence and all three
  `@param`s.
- **Switches.** The only switch *statement* in the module now has a default. The other three
  switches are expressions.

SpotBugs does not analyse tests (`includeTests` is unset), so `prepareStatement(sql)` in the helper
is not flagged. That is the same pattern as `AuthIntegrationTest.superuser`. CI must confirm all of
this.

### 3. The anomaly queue

The fallback transaction stores `anomaly_score = 0.0`, because the column is `NOT NULL`
(AnalystIntegrationTest:124). `ANOMALY_SCORE` turns it into NULL (`AlertQueries.java:109-110`), so
the sort key `-COALESCE(NULL, -1)` is `1`. The order of keys is then:

| Alert | Key |
|---|---|
| high (0.999) | −0.999 |
| low (0.991) | −0.991 |
| fallback | 1 |

The new assertions catch each regression:
- **Without the CASE**, the key would be −0.0. The order would still hold, but
  `items[2].anomaly_score` would be 0.0, not null, and `:165` fails.
- **Without the COALESCE**, the key is NULL. It still sorts last in the unpaged list, but the third
  page's `(k0, id) > (-0.991, low.id)` is NULL for that row, so it disappears. Then `paged` has two
  ids against three, and `:174` fails.

Paging with `limit=1` yields `high`, `low` and `fallback`, and the third page has no
`next_cursor`. The test is correct and now matches its name.

### 5. The explanation's anomaly score

The explanation query now selects `f.anomaly_score`. It passes `fallback ? null : anomaly_score`,
which matches the detail's `ANOMALY_SCORE` CASE, so both endpoints now give the same parameters.
- The ternary's type is `Double` (null against `double` boxes), so it cannot throw an NPE.
- `anomaly_score` is `NOT NULL`, so `getDouble` cannot misread NULL as 0.
- No test asserts the parameter (see the nits).

### 7. The contract

All seven reads now declare `"400"` and `"422"`, each referring to the `ValidationProblem` response.
Its schema already allows `status: [400, 422]` (`fraudshield-api.yaml:3007`). The seven reads are
`listAlerts`, `getAlert`, `getAlertExplanation`, `getScoringResult`, `listAccountTransactions`,
`getBehaviourProfile` and `getAccountNetwork`.
- **What it changes.** The change only adds status codes. No schema changes, so
  `test_compatibility.py` (which compares schemas) is unaffected.
- **Contract tests.** `test_validation_vectors.py:88-98` requires both statuses only on operations
  with a JSON body, so adding them to the GET operations is consistent with it.
- **No client to regenerate.** No generated client in `frontend/` needs regenerating.
- **The addendum.** ADR 0110 point 8 describes the change accurately. See N2 for its status.

### 9. The network assertions, traced

The seed is `account→partner` twice, `account→other` once, and `partner→X` once, where `X` is
`partnersFraud.counterparty`. All are at `NOW−1h`, inside the 90 days, with fresh tokens. The label
FRAUD is on `partnersFraud`.

1. **The existence check** is true.
2. **The first query** (`account` at either end) returns three links.
3. **`firstDegree`** counts partner 2 and other 1. The order is `{account:0, partner:1, other:1}`.
4. **`links(from, {partner, other}, false)`** returns all four transactions. In `secondDegree`:
   - `account`'s links are skipped, because `account` has degree 0 and is already chosen;
   - `partner→X` counts `X` via `partner` once;
   - `X→partner` is skipped, because `X` is not a degree-1 node.

   So `X` is added with degree 2.
5. **The nodes** are `[account, partner, other, X]`: four nodes, `X` at index 3 with degree 2.
   This matches `:413-424`.
6. **`links(from, nodes, true)`** returns the same four transactions. `edges` therefore gives
   `account→partner=2`, `account→other=1` and `partner→X=1`. This matches `:434-438`, which
   compares in any order.
7. **`confirmed_fraud`** holds for `partner`, the sender of the FRAUD-labelled transaction.
   This matches `:418`.
8. **The receive-only call for `other`** is known through `counterparty_token`. The first query
   gives `{other:0, account:1}`, so `nodes[0]` is `other`, as `:440-442` asserts.

## New findings

### N1. MINOR: findings 6 and 8 were dropped without a fix or a record

The commit message says it answers round 1. Yet these two are unchanged:
- `V80__analyst_reads.sql:18-19`: a plain `CREATE INDEX`, with no note in ADR 0112 point 8;
- `InMemoryPorts`: it still returns a base value and margin without SHAP.

Neither appears in an ADR, the backlog, or a reply in `docs/reviews/M8B/`. A MINOR may be deferred,
but not silently.

**Fix.** Do one of these:
- apply review 1's fixes. For 6, at least record in ADR 0112 point 8 that the index serves only the
  uncompressed 30 days and that the build locks inserts per chunk. For 8, return the two values
  only when `value >= 0.6`, and assert NULL base for NULL `shap_all` in `PostgresAdaptersTest`;
- or record each as a deferred item with a backlog id.

### N2. MINOR: ADR 0110's addendum changes the frozen contract without the owner's acceptance

ADR 0110's Context says the contract is frozen and that every change is "a reviewed proposal before
code" (ADR 0081). Yet:
- point 8 was added in a fix commit;
- the Status line (`0110...md:3-7`) still lists only the owner's earlier acceptance and the two
  contract reviews.

The change is additive and low-risk, but the record now claims an acceptance it does not have.

**Fix.**
- Get the owner's acceptance of point 8 and add it to the Status line with its date.
- Run the repository governance checks that ADR and contract edits require.

Nit: the new `"422"` entries sit between `"400"` and `"401"`. Elsewhere the file lists statuses in
numeric order (for example, the decision operation at `fraudshield-api.yaml:1043-1048` puts `"422"`
after `"409"`). Move them after `"404"` for consistency.

### Nits (no action required)

- No test asserts the explanation's `summary_parameters` for an ANOMALY entry with SHAP (finding 5).
  One assertion comparing the explanation's parameters with the detail's would pin it.
- `AlertQueries.java:360` `default -> throw` is unreachable, so it is one uncovered line. That is
  harmless against the 0.85 gate.
- The anomaly paging loop calls `analyst()` on every page, which creates a new user each time. One
  session, as `pagesFollowTheirCursor...` uses, is cheaper.

## Summary

| # | Severity | Item | Status |
|---|---|---|---|
| 1 | MAJOR | Tenant-less seeding | RESOLVED |
| 2 | MAJOR | Checkstyle | RESOLVED (12/12 sites; CI to confirm) |
| 3 | MINOR | Anomaly test without a fallback | RESOLVED |
| 4 | MINOR | Receive-only network 404 | RESOLVED |
| 5 | MINOR | Explanation's anomaly parameter | RESOLVED |
| 6 | MINOR | V80 index build and compressed chunks | NOT RESOLVED |
| 7 | MINOR | 400/422 undeclared | RESOLVED |
| 8 | MINOR | `InMemoryPorts` base value without SHAP | NOT RESOLVED |
| 9 | MINOR | Network degree 2 and edges | RESOLVED |
| N1 | MINOR | Findings 6 and 8 dropped without a fix or a record | New |
| N2 | MINOR | Contract addendum lacks the owner's acceptance; status order nit | New |
