# Review 1: P1's code for the analyst reads (ADR 0112)

- **Subject:** `git diff ac6580b 5298825 -- backend docs/adr/0112-p1-analyst-reads-implementation.md`
- **Branch / commit:** `m8b/analyst-api` at `5298825`
- **Reviewer:** independent; wrote none of this work. Read-only: no Maven, no Docker, no tests
  run. Every claim below comes from reading the diff, the migrations (V1, V3 to V5, V10, V11,
  V66, V80), the contract, ADRs 0110 and 0112, and the code patterns it copies. Where a
  finding depends on tool behaviour I could not run (Checkstyle 14.1.0, TimescaleDB), it says so.
- **Date:** 2026-10-20
- **Paths:** `.../analyst/` = `backend/analyst/src/{main,test}/java/io/github/mariusbayizere/fraudshield/analyst/`

## Verdict: REQUEST_CHANGES

The production code is mostly sound:
- the SQL matches the schema;
- the keyset cursor is correct;
- the responses match their closed schemas;
- the decision-path change is consistent end to end.

The integration tests, though, cannot pass as written. Every one that seeds a transaction
inserts into the `transactions` and `fraud_scores` hypertables as the superuser, without
setting the tenant. V1's `tenant_insert_guard` trigger rejects that (finding 1). As a result
`AnalystApiTest` and `AnalystInspectorTest` fail, and NFR-SEC-03's inspector proves nothing
until the seeding is fixed. Several Checkstyle violations under google_checks (warnings fail)
are also likely (finding 2). Neither problem is hard to fix. Once both are fixed, and the tests
have run green in CI, I expect APPROVE_WITH_CHANGES on the MINORs.

## What I checked and found correct

- **Joins and columns.**
  - `alert_queue_entries.fraud_score_id` = `v_fraud_scores.id` (plus `transaction_id`), as
    `PostgresSink` writes it (`scoringResultId` into both).
  - `v_transactions` is joined on `transaction_id`, which is unique per institution through
    `transaction_ids`.
  - `v_alert_decision_state` (`security_invoker`) exposes `d.*`, `state` and `committed_at`.
    Every column selected exists.
- **Tenant isolation.**
  - `fs_app` has SELECT on every relation read: `v_transactions`, `v_fraud_scores`,
    `alert_queue_entries`, `alert_decisions`, `v_alert_decision_state`, `users`,
    `decision_states`, `label_events` and `institutions` (V11).
  - Every relation is either RLS-protected or a tenant view.
  - Another institution's alert reads as 404.
- **The keyset cursor.**
  - Every key is ascending (DESC by negation) and never NULL: `COALESCE(..., -1)` puts NULL
    anomaly scores and NULL expected losses last.
  - Ties break on `e.id`, and the comparison is one row comparison.
  - The `NEWEST` key is epoch microseconds as `double`. That stays below 2^53 and is exact.
    Jackson writes doubles in round-trip form, so cursor keys survive the JSON hop.
  - The `::` cast binds tighter than unary minus, so `-(...)::double precision` is right.
  - A cursor carries its order (`PRIORITY:<queue>`), so it is refused under another order.
  - The account history uses a separate (timestamp, id) cursor at microsecond precision. It is
    correct.
- **SQL injection.**
  - Only constants are concatenated: column names, the sort expressions, the queue predicates,
    and runs of `?` sized by the bound list.
  - The IN lists in `AccountQueries` are at most 50 tokens each, because the node set is capped
    first. So the bind-parameter limit cannot be reached.
- **Contract shapes.** For `AlertPage`, `AlertSummary` and `AlertDetail`:
  - every required property is present, and no extra property is sent;
  - `expected_loss_rwf` is omitted when NULL (optional, not nullable);
  - `Views.decimal` emits `DecimalAmount` (no exponent, `"0"` for zero);
  - `Instant.toString()` always ends in `Z`.

  The same holds for `Explanation`, `ScoringResult`, `AccountTransactionPage`,
  `BehaviourProfile` and `AccountNetwork`. `summary_parameters` never holds a null.
