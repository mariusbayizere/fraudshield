# Review 3 of the breaker-monitor contract, draft 3

- **Subject:** `docs/architecture/breaker-monitor-contract.md`, draft 3, at commit 496f386 (delta
  `git diff 3843c90 496f386 -- docs/architecture/breaker-monitor-contract.md`).
- **Reviewer:** an independent, adversarial reviewer who wrote none of this work and neither of the
  earlier reviews.
- **Method:** each of review 2's findings (N1 to N9) was checked against draft 3. The new text was
  then read against the code (file:line below), the configuration contract
  (`risk-configuration-propagation-contract.md`, C3 and C4) and the decision-commit contract's A3.
  No build, tests, Docker or network were used. Line numbers `:n` without a file are draft 3's.

## Verdict: SOUND_WITH_CHANGES

- **N1, the MAJOR, is resolved.** v_p is defined (:55), and every check, publication, recorded
  version and event uses it: B1 (:79-81), B2 (:86-95), B3 (:97), B4 (:110-113), B7 (:140), P12
  (:210). Nothing in draft 3 lets a state be written under settings older than ones already
  published.
- **No new MAJOR.** The new findings are four MINORs:
  - F1: B3's claim that the reload "adds no load path" is false in two cases;
  - F2: §6 was not updated;
  - F3: B9's edge cases;
  - F4: missing properties.
- **Residuals from review 2:** N2, N5, N8 and N9(c) are PARTLY resolved; the rest are RESOLVED.
- **Before code:** the changes below are text changes, plus one amendment the configuration
  contract needs (F1). None needs another full review; a delta check of F1 is enough.

---

## 1. Review 2's findings

