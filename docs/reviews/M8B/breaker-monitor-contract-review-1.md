# Review 1 of the breaker-monitor contract, draft 1

- **Subject:** `docs/architecture/breaker-monitor-contract.md`, draft 1, on branch `m8b/analyst-api` at
  commit 5873764.
- **Reviewer:** an independent, adversarial reviewer who wrote none of this work.
- **Method:** the contract was read against the code with file:line references, against the
  configuration contract (`risk-configuration-propagation-contract.md`, C1 to C4 and A2), and
  against review 3 of that contract (M1, m1 and m2). No build, tests, Docker or network were used.

## Verdict: UNSOUND

The core safety idea is sound. B1 publishes a monotonic P(I). B2 makes (b) and (c) atomic with the
compare-and-set. Together they do stop a stale instance from undoing a published change. That fixes
review 3's M1 *safety* failure.

Four MAJORs remain:
- **Liveness is lost.** When no live instance is current, nobody evaluates the institution's
  breakers at all, for as long as that lasts (M1).
- **Database restores.** Redis-held versions outlive a database restore and freeze breakers
  permanently (M2).
- **Rolling deploys.** Old-release monitors write without the new checks and leave a false recorded
  version (M3).
- **§4's numbers.** The bound under-counts passes, and its N ≤ 40 is false at the stated E (M4).

Each MAJOR has a fix of a few sentences.

---

## MAJOR findings

### M1. MAJOR: when no live instance is current for I, nobody evaluates I's breakers, indefinitely (B3, B4, B7, §4)

**Rule.**
- B3: "An instance whose v(i, I) < P(I) skips evaluation for institution I."
- B7: "A stale instance can write for at most one pass … Any current instance republishes on its
  next pass."
- §4 bounds hold "from the first moment any instance has installed and published it."

**Counterexample.**
1. Instances A and B serve institution I at version 4.
2. Version 5 commits. A installs it, publishes P(I) = 5 (B1), and evaluates under 5.
3. B's loads fail. The configuration contract keeps the previous snapshot on a failed load (C4 step
   2; today `JpaConfiguration.java:117-127`). B therefore stays at 4.
4. A exits: a crash, a scale-down, or a node drain.
5. From then on, B skips I on every pass (B3), and no other instance exists.
   - Every breaker of I is frozen.
   - An open breaker never closes, so every transaction to that MCC is flagged
     (`DecisionService.java:185, 194`).
   - A breach under the settings in force, version 5, never opens a breaker.
6. The freeze lasts until B's loads recover, a time nothing bounds. The configuration contract
   leaves the fail-closed question open (its Q1).

**It is not "not yet in force".** Version 5 *was* in force by the contract's own definition (B4),
because it was published. The breaker is wrong against the current settings indefinitely. That is
worse than today, where B would at least evaluate under 4.

**B7's bound has the same hole.** After P(I) is lost, the next publisher may be B, which raises P(I)
only to 4. B2(b) then passes for B. B writes under 4, on every key whose recorded version is below
5, for as long as no current instance runs. "At most one pass" assumes a live current instance.

**Evidence.**
- The contract has no rule for "no instance is current". §4 and B7 both assume one exists.
- A failed refresh keeps the old snapshot: `JpaConfiguration.java:123-124`, and configuration
  contract C4 step 2.

**Fix.**
1. **Reload on sight.** In B3, an instance that sees v(i, I) < P(I) requests an immediate load of
   I. If the load succeeds, it becomes current, which turns a stale survivor into a current one
   whenever its database is reachable at all.
2. **Liveness metric and alert.** Add `fs_breaker_unevaluated_seconds{institution}`: the time since
   any instance last evaluated I, which can be measured as a `last_eval_at` field written beside
   P(I). Alert on it.
3. **State the liveness condition.** "I's breakers are evaluated only while some live instance is
   current; otherwise they freeze." Tie it to the configuration contract's Q1.
4. **Restate B7's bound** as "one pass, provided a current instance is live".
5. **Add a property:** with the only current instance stopped and the survivor's loads failing, an
   alert fires within X s.

### M2. MAJOR: Redis-held versions outlive a database restore, so every instance is stale and every breaker freezes (B1, B2(c), A1)

**Rule.**
- A1 relies on the configuration contract.
- That contract's A2 handles a restore or a lossy failover by restarting every API instance,
  which clears the in-memory versions.

**Counterexample.**
1. P(I) = 7, and several state hashes record version 7.
2. PostgreSQL is restored to a point where I's highest breaker version is 6.
3. Every instance is restarted, as the runbook requires, and loads version 6.
4. Redis is untouched. P(I) is still 7, because B1 only raises it.
5. Every instance now has v(i, I) = 6 < P(I) = 7. Every instance skips I (B3), and B2(b) would
   refuse their writes anyway.
