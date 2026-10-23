# Review 2 of the breaker-monitor contract, draft 2

- **Subject:** `docs/architecture/breaker-monitor-contract.md`, draft 2, on branch `m8b/analyst-api` at
  commit 3843c90.
- **Reviewer:** an independent, adversarial reviewer who wrote none of this work and not review 1.
- **Method:** each of review 1's findings was checked against draft 2. Draft 2 was then read afresh
  against the code, with file:line references, against the configuration contract
  (`risk-configuration-propagation-contract.md`, A2 and C2 to C4) and against the decision-commit
  contract's A3. No build, tests, Docker or network were used.

## Verdict: UNSOUND

One MAJOR remains, and its fix is one definition plus one property.
- **The MAJOR (N1).** B1, B2 and B3 check and record v(i, I). §2 defines that as the version
  *installed* on the instance, and the refresh thread can change it in the middle of a pass. B4
  pins one snapshot per pass for *evaluation*, but nothing says the pinned snapshot's version is the
  one B2 checks and records. Read as written, a state evaluated under older settings can be written,
  and recorded under the newer version, after the newer version was published. That is the exact
  defect this contract exists to remove, and no property in §5 would catch it.

Everything else is MINOR.
- The four MAJORs of review 1 are answered: liveness is stated and alerted, a restore is detected,
  the cutover removes mixed releases, and §4's arithmetic is now correct (and conservative).
- The MINORs are about precision:
  - B3's reload path;
  - B8's false alarms;
  - B9's metric definition;
  - B7's wording, which contradicts B9 and P8 in one combination;
  - the runbook steps' ordering and rollback;
  - a few property gaps.

With N1 fixed, the design is sound. Draft 3 can be checked as a delta.

---

## 1. Review 1's findings

| Finding | Status | Reasons |
|---|---|---|
| M1 MAJOR: no live current instance, no evaluation | **RESOLVED** | B9 states the liveness condition and ties it to the configuration contract's Q1 (contract:130-140). `fs_breaker_unevaluated_seconds` alerts at 60 s, P8 tests it, and B7 is conditioned on a live current instance (:117-118). B3's immediate reload (:87-88) gives latency only, not liveness: in M1's own counterexample, B's loads fail, and so does the reload. B9 is honest about that. Residuals: N4 (the metric's definition) and N5 (B7, B9 and P8 disagree after P(I) is lost). |
| M2 MAJOR: versions in Redis outlive a restore | **RESOLVED** | A2's runbook clears P(I) and every recorded version before instances start again (:59-64). B8 detects a skipped step and never lowers P(I) (:122-128), and P7 tests it. For the scenario stated (database restored, instances restarted, Redis untouched), every instance installs the restored version, sees it below P(I), and alerts. Residuals: N3 (false alarms) and N6 (the step's ordering). |
| M3 MAJOR: old-release monitors during a rolling deploy | **RESOLVED** for the forward deploy | A3's stop-and-drain cutover (:65-67; decision-commit contract :84-98) starts only new-release instances after every old one has stopped. So no unchecked `SET_STATE` (`RedisCircuitBreakers.java:35-42`) runs after a checked write. Hashes and P(I) written before the cutover have no version, which reads as 0 and passes (c). Residual: N7 (rollback, and "the same cutover"). |
| M4 MAJOR: the pass count and N ≤ 40 | **RESOLVED** | Fixed delay is stated (:54, :108). B5's `R_m + 2E` is correct for fixed delay (`BackgroundTasks.java:91`). §4's sum `R + 2C + 3R_m + 5E = 25 + 2C` is correct for its terms, and so are C ≤ 17.5 and N ≤ 28 (see verified claims). The write term is conservative by `R_m + E` (N8). |
| m1 cross-slot keys | **RESOLVED** | `fs:{I}:mcc:settings-version` carries the same `{<uuid>}` tag as `fs:{I}:mccstate:<mcc>` (`RedisKeys.java:218-219`). It cannot collide with a bucket key, `fs:{I}:mcc:<4-digit MCC>:<minute>` (`:214-215`). |
| m2 absent values, return codes, version 0 | **RESOLVED** | Absent means 0 (§2), there are distinct codes (B2), and A1 relies on V81's seeding. Nit: the codes' precedence when more than one check fails is unstated (N9). |
| m3 install before publish; versions within a pass | **PARTLY** | B4 now pins one snapshot per pass, P4 and P5 say "after publication", and the "in force" terminology is gone. Review 1's fix also said "publish its version, *and pass that version to B2*". Draft 2 does not: B2 still uses v(i, I), the *installed* version (N1). B4's second bullet ("the installing instance still evaluates under it") also contradicts pinning at the start of the pass (N1). |
| m4 Redis down | **RESOLVED** | B5 and B6 say the pass ends and is counted, and that `isOpen` keeps its last copy. P11 tests it. This requires changing `ResilientPorts.java:160`, which turns a failed `active()` into `List::of`. The contract names that defect in §1 (:44-45). |
| m5 a loosening meeting an open breaker | **RESOLVED** | §4's exception (:157-159) matches `MccCircuitBreaker.java:182-186`, and the 10,080-minute ceiling matches V6:111. |
| m6 E unbounded; §1 overstated | **RESOLVED** | E is measured and alerted above 1 s (:55), and §1 is corrected (:40-45). Pipelining was not adopted, so at realistic scale E ≤ 1 s is a hope, made visible by the alert. See N9(c) for a new source of E. |
| m7 missing properties | **PARTLY** | P8 to P11 cover liveness, restore detection, the refusal codes and Redis down. Still missing: B5's staleness bound; B7's republish, with a current instance live and with none; B6's dedicated scheduler; the pinned version in B2 (N1); B3's install path (N2); and no false alarm from B8 or B9 in normal propagation (N3, N4). |