| Finding | Status | Reasons |
|---|---|---|
| N1 MAJOR: checks used the installed version | **RESOLVED** | §2 defines v_p as the version of the snapshot pinned at pass start and says every check, publication, recorded version and event uses it (:55). B1 pins and publishes v_p (:79-81); B2 checks (b) and (c) against v_p, stores v_p and puts v_p on the event (:86-89); B3's stale test is v_p < P(I) (:97); B4's second bullet is reworded to the install-to-next-pass-start window (:111-113), which removes review 2's "the installing instance still evaluates under it". Review 2's counterexample now fails at step 6: A calls B2 with v_p = 4 < P(I) = 5, refused with code (b). P12 (:210) is the property review 2 asked for. Today the settings and their version already come from one object per key (`CircuitBreakerMonitor.java:54-55, 73`; `JpaConfiguration.java:107-110`), so pinning that object per institution per pass is implementable. The event's FK (`V6__risk_configuration.sql:127-129`) holds for v_p: it is a committed version loaded from the primary, and A1 (version ≥ 1, `V6:106`) excludes 0. |
| N2 B3's reload path | **PARTLY** | Executor (C4's K-thread pool), install path (C3, never `put`; today's `put` is at `JpaConfiguration.java:121, 137, 167`) and merging (one outstanding request per institution) are now stated (:102-106), with P16. Not done: the amendment of the configuration contract that review 2 asked for. Draft 3 argues none is needed ("It adds no load path, and C4's worst case already counts it, because a stale institution is one whose probed version differs", :105-106). That argument holds for a cached institution whose probe differs, and fails in two cases. See F1. |
| N3 B8's false alarms | **RESOLVED** | B8 (:148-152) now raises only on a load that started after P(I) was read, succeeded, and returned a version below it. That condition is exact under the configuration contract's A1 and A2: P(I) is raised only to versions installed from committed primary rows, so a later REPEATABLE READ snapshot sees them. A failed load raises nothing. P17 tests both directions. The dependency review 2 asked to state (on the configuration contract's restart) is not needed: an instance still holding a lost version loads (its probe differs) and that load returns below P(I), so B8 fires; C3 discarding the snapshot does not matter. One dependency is unstated, though: in the case B8 exists for, the load B8 needs happens only through B3's request. See F1(b). |
| N4 `evaluated-at` | **RESOLVED** | (1) Scope: institutions with at least one active key (:164-165). (2) Clock: Redis `TIME` (:166-170); the stack runs `redis:7.2.16`, where scripts are effect-replicated and may call `TIME`. (3) Per-key skips: `fs_breaker_key_skips_total` alerts on a sustained rate (:135-136). (4) P8 reworded to "within 60 s plus one pass of the last evaluation" (:206). Residual edge cases (an absent key, and "writes or confirms") are in F3. |
| N5 B7 understated | **PARTLY** | B7 is restated as review 2 proposed: any key recorded at or below v_p (:139-140); one pass with a live current instance; otherwise until one returns, signalled by `fs_config_unconfirmed_seconds` (:141-144). That signal is correct: with no live current instance, every live instance is behind the database, so its loads or probes fail, and C4's freshness alert fires. Not done: (i) §6 still says "for at most one pass after Redis loses P(I)" with no condition (:222), and (ii) B9's "no instance writes I's breakers" (:160-162) is still false in review 2's combination (P(I) lost, the only current instance gone, the stale one republishes its own version and writes). See F2 and F3. |
| N6 A2 restarts | **RESOLVED** | "Every instance is stopped before the step and started after it, never restarted in a rolling way" (:68-70). The configuration contract's A2 says "restarted"; that wording does not allow a rolling restart against this contract's stricter rule, so the runbook (M9) must follow the stricter one. |
| N7 rollback | **RESOLVED** | A3 now names the release (the one that brings V80/V81, deployed by the decision-commit contract's stop-and-drain cutover, `analyst-decision-commit-contract.md:84-91`) and adds a stop-all-then-start rollback (:71-75). After a rollback, the old `SET_STATE` (`RedisCircuitBreakers.java:35-42`) leaves `settings_version` fields and P(I) in place. Both are at or below the newest database version, so a re-upgrade freezes nothing. Nit: review 2's request that later changes to B2's script stay compatible in a mixed deploy is not written down. |
| N8 an extra pass charged | **PARTLY** | §4 now uses the tight term, and its arithmetic is right (verified below). "This is a bound" is stated (:189). But §6 still says "N ≤ 28 against the configuration contract's N ≤ 48" (:226-227). See F2. |
| N9 (a) code order, (b) skipped reads, (c) first loads | **PARTLY** | (a) **RESOLVED.** (b), then (c), then (a), first failure's code returned (:90-91). A settings refusal is never counted as a race, and P1's "(code (b))" is consistent with the order. (b) **RESOLVED.** B5 includes institutions skipped under B3 or B6 (:119-120); B6 skips evaluation only (:129-134); P13 covers skipped institutions. (c) **PARTLY.** The monitor no longer loads (:129-132), which removes the synchronous load reached today through `CircuitBreakerMonitor.java:54-55` → `JpaConfiguration.java:161-168`. But who serves the request for an institution the instance has never cached is not established (F1(a)). |

---

## 2. New findings

### F1. MINOR: "It adds no load path, and C4's worst case already counts it" is false for a never-cached institution (B6) and for the restore case (B8). The approved configuration contract is not amended (B3, B6, B8, §4)

**Rule.**
- B3 (:102-106): the reload "is served at the start of the next cycle on that contract's K-thread
  pool … counts as one of that cycle's loads. It adds no load path, and C4's worst case already
  counts it, because a stale institution is one whose probed version differs."
- B6 (:129-132): for an institution with no snapshot, "the monitor requests a load (B3's path)".
- Configuration contract C4 step 2 (`risk-configuration-propagation-contract.md:205-206`): "Load
  every **cached** institution whose probed versions differ from its installed ones". C3 (:195-196):
  "no snapshot" holds only before an instance's first install. C4 has no request queue.

**(a) A never-cached institution (B6).**
- C4 step 2 loads only cached institutions. An institution the instance never cached has no
  installed version, so the argument "its probed version differs" does not apply. Read literally,
  the approved C4 never serves B6's request. The breaker contract silently adds a caller and a load
  path to an approved contract, which is what review 2's N2 asked to avoid.
- **If the request is not served,** the monitor skips evaluation of that institution forever. The
  only other first-load path is the decision path's synchronous first-request load
  (`JpaConfiguration.java:161-168`; configuration contract §6, :332-333).
  - Counterexample: A2 and A3 both require every instance to stop and start. After that restart,
    an institution with active keys (`fs:mcc:active` is global, `RedisKeys.java:32`;
    `RedisCircuitBreakers.java:92-106`) but no traffic since the restart is cached by no instance,
    so no instance evaluates it. An open breaker on it stays open in Redis.
  - Not indefinite in effect: the first decision for that institution loads it, and the next pass
    evaluates it. Until then, decisions read the stale state for up to one pass (B5 reads states
    regardless).
  - B9 alerts at 60 s if `fs:{I}:mcc:evaluated-at` exists. After the first deployment of this
    contract it does not exist (see F3), so nothing alerts.
- **If the request is served** (the contract's intent), the cost is not counted. A starting
  instance meets every institution with active keys on its first pass, M of them. Its next cycle
  then carries M first loads, served "at the start", ahead of step 2's change loads. That cycle's C
  is up to `T_P + ⌈(N + M)/K⌉·T_L`, not C4's `T_P + ⌈N/K⌉·T_L`.
  - Example: M = 100. 2 + 25·2 = 52 s.
  - After A2's and A3's full restarts, every instance starts at once. "Installed on some instance
    by t + R + 2C" (§4) then has no unaffected instance to rely on. §4's "N ≤ 36" does not hold for
    a change committed in those first cycles.
  - It is transient: one or two cycles per start. That is why this is MINOR, not MAJOR.

**(b) The restore case (B8).**
- After a restore whose A2 step was skipped, the database holds version 6 and P(I) = 7. A
  restarted instance installs 6, and its probe then returns 6 = installed. So C4 step 2 never loads
  it again.
- The only load that can start after the instance read P(I), which B8 requires, is the one B3's
  request forces.
- So B8's detection depends on B3's request being a load **even when the probe shows no
  difference**. That is exactly the case the justification says cannot arise ("a stale institution
  is one whose probed version differs").
- An implementer who reads the justification as "C4's step 2 serves the request" builds a no-op:
  - B8 never fires;
  - the breakers freeze everywhere (every instance is at 6 < 7);
  - only B9's generic alert remains.

**Fix.**
- Delete the sentence "It adds no load path … probed version differs". In its place, state that a
  request is a load of I whatever the probe says, merged with any step-2 load of I in the same
  cycle.
- Add to the configuration contract's C4 (a one-paragraph amendment, recorded in its history): "a
  load may also be requested by the breaker monitor for a cached institution (B3) or an uncached
  one (B6). Requests are merged per institution and run after step 2's loads." Also say that an
  uncached institution's first successful load is installed by C3's "the entry has none" branch,
  and is then cached.
- Bound the start-up burst. Either:
  - serve at most K first loads per cycle, after step 2's loads; or
  - state that §4 and C4's bounds hold from an instance's second cycle on, and that A2's and A3's
    full restarts suspend E.6's 60 s for that long.
- Say "served after step 2", not "at the start of the next cycle", so first loads never delay a
  change's load.

### F2. MINOR: §6 contradicts §4 and B7 (§6)

**Rule.** §6 (:221-222): "A stale instance can still write … for at most one pass after Redis
loses P(I)." §6 (:226-227): "N ≤ 28 against the configuration contract's N ≤ 48."

**Evidence.**
- §4 (:187-188) now gives N ≤ 36.
- B7 (:141-144) says the post-loss window lasts "until one returns" when no current instance is
  live.
- §6 is the list of accepted costs the owner accepts. Both lines understate the cost or give the
  superseded figure.

**Fix.**
- Change §6 to "N ≤ 36 against … N ≤ 48".
- Change "for at most one pass after Redis loses P(I)" to "for one pass after Redis loses P(I)
  while a current instance is live, and otherwise until one returns, with
  `fs_config_unconfirmed_seconds` firing meanwhile".
- §7's M4 row (:236) records draft 2's answer. That is history, and may stay.

### F3. MINOR: B9's edge cases: an absent timestamp is undefined, and "no instance writes" is still false after P(I) is lost (B9, P8)

**Rule.** B9 (:157-170) and P8 (:206).

**1. Absent `evaluated-at`.**
- The metric is "the age of that time" (:169). For an institution with active keys and no
  `evaluated-at` key, the age is undefined, so the alert does not fire.
- The key is absent:
  - for every institution right after this contract's first deployment;
  - for an institution whose keys were created while no instance could evaluate it (F1(a));
  - after a Redis failover that lost the key.
- Fix: define the age of an absent key as the time since the institution's oldest active key's
  score in `fs:mcc:active` (`RedisCircuitBreakers.java:75`), or as since the instance's start,
  whichever is later. Or let the first publishing pass create the key with `TIME` before it
  evaluates anything.

**2. "No instance writes I's breakers" (:160-162) is still false** in review 2's N5 combination.
- In that combination: P(I) is lost, the only instance at version 5 is gone, and B is at 4 with
  failing loads.
- What B then does: it republishes 4, is not stale (v_p = P(I)), writes every key recorded at or
  below 4, and writes `evaluated-at`. So B9 stays silent.
- Keys recorded at 5 are refused under (c) on every pass and stay frozen.
- The case is not silent: `fs_config_unconfirmed_seconds` fires on B, as B7 now says. But B9's
  sentence is untrue as written.
- Fix: add "unless Redis has lost P(I) (B7)" to B9's failure clause, and the same exclusion to P8.

**3. "Writes or confirms its state" (:166) is undefined.**
- The meaning is probably "evaluates every active key of I without a key skip". Say so, so that a
  pass in which every key of I threw does not write `evaluated-at`.
- Optionally, write `evaluated-at` in the same script as B1's publish, under the check v_p ≥ P(I).
  Then an instance that became stale mid-pass, and whose writes are all refused, does not refresh
  it. Today that exposure is one pass, so this is a nit.

### F4. MINOR: properties missing for three new rules (§5)

- **B6's no-snapshot path:**
  - the monitor never loads in its own thread (a first pass over M unknown institutions does not
    raise E);
  - the institution is evaluated from the first pass after its install;
  - its states are still read.

  P6 covers a failed load only. Review 2 asked for this under N9(c).
- **B3's request in the probe-equal case:** a stale instance whose probe matches its installed
  version still loads, and B8 fires (F1(b)). P16 and P17 do not cover it.
- **B9's clock:** the age is computed from Redis `TIME`, and skewing an instance's clock changes
  neither the written time nor the alert.

---

## 3. Verified claims

| Claim | Evidence |
|---|---|
| §4: `R + 2C + (R_m + E) + E + (R_m + 2E) = R + 2C + 2R_m + 4E`; with R = R_m = 5 s and E = 1 s, `19 + 2C` | arithmetic: 5 + 10 + 4 = 19 |
| §4: 60 s holds while C ≤ 20.5 s, and with K = 4, T_L = T_P = 2 s, N ≤ 36 | 2 + 2⌈N/4⌉ ≤ 20.5 ⇔ ⌈N/4⌉ ≤ 9 ⇔ N ≤ 36. N = 36: C = 20, total 59 s. N = 37: C = 22, total 63 s |
| §4: "about 21 s" with one change and ordinary loads | 19 + 2C with C ≈ 1 s; it is the bound, as the text now says |
| §4: the write lands in the publishing pass; a stale write after publication is refused by (b), so it cannot cause an (a) conflict for the current instance | B2 is atomic with (b) first; P(I) is monotonic (B1); recorded ≤ P(I) ≤ v_p for every current instance while P(I) survives |
| B4: publication lags install by at most `R_m + E`; B5's `R_m + 2E` | fixed delay, `BackgroundTasks.java:91` (today on the shared pool, `:80-86`; B6 moves the monitor to its own thread) |
| B2: (b), (c), (a) fit one script on one slot | `fs:{I}:mcc:settings-version`, `fs:{I}:mcc:evaluated-at` and `fs:{I}:mccstate:<mcc>` share the `{I}` tag (`RedisKeys.java:24-29`). Neither new key matches a bucket key `fs:{I}:mcc:<mcc>:<minute>` (`:24-25`), whose MCC is four digits (`V6:123`). Absent reads as 0 by the `tonumber(... or '0')` pattern (`RedisCircuitBreakers.java:37`) |
| The state hash's only writer is the monitor's compare-and-set, so B2 covers every state write | `RedisCircuitBreakers.java:150-169`, called only from `CircuitBreakerMonitor.java:62` (through `ResilientPorts.java:174-176`) |
| The event's `settings_version` must be a committed version ≥ 1; v_p satisfies that | `V6__risk_configuration.sql:106, 127-129`; A1 (V81 seeding) |
| B5's `isOpen` copy is refreshed by every state read, including on a refused write | `RedisCircuitBreakers.java:132-147, 171-177` |
| B6 removes today's two E stretchers: a synchronous first load in the monitor and a silent empty pass | `JpaConfiguration.java:161-168` via `CircuitBreakerMonitor.java:54-55`; `ResilientPorts.java:159-160` (`List::of` on a failed `active()`) |
| B7's "with none, `fs_config_unconfirmed_seconds` fires": with no live current instance, every live instance's probes or loads fail | configuration contract C4 (:203-208, :217-220); if they succeeded, the instance would install the newest version within R + 2C |
| B8's condition is exact: a successful load that started after P(I) was read returns ≥ P(I) unless commits were lost | configuration contract A1, A2 and C2 (REPEATABLE READ on the primary); B1 publishes only installed versions |
| B9's `TIME` is allowed in a script on the deployed Redis | `redis:7.2.16` (compose and test containers); Redis 7 replicates scripts by effects |
| A2's step, with every instance stopped, leaves no version in Redis above the restored database's; `evaluated-at` need not be cleared | B1 raises P(I) only to installed versions; a stale `evaluated-at` only delays B9's alert by its age, which is already past 60 s |
| A3's rollback is safe to re-upgrade from | the old `SET_STATE` (`RedisCircuitBreakers.java:35-42`) neither reads nor deletes `settings_version` or P(I), and both stay ≤ the newest database version |
| Correction to review 2's citations | `RedisKeys.java` has the bucket key at :24-25, the state key at :28-29 and `fs:mcc:active` at :32. Review 2 cites :214-222, which do not exist in this file. The substance of review 2's m1 is unchanged |