6. I's breakers are frozen until two more versions are committed.
7. Version number 7 can then be *reused* for different settings. That is exactly the hazard A2
   names. A restored version 7 matches the recorded 7 and passes (c), whatever was written under
   the lost 7.

**Evidence.**
- Configuration contract A2: "Snapshots and versions are memory-only. After any restore … every
  API instance is restarted."
- This contract adds versions that are *not* memory-only (`fs:mcc:settings-version:<I>`, and the
  recorded version in the state hash), and no runbook step clears them.

**Fix.**
1. **Runbook.** The restore runbook deletes `fs:mcc:settings-version:*` and every state hash's
   recorded-version field (or the state hashes themselves, accepting the B7 behaviour), *before*
   instances restart.
2. **Detection.** The configuration probe returns the database's highest breaker version for I
   (C4, `risk_configuration_versions()`). A probe max below P(I) is impossible under A2, so it
   means a restore. Count it, alert, and reset P(I) to the probe max with a compare-and-set.
3. **Add a property** for that detection.

### M3. MAJOR: during a rolling deploy, old-release monitors write without checks and leave a false recorded version (B2, P1, P2)

**Rule.** B2 says "A write stores the caller's settings version as the recorded version". P2 says
"never overwritten by a write under a version below v".

**Counterexample.**
1. During a rolling deploy, an old-release instance O runs today's `SET_STATE`
   (`RedisCircuitBreakers.java:35-42`). It checks only the change counter, and it writes only
   `version`, `open`, `opened_at` and `last_breach_at`.
2. A new-release instance at version 5 opens a breaker and records 5.
3. O is still at settings 4, and the old release has no notion of P(I). It closes the breaker under
   the shorter clean reset of version 4. That is the very defect the contract removes (§1 last
   bullet; P4).
4. The recorded-version field survives O's `HSET` with the value 5. Every new-release instance then
   believes the closed state was written under 5.
5. A new-release instance at 5 does not reopen it, because B4 forbids re-deciding. It reopens only on
   a fresh breach.

**Evidence.**
- `RedisCircuitBreakers.java:37-40`: the `HSET` names four fields and leaves any others untouched.
- The contract has no deploy section. The configuration contract lists its own rolling-deploy
  costs (§6) and this one lists none.

**Fix.** Do one of these:
- **Ship in two steps.** Ship a release whose old-format writer refuses to write when the hash has
  a recorded-version field. For example, `SET_STATE` returns 0 if `HEXISTS settings_version`.
  Activate B1 and B2 only after every instance runs that release.
- **Or accept the cost** in §6, with its bound: the deploy's duration plus clean reset.

Add a property either way.

### M4. MAJOR: §4's bound under-counts the pass, and N ≤ 40 is false for E up to 1 s (§4, B5)

**Rule.**
- §4: every decision sees the effect by `t + R + 2C + 2(R_m + E)`.
- "With R = R_m = 5 s, E under 1 s, and the configuration contract's worst-case C, that allows
  N ≤ 40."
- B5: `isOpen` is "at most R_m + E old".

**The arithmetic.**
- The configuration contract's worst case is `C = T_P + ⌈N/K⌉·T_L = 2 + 2⌈N/4⌉` (its C4).
- At N = 40, C = 22, so `5 + 44 + 2(5 + E) = 59 + 2E`. That is at most 60 only if E ≤ 0.5 s.
  "E under 1 s" therefore does not give N ≤ 40.
- Review 3's m1 derived N ≤ 40 with *one* evaluation term (`d_eval` < 1 s). This draft has two.

**The pass accounting.**
- B1 publishes at the *start* of a pass. The new state is written somewhere *within* that pass, so
  up to E later. The "one pass to write" therefore costs R_m + 2E from install, not R_m + E.
- The monitor is scheduled with fixed delay (`BackgroundTasks.java:90-101`, `scheduleWithFixedDelay`).
  The gap between two reads of the same key on one instance is then up to `R_m + 2E` (end of pass n,
  the delay, then deep into pass n+1), not `R_m + E`. B6 says "every R_m" but not "fixed rate".
- **Corrected bound, fixed rate:** `t + R + 2C + 2R_m + 3E`.
  - With E < 1 s that is `≤ 5 + 2C + 13`, which needs C ≤ 21, so ⌈N/4⌉ ≤ 9 and **N ≤ 36**.
  - Check: N = 36 gives C = 20 and 58 s; N = 37 gives C = 22 and 62 s.
- **Fixed delay** gives `2R_m + 4E` and also N ≤ 36, at 59 s.