---

## 2. New findings

### N1. MAJOR: B2 checks and records v(i, I), the *installed* version, which can move mid-pass. A state evaluated under older settings can then be written after a newer version is published (§2, B1 to B4, P9)

**Rule.**
- §2 (:51): "**v(i, I)** the breaker-settings version installed on instance i for institution I".
- B2 (:76-81): "(b) v(i, I) ≥ P(I); (c) v(i, I) ≥ the recorded version. The write stores v(i, I)
  as the recorded version."
- B4 (:92-93): "a pass reads its snapshot once and uses it for every key". P9 (:173) tests that the
  *evaluation* uses one snapshot.
- B4 (:94-96): "Between installing a version and publishing it … the installing instance still
  evaluates under it."

**Counterexample.**
1. Instances A and C serve I at version 4. Version 5 lengthens the clean reset.
2. C installs 5 and publishes P(I) = 5 (B1).
3. A starts a pass, pins its snapshot at version 4 (B4), and publishes 4. That has no effect,
   because P(I) is already 5.
4. A's refresh thread installs 5. The configuration contract's own scheduler runs that thread
   (C4; today `JpaConfiguration.java:117-127`, on another scheduler thread,
   `BackgroundTasks.java:85-86`). v(A, I) is now 5.
5. Still in the same pass, A evaluates a key under the pinned version 4. The shorter clean reset
   has run, so A computes CLOSED (`MccCircuitBreaker.java:182-185`).
6. A calls B2 with v(A, I) = 5:
   - (a) holds;
   - (b) 5 ≥ 5 holds;
   - (c) holds.

   The breaker closes, and the hash records 5.
7. From then on, every instance at 5 leaves it closed (B4: nothing is re-decided). That violates
   P4 ("after publication, a tightening that lengthens the clean reset keeps open a breaker a stale
   instance would have closed"). The same sequence with a lowered threshold violates P5.

**Why it is MAJOR.** It lets a state be written under settings older than ones already published.
The literal reading is also the natural one: today's code reads the settings version at the point
of use (`CircuitBreakerMonitor.java:54-55, 73`). No property in §5 fails:
- P1 and P2 use a stale *installed* version;
- P9 checks only the evaluation.

**The second sentence is wrong too.** Under pinning at the start of the pass, the installing
instance does *not* evaluate under v before publishing it. It pins v and publishes v at the same
pass start. The sentence describes lazy pinning, which would make B3's stale test (made at pass
start) and the evaluation use different versions.