- **Rules from the contract and ADRs 0110 and 0112, all implemented as written.**
  - Queue membership: the default outcome is PENDING and IN_REVIEW; the escalated rule is "rank
    of target ≤ rank of caller, or the caller's ESCALATE on the original".
  - `queue=all` priority, and the anomaly order with nulls last.
  - The amount filters on `amount_rwf`, inclusive at both ends; `from` inclusive, `to`
    exclusive.
  - `analyst_id`, assigned or decided.
  - The decision state: only CONFIRM_FRAUD and MARK_LEGITIMATE read the view's state.
  - The 90-day window before the compared transaction, and hours in each transaction's
    currency-country offset (the same derivation as `serving/features.py:115,240`).
  - Buckets from the lowest to the highest non-empty one; nearest-rank percentiles (I checked
    the `ceil(p/100.0*n)` floating-point rank against exact arithmetic for n = 10 to 4999, with
    no difference).
  - The 90% hour range, including wrap-around and the earliest start.
  - The n < 10 rule, the device and counterparty-country null cases, and the 5% channel rule.
  - The network: selection, the strongest single link for degree 2, the cap at 50, the edges,
    and `confirmed_fraud` as the latest label by availability, then id.
- **The decision path.**
  - `ScoringRecord` gains two components. All six construction sites are updated, including
    `withTier`.
  - The `FactCodec` decode treats an absent field as null, for old spool records.
  - `ScoringContract` sets both null when `shap_all` is empty. `cond ? null : double` boxes,
    so it cannot throw an NPE.
  - The `PostgresSink` INSERT has 23 columns and 23 placeholders, and indices 22 and 23 are
    `setObject(..., Types.DOUBLE)`.
  - `DecisionService`'s fallback passes null for both.
- **V80.**
  - Adding a nullable column without a default to a compressed hypertable is supported, as
    V66 already did.
  - `CREATE OR REPLACE VIEW` appends the new columns at the end and keeps the existing grants
    and the `security_barrier` option.
- **Wiring.**
  - `AnalystAutoConfiguration` mirrors `AdminAutoConfiguration`, and the imports file lists it.
  - `backend/pom.xml` lists the module, and the ingest pom depends on it.
  - `.mvn` lives in `backend/`, so `${maven.multiModuleProjectDirectory}/../dataset/...`
    resolves to the repository's `dataset/` from `backend/analyst` as well. It is the same
    mechanism `auth` uses for `contracts/openapi`.
  - A `classpath*:` pattern with a fixed root directory works inside a jar, because the Maven
    jar plugin writes directory entries.
  - Five packs exist, each with a distinct currency.
- **Test APIs.**
  - The Jackson 3 calls `properties()`, `isString()` and `asString()` exist.
  - AssertJ on `JsonNode` (an `Iterable`) is used elsewhere, for example in notify.
  - The networknt `SchemaRegistry` usage copies decision's `ContractSchemas`.
- **The NFR-SEC-03 coverage check.** The inspector compares its case keys with the operation
  ids of every handler in the analyst package. A new handler without a case, or without
  `@ContractOperation`, therefore fails it.
- **Coverage.** If the integration tests run, roughly 35 to 40 of about 630 main lines stay
  uncovered: the `from`/`to`/`analyst_id`/`amount_max` filters, the analyst branch of a
  decision record, the `CountryPacks` error paths and two summary keys. That is about 94%,
  above 0.85. Coverage is moot while finding 1 stands.

## Findings

### 1. MAJOR: the integration tests' seeding is rejected by the hypertables' tenant guard, so `AnalystApiTest` and `AnalystInspectorTest` cannot pass

`AnalystIntegrationTest.Tx.write()` inserts into `fraudshield.transactions`
(`.../analyst/AnalystIntegrationTest.java:78`) and `fraudshield.fraud_scores` (`:101`) through
`AuthIntegrationTest.superuser(...)`. That opens a fresh superuser connection per statement and
sets nothing (`backend/auth/src/test/.../auth/testing/AuthIntegrationTest.java:238-249`;
`TestDatabase.superuser()` at `backend/persistence/src/test/.../TestDatabase.java:132-135` adds
no options).

Both tables carry `tenant_insert_guard`. V3 calls `enable_tenant_view_isolation`, and
`V1__tenancy_and_common_functions.sql:57-77` defines the guard:

```sql
IF NEW.institution_id IS DISTINCT FROM fraudshield.current_institution() THEN
  RAISE EXCEPTION ... USING ERRCODE = 'insufficient_privilege';
```

With `fraudshield.institution_id` unset, `current_institution()` is NULL, and the trigger
raises. Superusers bypass RLS but not triggers. The repository's own precedents set the tenant
first: `DemoDataSeeder.java:129` (`set_config('fraudshield.institution_id', ?, true)`) and
`SchemaPoliciesTest.tenant(...)`. Every test that calls `Tx.write()` therefore throws
`IllegalStateException` before any request is made. That is 12 of the 13 tests in
`AnalystApiTest`, and `AnalystInspectorTest`. Only `AnalystAuthorisationTest` survives, so
NFR-SEC-03's inspector currently cannot run green.