**Fix.**
- Make B6 say fixed rate, or state the fixed-delay terms.
- Restate B5 as `R_m + 2E` under fixed delay.
- Restate §4 with the in-pass write term and N ≤ 36.
- Make E a budgeted, alerted quantity (m6).

---

## MINOR findings

### m1. MINOR: P(I)'s key name breaks the hash-tag convention, so B2's single script is cross-slot under Redis Cluster (B1, B2)

- **The key names.** `RedisKeys.java:8-9` states the convention that keys sharing a slot use a hash
  tag. The state key is `fs:{<I>}:mccstate:<mcc>` (`:28-29`), but P(I) is `fs:mcc:settings-version:<I>`.
- **The consequence.** A Lua step that reads P(I) and writes the state touches two slots, which
  Redis Cluster refuses (CROSSSLOT).
- **Why it works today.** The deployment is standalone (`docker-compose.yml:118`; a single
  `StatefulRedisConnection`, `RedisCircuitBreakers.java:44, 56-57`).
- **Fix.** Name it `fs:{<I>}:mcc:settings-version` and pass both keys in `KEYS`.

### m2. MINOR: absent values and refusal reasons are undefined (B2, B7)

- **Absent values.** An absent P(I) (after loss, or before the first publish) and an absent recorded
  version (in hashes written before the upgrade, or in a lost hash) must be defined as 0. Lua's
  `tonumber(nil)` comparison errors otherwise. Today's script uses `or '0'`
  (`RedisCircuitBreakers.java:37`).
- **Refusal reasons.** B2 should return distinct codes for a counter conflict, (b), and (c), so the
  monitor can count settings refusals separately from ordinary races. Today a refusal is just
  `false` (`:163-168`; `CircuitBreakerMonitor.java:61-63`).
- **Version 0 (pre-existing, outside this contract).**
  - Before V81, `JpaConfiguration.java:222-224` reports version 0, and `CircuitBreakerChanged`
    carries it (`CircuitBreakerMonitor.java:73`; `PostgresSink.java:478`; `KafkaMessages.java:270`).
  - V6 refuses version 0 (`V6__risk_configuration.sql:106`) through the event's foreign key (`:129`).
  - The configuration contract's §3.1 removes version 0. This contract should say that P(I) and the
    recorded version are ≥ 1 after V81.

### m3. MINOR: B4's justification is false as worded, and the settings version can change mid-pass (B1, B4, §1 step 1, P4, P5)

- **B4's justification.** B4 says older-version writes before publication "were made when no
  instance had installed v". In fact install precedes publish by up to R_m + E. An instance that
  installed v at its refresh publishes only at its next pass start, and stale writes in between
  succeed.
- **Settings read per key.** §1 step 1 and `CircuitBreakerMonitor.java:54-55` read settings per key,
  from a snapshot that the refresh thread can replace mid-pass. One pass can then evaluate under a
  version it did not publish. This is not unsafe, because B2 uses the evaluated version, but it makes
  "the caller's settings version" ambiguous.
- **Terminology.** The configuration contract's C1 defines "in force" as *visible in the database*.
  This contract uses "in force" for *published*.
- **Fix.**
  - Pin one snapshot per institution per pass. Publish its version, and pass that version to B2.
  - Reword B4's justification as "made before any instance published v".
  - Call the property "enforced for breaker writes".
  - Qualify P4 and P5 with "after v is published".

### m4. MINOR: Redis-down behaviour is misdescribed, and B5's bound does not hold during an outage (B5, B6)

- **The outage does not end the pass.** `ResilientPorts.breakers` turns a failed `active()` into an
  empty list (`ResilientPorts.java:159-160`). A Redis outage therefore does not "end the pass and
  get counted" (B6). The pass succeeds with zero keys and only `DegradedMode` is marked.
- **`isOpen` is frozen.** `isOpen` returns the last known open set unchanged (`:132-133`; the class
  comment at `:34-35`) for the whole outage. B5's "at most R_m + E old" holds only while Redis is
  reachable.
- **Verified: no instance writes local state on failure.** `counts`, `state` and `compareAndSet` pass
  through with no fallback (`:164-177`), and counting is skipped (`:137-156`).
- **Fix.** State the Redis-down behaviour and the frozen copy in B5 and §6, and count the empty
  fallback as a failed pass.

### m5. MINOR: §4's "sees its effect" overclaims for a loosening that meets an open breaker (§4, B4)

- **The case.** Under B4, a breaker opened under the old threshold closes only after the *new* clean
  reset from `last_breach_at` (`MccCircuitBreaker.java:102`). A clean reset can be up to 10080 min (`V6__risk_configuration.sql:111`).
- **The conflict.** The loosening's visible effect on that breaker can therefore take days, not
  `t + … ≤ 60 s`. That matches B4 and §6's "prospective" cost, but §4's sentence contradicts it.