**Fix.**
- **Define v_p(i, I),** the version of the snapshot instance i pinned for I at the start of the
  pass. Pin every institution's snapshot at pass start.
- **Use it everywhere a pass names a version.** B1 publishes v_p, B3 compares v_p with P(I), B2
  checks (b) and (c) against v_p and records v_p, and `CircuitBreakerChanged` carries v_p.
- **Reword B4's second bullet:** "between install and the next pass start (at most R_m + E), the
  instance still evaluates under the older pinned version".
- **Add a property:** "with version v+1 published by another instance and installed locally after
  the pin, a write evaluated under the pinned v is refused with code (b), and records nothing".

### N2. MINOR: B3's immediate reload has no stated executor, install path or coalescing, so it can undo the worst case of C on which N ≤ 28 rests (B3, §4; configuration contract C3, C4)

**Rule.** B3 (:87-88): an instance with v < P(I) "requests an **immediate reload** of I's
snapshot, outside the refresh cycle and with the configuration contract's deadline T_L".

**Who triggers it, and how often.** The monitor triggers it at pass start, when B1's script reports
P(I) > v. That happens once per stale institution, per pass, per instance:
- in normal propagation, for every stale instance and every changed institution, for up to
  R + 2C;
- under B8's condition (a skipped A2 step), on every pass, forever.

It is not a storm. Each instance's loads are capped at K connections (C4), so the database sees at
most K × instances concurrent loads. It is, however, unbounded *repetition*.

**What the contract leaves open.**
- **Executor.**
  - **On C4's K-thread pool, without coalescing.** The reloads queue behind the current cycle's
    loads, and *ahead of the next cycle's*. With N institutions changing at once, every instance
    that loses the race to install receives N reload requests. Its next cycle can then wait up to
    ⌈N/K⌉·T_L before its own loads start. A change committed just after a probe is caught only by
    that next cycle. The worst case of C used in §4 (`T_P + ⌈N/K⌉·T_L`, :153) no longer holds, and
    N ≤ 28 falls with it.
  - **On its own threads.** C4's cap of K connections is exceeded.
  - **On the monitor's thread.** E grows by T_L per stale institution, and the monitor stalls.
- **Install path.** The configuration contract's §1 says `invalidate()` has no caller. Its body
  installs with `put` (`JpaConfiguration.java:135-141`), which C3 forbids. The reload must install
  through C3's `compute`. If it does not, versions on an instance can fall, breaking A1. B2 would
  still refuse the resulting writes, so the harm is liveness and P7's detection, not safety.
- **The configuration contract does not know about this load path.** C4 describes only the cycle.
  An approved contract gains a caller and a load path without amendment.

**Fix.**
- State that the reload is submitted to C4's pool and installed through C3.
- Coalesce it: at most one outstanding load per institution, and none when a cycle load of I is
  queued or running.
- Rate-limit it to once per refresh period per institution.
- Add a sentence to the configuration contract's C4 naming this path.
- Add a property: "a reload request while a cycle load of I is queued starts no second load, and a
  reload never installs a snapshot C3 would discard".

### N3. MINOR: B8 alarms on ordinary load failures and names the wrong cause (B8, P7)

**Rule.** B8 (:122-124): "If an instance's installed version … is below P(I) for longer than one
refresh cycle after a reload, that instance raises `fs_breaker_published_ahead_of_database`."

**Counterexample.**
1. Version 5 is published.
2. Instance B's loads fail. This is the very case B9 describes: a database partition affecting
   B, or loads hanging past T_L.
3. B stays at 4 for many cycles, and every reload fails.
4. "Below P(I) for longer than one refresh cycle after a reload" is true, so B raises an alert that
   names a restore. No restore happened.

**What is vague.** "One refresh cycle" has no fixed length, because C is measured (C4). B8 also
does not say whether the reload must have succeeded.