**Fix.** Add a seeding helper in `AnalystIntegrationTest` that does the work on one superuser
connection:
1. open the connection with `setAutoCommit(false)`;
2. run `SELECT set_config('fraudshield.institution_id', ?, true)` with `tx.institution`;
3. run the `transactions` and `fraud_scores` inserts (the others may stay as they are);
4. commit.

Then run the module's tests in CI (`-pl analyst -am`) before re-review. Their seeding has
evidently never executed.

### 2. MAJOR (likely CI failure): Checkstyle violations under google_checks with `violationSeverity=warning`

I could not run Checkstyle 14.1.0. Each item below follows the documented rule, and each
matches a fix this repository has already made or avoided.

The VariableDeclarationUsageDistance check has a precedent: `fe757fa` made
`adminLockRequested` `final` to satisfy it. Allowed distance is 3; declarations between are not
counted; the initialisation-sequence exemption does not apply because the preceding statements
call methods on different objects. The sites:
- `.../analyst/alerts/AlertQueries.java:274` `double base`: first used at `:287`, distance 4.
- `.../analyst/alerts/AlertQueries.java:276` `double margin`: first used at `:288`, distance 5.
- `.../analyst/AnalystApiTest.java:57` and `:58` `medium80` and `medium71`: first used at
  `:68`, distance 5.
- `.../analyst/AnalystApiTest.java:104` `smallAlert`: distance 4.
- `.../analyst/AnalystApiTest.java:249` `fallbackAlert`: distance 5.
- `.../analyst/AnalystInspectorTest.java:71` `alert`: first used in the second `cases.put`,
  distance 5.

The MissingSwitchDefault check:
- `.../analyst/alerts/AlertQueries.java:342`: `switch (queue) { case ... -> }` is a switch
  *statement* over an enum without `default`. The check exempts switch expressions and pattern
  switches, not arrow-form statements. Every other enum switch statement in the repository has
  a `default`, for example `RuleCompiler.java:135` and `FraudShieldProperties.java:133`.

The MethodName check (`^[a-z][a-z0-9]...`, so the second character may not be upper case):
- `.../analyst/AnalystApiTest.java:274` `aFallbackScoringResultCarriesAll44FeaturesAsMissing`.
- `.../analyst/accounts/NetworksTest.java:31`
  `aSecondDegreeNodeCountsItsStrongestLinkToAnyOneNeighbour`.

No other method in the backend begins `a` followed by a capital.

**Fix.**
- Declare `base` and `margin` `final`, or read them where they are used.
- Declare the test locals `final`, or move each declaration next to its first use.
- Make `queue()` `sql.append(switch (queue) { ... })`, or add a `default` that throws.
- Rename the two methods, for example `fallbackScoringResultCarries...` and
  `secondDegreeNodeCounts...`.
- Run `mvn -pl analyst checkstyle:check` (or let CI) before re-review.

### 3. MINOR: the anomaly-queue test names a rule it does not exercise

`.../analyst/AnalystApiTest.java:134`, `theAnomalyQueueIsOrderedByAnomalyScoreWithFallbacksLast`,
seeds two model-scored entries and no fallback. The nulls-last rule
(`-COALESCE(anomaly, -1)`, `AlertQueries.java:364`) and the "null when the fallback decided"
rule (`ANOMALY_SCORE`, `:109-110`) are therefore untested. If the COALESCE is removed, the
test still passes: PostgreSQL's ascending order puts NULLs last anyway. What breaks instead is
paging, because `(NULL, id) > (?, ?)` is never true.

**Fix.** Seed a third ANOMALY entry with `fallback = true`. Assert that it comes last with
`anomaly_score` null, and page with `limit=1` across it.

### 4. MINOR: `getAccountNetwork` answers 404 for an account that only receives

`.../analyst/accounts/AccountQueries.java:145-150` checks only `account_token = ?`. An account
that appears only as `counterparty_token` has a network: it is degree 0 to its senders. Yet it
is reported as unknown. Such an account is typically a receiving or mule account, and the
console reaches it by clicking a degree-1 node. The method's own Javadoc ("empty when the
account has no transaction") does not match this. ADR 0112 point 6 states the 404 rule only
for the history.

**Fix.** Use `EXISTS (... WHERE account_token = ? OR counterparty_token = ?)`, or state in ADR
0112 that the network, like the history, needs an outgoing transaction.

### 5. MINOR: the explanation's summary parameters differ from the detail's for the same alert