- **Fix.** Say "every decision sees breaker state evaluated under the new settings by …".

### m6. MINOR: E is unbounded, and §1 overstates today's `isOpen` defect (§1, §4, B5)

- **The cost of a pass.**
  - `counts` issues one `HMGET` per window minute, up to 1440 per key (`RedisCircuitBreakers.java:110-117`;
    V6:109).
  - `state` and `compareAndSet` are separate round trips per key, awaited in sequence.
  - The active set holds every key with traffic in the last 25 h, plus locally open keys
    (`:33, :92-106`).
  - Every instance does all of this every pass.
- **The consequence.** E grows with active keys × window, and "E under 1 s" is an assumption.
- **Is B5 implementable? Yes.** It adds one `HGETALL` per key for stale instances only. Current
  instances already read state for every key (`CircuitBreakerMonitor.java:58`).
- **§1's claim is overstated.** Today every instance already refreshes its copy for every active key
  every pass (`CircuitBreakerMonitor.java:53-58` → `RedisCircuitBreakers.java:132-146`). The real
  defect is that any exception aborts the whole pass. An example is a lazy snapshot load
  (`JpaConfiguration.java:161-168`) throwing at `CircuitBreakerMonitor.java:54-55`, after which the
  remaining keys go unrefreshed. B6 fixes exactly that.
- **Fix.**
  - Correct §1.
  - Give E a budget, exported, with an alert.
  - Pipeline the bucket reads per institution, or move them to one script per key.
  - Make the §4 bound conditional on the budget.

### m7. MINOR: the properties are incomplete

P1 to P7 are testable as stated, using a real Redis to delete P(I) for P2. These are missing:
- **liveness** (M1);
- **the restore detection** (M2);
- **mixed-release writes** (M3);
- **B5's staleness bound under the chosen scheduling** (M4);
- **B7's one-pass bound**, with a current instance live and with none;
- **B6's dedicated scheduler**, so that a blocked task elsewhere does not delay the monitor;
- **no event on a refused write.** A B2 refusal records no `CircuitBreakerChanged`.

---

## Verified claims

| Claim | Evidence |
|---|---|
| Every instance runs the monitor every 5 s on a shared two-thread scheduler | `BackgroundTasks.java:32, 80-87`; fixed delay, `:91` |
| The monitor reads settings, counts and state per active key and writes only a changed state, by compare-and-set | `CircuitBreakerMonitor.java:53-64` |
| The compare-and-set checks only a change counter; no settings version is recorded | `RedisCircuitBreakers.java:35-42` |
| Buckets are kept 25 h; the active set is pruned at 25 h and unioned with locally open keys | `RedisCircuitBreakers.java:33, 74-75, 92-106` |
| `isOpen` is a local copy, updated on `state()` and on a successful compare-and-set | `RedisCircuitBreakers.java:46, 62-64, 145, 163-166, 171-177` |
| Decisions read `isOpen` once per decision | `DecisionService.java:185, 194` |
| Closing is measured from `last_breach_at` plus the clean reset; an open breaker under breach refreshes `last_breach_at` (so it is written every pass) | `MccCircuitBreaker.java:95-102` |
| ResilientPorts writes no local breaker state when Redis fails | `ResilientPorts.java:129-179` |
| Breaker versions ≥ 1 in V6; the event's `settings_version` has a foreign key to them | `V6__risk_configuration.sql:104-116, 127-129` |
| Event records carry the evaluator's settings version | `CircuitBreakerMonitor.java:68-74`; `PostgresSink.java:465-482`; `KafkaMessages.java:265-270` |
| A failed refresh keeps the previous snapshot; the first use loads synchronously | `JpaConfiguration.java:117-127, 161-168` |
| B1's "set if greater" in Lua is atomic, so P(I) is monotonic while the key exists (P7) | sound by construction |
| B2(b) and (c) in the same script as the counter check close the read-then-write race of P1, including a stale instance that read the state after the current instance's write | sound by construction (subject to m1 in Cluster) |
| A late-learned institution is not a hazard: its first load is the newest version, so the instance is current for it; it publishes from its next pass | `JpaConfiguration.java:161-168`; configuration contract C3 |
| "Every instance stale because nothing has installed yet" is correctly "not yet enforced": all write under the old version, consistent with C4's propagation bound | B2, B4; configuration contract C4, §6 |
| Redis is standalone with `noeviction`, so P(I) is lost only through failover or restore, not eviction | `docker-compose.yml:118` |
| Review 3's m2 (history) is avoided: B4 needs only `last_breach_at` and the current window | B4; `MccCircuitBreaker.java:95-107` |