**Evidence.**
- A failed load keeps the old snapshot (configuration contract C4 step 2;
  `JpaConfiguration.java:123-124`).
- B9 (:133-135) names exactly this state as the liveness failure, which has its own alert.

**What does hold.** P(I) is raised only to versions installed from the primary (B1, A1). So a load
that *starts after reading P(I) and succeeds* can return a breaker version below P(I) only if
commits were lost. That condition is exact.

**Fix.**
- Raise the alert only when a load that began after the instance read P(I) succeeded and returned
  a breaker version below it.
- Say that B8 depends on the configuration contract's A2 restart. Without the restart, C3 keeps
  the lost version installed, and B8 sees nothing.
- Add a property: "an instance whose loads fail stays below P(I) without raising B8's alert".

### N4. MINOR: B9's `evaluated-at` is underdefined. Quiet institutions alarm, clocks skew, and per-key skips are hidden (B9, B6, P8)

**Rule.** B9 (:136-137): "Every successful evaluating pass writes `fs:{I}:mcc:evaluated-at`.
`fs_breaker_unevaluated_seconds{institution}`, the age of that time, alerts at 60 s."

**What is open.**
1. **Quiet institutions.** A pass iterates over active keys (`CircuitBreakerMonitor.java:53`;
   `RedisCircuitBreakers.java:92-106`). An institution held in a snapshot but with no traffic for
   25 h has no key.
   - If `evaluated-at` is written per evaluated key, a quiet institution never gets one. Its gauge
     then alarms forever, or never exists, depending on which institutions the gauge covers.
   - The contract must say that a current instance writes `evaluated-at` for every institution it
     published for (B1), keys or none.
2. **Clock.** Instances' wall clocks differ, and the age is computed on a different instance from
   the one that wrote the time.
   - With 60 s of headroom, a skew of tens of seconds gives false or late alarms.
   - Fix: write and read the time from Redis `TIME`, inside the publishing script.
3. **Per-key skips.**
   - `evaluated-at` advances even when B6 skips some or all of I's keys for exceptions. A key that
     throws on every pass is never evaluated, and its `isOpen` copy is never refreshed, so B5's
     bound fails for it. Only a counter shows it.
   - Fix: alert on B6's skip counters (per key and per institution) as on `evaluated-at`, or write
     `evaluated-at` only when no key of I was skipped.
4. **P8's wording.** An alert that fires when the age exceeds 60 s cannot fire "within 60 s" of the
   last current instance going away. State it as: the gauge exceeds 60 s no later than
   60 s + R_m + E after the last successful evaluation.

### N5. MINOR: B7 and §6 understate what a stale instance can write after P(I) is lost, and in one combination B9 and P8 become false (B7, B9, §6, P8)

**Rule.**
- B7 (:117-118): "A stale instance can write *unrecorded keys* for at most one pass. It needs a
  current instance that is live."
- §6 (:181-182): "for at most one pass after Redis loses P(I)", with no condition.
- B9 (:133-135): "If none can [install], … no instance writes I's breakers."
- P8: "With no current live instance, `fs_breaker_unevaluated_seconds` alerts within 60 s."

**Not only unrecorded keys.** (c) admits every key whose recorded version is at or below the
stale instance's. That includes:
- **closed breakers.** These are written only on a change (`MccCircuitBreaker.java:175-187`;
  `CircuitBreakerMonitor.java:61-63`), so they keep the version of the write that closed them,
  often older;
- **hashes a lossy Redis failover rolled back** to an older write.

**The combination.**
1. P(I) is lost.
2. The only instance that installed 5 is gone.
3. B is at 4, and its loads fail.

What follows:
- B republishes P(I) = 4 (B1) and passes (b).
- B writes under 4 on every key recorded at 4 or below, indefinitely.
- B writes `evaluated-at`, so B9's alert stays silent.

So B9's "no instance writes" and P8 are both false here. It is not silent: B's own
`fs_config_unconfirmed_seconds` fires at 60 s (configuration contract C4), because its loads fail.
That is why this finding is MINOR. It needs three faults at once, and an alert still fires.