`AlertQueries.java:296` passes `null` for the anomaly score. `detail` passes it (`:242`), and
ADR 0112 point 3 says the parameters include "the anomaly score when there is one". The two
endpoints use the same key (`summary.anomaly` for an ANOMALY entry) with different parameters.
This is rarely visible, since an ANOMALY entry's score is usually below 0.60 and has no SHAP.

**Fix.** Select `ANOMALY_SCORE` in the explanation query and pass it through.

### 6. MINOR: V80's index build blocks the decision path's inserts, and it does not cover compressed chunks

`V80__analyst_reads.sql:18-19` runs a plain `CREATE INDEX` on the `transactions` hypertable.
In production this takes a SHARE lock per chunk for the duration of the build, which blocks the
ingest path's INSERTs. And V10 compresses chunks after 30 days with
`segmentby institution_id, account_token`. So for 60 of the network's 90 days, a lookup by
counterparty still decompresses every segment of the institution, and the index does not help.
CI runs on an empty database with no compressed chunks, so CI proves neither point.

**Fix.**
- Consider `WITH (timescaledb.transaction_per_chunk)`, which TimescaleDB supports for
  hypertable index builds.
- Record in ADR 0112 point 8 that the index serves only the uncompressed 30 days.
- Verify on a database with compressed chunks, under TimescaleDB 2.30, that the statement
  succeeds.

### 7. MINOR: some error statuses are not declared on their operations

These operations return statuses their contract entries do not declare:
- `listAccountTransactions`, `getBehaviourProfile` and `getAccountNetwork` declare no 400 or
  422. Yet they answer 400 `not_a_token` for a malformed path token
  (`AccountController.java:288-292`), and 422 for a bad cursor or an out-of-range `limit`.
- `listAlerts` declares only `"400"` for validation, yet answers 422 (`invalid_format`,
  `unsupported_value`, `duplicate_items`). ADR 0112 point 5 itself says 422.

The same pattern exists in M7's audit search, so this is not new. Generated clients still
reach these statuses only through `default`.

**Fix.** Record the deviation in ADR 0112. Alternatively, file a contract amendment adding
`"422"` (and `"400"` where a path token is validated) to these operations.

### 8. MINOR: the decision test double contradicts the invariant it now tests

`backend/decision/src/test/.../testing/InMemoryPorts.java:147-149` gives every model score
`shapBaseValue -4.25`, even with `shapAll` null and a score below 0.60. That is the opposite of
`ScoringContract`'s rule (null without SHAP). The new `PostgresAdaptersTest` assertion
(`:195-200`, "every non-fallback row has −4.25") holds only because of that. A sink that wrote a
base value without SHAP would therefore pass.

**Fix.** Return the two values only when `value >= 0.6`, matching `ScorerDouble`, and assert
that rows with `shap_all IS NULL` have `shap_base_value IS NULL`.

### 9. MINOR: the network test does not check degree 2 or the edges

`.../analyst/AnalystApiTest.java:367-400` seeds a degree-2 node (the partner's counterparty) but
never asserts it. It checks only `edges` `isNotEmpty()`. Unit tests cover the selection, but
nothing end to end covers:
- the second query (`links(from, neighbours, false)`);
- the both-ends edge query.

**Fix.** Assert the fourth node's token and degree 2. Assert the exact edge set: account→partner
with count 2, account→other with count 1, and partner→X with count 1.

## Summary

| # | Severity | Finding |
|---|---|---|
| 1 | MAJOR | Seeding inserts into `transactions` and `fraud_scores` as superuser without a tenant; `tenant_insert_guard` raises; 12 API tests and the NFR-SEC-03 inspector cannot pass |
| 2 | MAJOR | Likely Checkstyle failures: usage distance (AlertQueries:274/276, tests), MissingSwitchDefault (AlertQueries:342), MethodName (two `aXxx` tests) |
| 3 | MINOR | The anomaly-queue test claims "fallbacks last" but seeds no fallback; nulls-last and keyset-over-NULL untested |
| 4 | MINOR | The network 404s for a receive-only account |
| 5 | MINOR | The explanation omits the `anomaly_score` parameter that the detail sends |
| 6 | MINOR | The V80 index build locks out ingest inserts and does not help compressed chunks; unverified on compressed data |
| 7 | MINOR | 400 and 422 statuses undeclared on the account operations, 422 on `listAlerts` |
| 8 | MINOR | `InMemoryPorts` sets a base value without SHAP, so the sink test cannot catch that mismatch |
| 9 | MINOR | The network integration test asserts neither degree 2 nor the exact edges |