**Fix.**
- **Restate B7:** "on keys whose recorded version is at most v_p; for one pass while a current
  instance is live; otherwise until one is, signalled by `fs_config_unconfirmed_seconds`".
- **Carry the condition** into §6.
- **Restate B9 and P8** to exclude the loss of P(I), or close the gap. A stronger option is to
  write only while the institution was confirmed (C4 step 3) within the last two cycles. That
  makes B9 true unconditionally, at the cost of freezing breakers during a database outage even
  when every instance is current, which is a Q1 decision.

### N6. MINOR: A2's runbook step must run with every instance stopped. "Before any instance restarts" allows a running instance to undo it (A2)

**Rule.** A2 (:59-62): "before any instance restarts", delete every P(I) key and remove every
`settings_version` field.

**Counterexample.**
1. After a restore, the operator clears Redis, then restarts instances one by one. The
   configuration contract's A2 says "restarted" and does not require all of them to stop first.
2. A still-running instance holds the lost version 7. Within R_m + E it republishes P(I) = 7 (B1)
   and records 7 on any hash it writes.
3. The restarted instances install 6. They are stale against 7, so I's breakers freeze.

**Severity.** It is detected: B8 fires on each restarted instance, and B9 fires within 60 s. It is
not silent, but it defeats the step.

**Fix.** Make the step "after every instance has stopped and before any starts", and make the
configuration contract's A2 restart a stop-all-then-start for the same reason.

### N7. MINOR: A3 covers the forward deploy only, and "the same cutover" is ambiguous (A3)

**Rollback.** A rolling *rollback* to the old release recreates M3 for its duration. Old monitors
run the unchecked `SET_STATE` (`RedisCircuitBreakers.java:35-42`) beside new ones, and their
`HSET` leaves a stale `settings_version` behind (`:39-40`).
- After the rollback completes, behaviour is today's, and a re-upgrade through A3 is safe. The
  recorded versions are at or below the highest version ever published, so nothing freezes.
- The exposure is the rollback window itself.

**"The same cutover".** If this contract's code ships in a release after V80's, then "the same
stop-and-drain cutover as the decision-commit contract's A3" may be read as "that event", which has
already passed. The breaker needs only that every old instance stops before any new one starts. It
does not need the spool drain.

**Fix.**
- Say "any release that first brings B1 and B2, and any rollback from it, is deployed by stopping
  every instance before starting any".
- Require that later changes to the B2 script stay compatible in a mixed deploy.

### N8. MINOR: §4 over-counts the write term, and "about 27 s" is a worst case, not a typical figure (§4)

**The write term.** §4 (:147-148) charges `+ (R_m + 2E)` for "the same or the next pass". After
publication at pass start, the publishing instance's write of a changed state lands within that
pass, at most E later:
- a stale write after publication is refused (b), so it cannot cause a (a) conflict;
- a conflicting current write is itself a write under ≥ v.

A Redis error is the exception, and B5 already excludes Redis outages from the bounds.

**The tight sum.** `R + 2C + (R_m + E) + E + (R_m + 2E) = R + 2C + 2R_m + 4E`, which at E ≤ 1 s
gives C ≤ 20.5 and **N ≤ 36**: review 1's figure. The draft's N ≤ 28 is correct, but conservative
by 8 institutions.

**"About 27 s" (:155).** It takes the worst-case sum with C ≈ 1 s. The configuration contract's
own single-change figure, about 6 s for `R + 2C` (C4), gives about 26 s. Either way, it is the
bound for ordinary loads, not what a change usually takes.

**Fix.** Either justify the extra pass or use the tight term. Call 27 s a bound.

### N9. MINOR: smaller precision gaps (B2, B5, B6)

- **(a) Refusal codes.** B2 does not say which code wins when more than one check fails. The
  counts of P10 depend on it. Fix: check (b), then (c), then (a), so settings refusals are never
  counted as races.
- **(b) B6 and B5 disagree.** An institution "whose snapshot cannot be loaded is skipped for that
  pass" (B6), but B5 has every instance read every active key's state. A state read needs no
  snapshot. Fix: skip *evaluation* only. B5's bound also holds only for keys B6 did not skip
  (N4.3).
- **(c) First loads inside the pass.** The active set is global (`RedisKeys.java:222`;
  `RedisCircuitBreakers.java:92-106`), so a new instance meets every institution's keys on its
  first pass.
  - Today each one costs a synchronous load in the monitor thread (`JpaConfiguration.java:161-168`,
    reached from `CircuitBreakerMonitor.java:54-55`). That breaks E ≤ 1 s, and so §4, at every
    start-up and scale-out.
  - Fix: the monitor never loads. It hands an unknown institution to C4's pool (as in N2), reads
    its states, and evaluates it from the first pass after its install.

---

## 3. Verified claims

| Claim | Evidence |
|---|---|
| §1: the monitor runs every 5 s on the shared two-thread fixed-delay scheduler | `BackgroundTasks.java:32, 80-86, 91` |
| §1: settings are read per key, buckets and state per key, and a changed state is written by a Lua compare-and-set on a counter | `CircuitBreakerMonitor.java:53-64`; `RedisCircuitBreakers.java:35-42, 110-147` |
| §1: `isOpen` is a local copy, refreshed on a state read and on a successful write | `RedisCircuitBreakers.java:46, 62-64, 145, 163-166, 171-177`; read once per decision at `DecisionService.java:185` |
| §1: nothing records the settings version in Redis; a single exception ends the pass; a failed `active()` reads as empty | `RedisCircuitBreakers.java:39-40`; `CircuitBreakerMonitor.java:53-77` (no catch inside the loop) and `BackgroundTasks.java:93-97`; `ResilientPorts.java:160` |
| The hash's existing `version` field is the change counter, distinct from the new `settings_version` | `RedisCircuitBreakers.java:37-39` |
| B2's three checks fit one script on one slot: two keys with the `{I}` tag, absent values read as 0, the version in ARGV | `RedisKeys.java:218-219`; the pattern `tonumber(... or '0')` at `RedisCircuitBreakers.java:37` |
| B1's "set if greater" is monotonic while the key exists; B2(b) is checked atomically with the write, so a stale CAS that lands after publication is refused whenever it read the state | sound by construction |
| P4 and P5 hold after publication once B2 uses the pinned version (N1). Before publication they are not claimed | B2, B4; `MccCircuitBreaker.java:175-187` |
| B4's "nothing re-decided": an old open breaker closes at `last_breach_at` + the newer reset, and stays open while the newer settings breach | `MccCircuitBreaker.java:175-186` |
| §4's sum: `5 + 2C + 15 + 5 = 25 + 2C`; C ≤ 17.5 ⇔ ⌈N/4⌉ ≤ 7 ⇔ N ≤ 28 (N = 28: C = 16, 57 s; N = 29: C = 18, 61 s) | arithmetic, with configuration contract C4 |
| B5's `R_m + 2E` between two reads of a key under fixed delay | `BackgroundTasks.java:91` |
| Publication lags install by at most `E + R_m` | fixed delay; install on another thread |
| §4's exception: the clean reset can be 10,080 minutes; the buckets (25 h) cover the longest window (1,440 min) | V6:109, 111; `RedisCircuitBreakers.java:33` |
| A2's step, run with every instance stopped, leaves no version in Redis above the restored database's | A2; B1 raises P(I) only to installed versions |
| A3 removes M3's forward-deploy hazard; pre-cutover hashes read as recorded version 0 | decision-commit contract :84-98; §2 "absent means 0" |
| B3 cannot storm the database: one request per stale institution per pass per instance, under C4's cap of K connections | B3; configuration contract C4 (subject to N2) |
| B9's liveness condition is correct as long as P(I) survives (N5 for the loss case) | B2(b), B3 |
| Every property is testable with a real Redis and two monitor instances. P8 needs its wording fixed (N4.4) | §5 |
