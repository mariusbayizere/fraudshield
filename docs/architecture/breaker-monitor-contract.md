# The MCC circuit-breaker monitor: who writes breaker state, under which settings

Status: **draft 2, for independent review.**

**Review 1** (`docs/reviews/M8B/breaker-monitor-contract-review-1.md`) found draft 1 UNSOUND, with
4 MAJOR and 7 MINOR findings.
- **The safety core held.** Publication, plus refusing any write from an instance behind the
  published or recorded version, stops a stale instance from undoing a published change.
- **The MAJORs were in four places:** liveness, a database restore, rolling deploys, and the
  arithmetic.
- §7 maps every finding to its answer.

**Origin.** The owner split this contract out of the configuration contract
(`risk-configuration-propagation-contract.md`) on 2026-10-20. It replaces that contract's C8. Code
is written against it, not the other way round.

**Scope.** This contract governs three things:
- which instance may evaluate and write a breaker's state in Redis;
- how a breaker-settings change, delivered by the configuration contract's snapshots, reaches that
  state;
- how decisions read that state.

Storage, versions, dual control and propagation of the settings belong to the configuration
contract.

**Sources:**
- FR-03-07 and D-18, and point 4 of ADR 0061;
- the code: `CircuitBreakerMonitor`, `MccCircuitBreaker`, `RedisCircuitBreakers`, `RedisKeys`,
  `CircuitBreakerPort`, `ResilientPorts`, `BackgroundTasks` and `DecisionService` (`isOpen`);
- the configuration contract's A2 and C2 to C4.

## 1. What exists

- **Every instance runs the monitor every 5 s,** on the shared two-thread fixed-delay scheduler.
  For each active key `(institution, MCC)`, it:
  - reads the settings from its snapshot, once per key;
  - reads the window's one-minute buckets (kept 25 h) and the state hash;
  - evaluates;
  - writes a changed state by a Lua compare-and-set on a change counter.
- **Decisions read `isOpen` from a per-instance copy,** refreshed when the instance reads or writes
  the state.
- **Nothing records which settings a state was written under.** A stale instance can therefore undo
  a newer setting's effect.
- **A single exception ends the whole pass.** A failed `active()` while Redis is down reads as an
  empty set, so the pass silently does nothing.

## 2. Terms and assumptions

| Term | Meaning |
|---|---|
| **v(i, I)** | the breaker-settings version installed on instance i for institution I |
| **P(I)** | the published version, in the key `fs:{I}:mcc:settings-version`. It shares the `{I}` hash tag with the state keys, so one script can touch both under Redis Cluster. Absent means 0 |
| **recorded version** | the field `settings_version` in a breaker's state hash, set by the write that last changed the state. Absent means 0 |
| **R_m** | the monitor period, 5 s, run by **fixed delay** |
| **E** | one pass's duration, measured. The bounds assume E ≤ 1 s, and `fs_breaker_pass_seconds` alerts above that |

- **A1. The configuration contract holds.** In particular, versions on an instance never decrease,
  and its seeding (§3.1 there) gives every institution version 1, so v is never 0 after V81.
- **A2. No committed transaction is lost,** as in the configuration contract. After any restore or
  failover, the runbook (M9) does two things before any instance restarts:
  - deletes every `fs:{*}:mcc:settings-version` key;
  - removes the `settings_version` field from every breaker state hash.

  Without that step, versions held in Redis would outlive the database's. Detection is in B8.
- **A3. The release that brings this contract is deployed by the same stop-and-drain cutover as the
  decision-commit contract's A3.** An old-release monitor, whose compare-and-set ignores recorded
  versions, never runs after a new-release one has written.

## 3. Rules

**B1. Publish what is installed.** At the start of each pass, for each institution it holds a
snapshot for, an instance raises P(I) to v(i, I) with a Lua "set if greater". P(I) never decreases
while Redis keeps it.

**B2. Only a current instance may write.** The compare-and-set is one Lua step on the keys of one
`{I}` slot. It writes only if all three hold:
- (a) the change counter equals the one the caller read;
- (b) v(i, I) ≥ P(I);
- (c) v(i, I) ≥ the recorded version.

The write stores v(i, I) as the recorded version. The script returns a distinct code for each of
a conflict on (a), stale under (b) and stale under (c). The monitor counts each, and records no
event for a refused write.

**B3. A stale instance reads, reloads, and does not write.** An instance with v(i, I) < P(I):
- skips writes for I in that pass;
- requests an **immediate reload** of I's snapshot, outside the refresh cycle and with the
  configuration contract's deadline T_L;
- still reads each key's state, to refresh its `isOpen` copy (B5).

**B4. One snapshot per pass, and settings bind from publication.**
- **One snapshot per pass.** For each institution, a pass reads its snapshot once and uses it for
  every key, so one pass never mixes versions.
- **From publication on, no write under an older version succeeds** (B2(b)). Between installing a
  version and publishing it (at most R_m + E), the installing instance still evaluates under it.
  Others may still write under the older one.
- **Nothing is re-decided after the fact.** A breaker opened under older settings follows the newer
  settings' rules from the next evaluation: it closes after the newer clean reset from its
  recorded `last_breach_at`, and stays open while the newer settings see a breach. No history beyond
  the buckets and the state is needed.

**B5. Every instance refreshes its decisions' copy every pass.**
- **When Redis answers.** Every instance, stale or not, reads every active key's state once per
  pass. A decision's `isOpen` is then at most R_m + 2E old under fixed delay.
- **When Redis does not answer.** The pass ends, the failure is counted, and decisions keep the last
  copy until Redis answers. The staleness bound does not hold during a Redis outage.

**B6. Scheduling and isolation.**
- The monitor runs on its own single-thread scheduler, by fixed delay, shared with nothing.
- An institution whose snapshot cannot be loaded is skipped for that pass and counted. The others
  are evaluated.
- An exception on one key skips that key, is counted, and does not end the pass.

**B7. Redis loses P(I).**
- **B2(c) still protects every recorded state.** After a failover that loses keys, P(I) is absent
  (0) until the next publishing pass.
- **A stale instance can write unrecorded keys for at most one pass.** It needs a current instance
  that is live (B9). Any current instance republishes on its next pass.
- **The state hash itself.** If the state hash is lost, the breaker reads closed. That is today's
  behaviour, outside this contract.

**B8. A restore is detected, not repaired.** If an instance's installed version, which comes from
PostgreSQL, is below P(I) for longer than one refresh cycle after a reload, that instance raises
`fs_breaker_published_ahead_of_database`. This happens when A2's runbook step was skipped after a
restore.
- The instance does not lower P(I) itself. A probe that lags, or a stale instance, must never undo a
  publication.
- The runbook step (A2) is the repair.

**B9. Liveness.**
- **When it holds.** Breaker evaluation for institution I progresses while at least one live
  instance can install I's newest version.
- **When it fails.** If none can, for instance because loads fail on every instance whose version
  is behind, no instance writes I's breakers. Open ones then stay open, and new breaches do not
  open.
- **The alert.** Every successful evaluating pass writes `fs:{I}:mcc:evaluated-at`.
  `fs_breaker_unevaluated_seconds{institution}`, the age of that time, alerts at 60 s.
- **The link to the other contract.** This is the breaker form of the configuration contract's Q1:
  a database outage that stops loads also stops breaker evaluation for any institution whose
  newest version is not yet installed everywhere.

## 4. Bounds (fixed delay, E ≤ 1 s)

A settings change committed at DB time t meets these bounds:
- **Installed** on some instance by `t + R + 2C` (configuration contract C4).
- **Published** within that instance's next pass, by `+ (R_m + E)`.
- **Binding for writes** from then on (B2(b)). A changed state is written within the same or the
  next pass, `+ (R_m + 2E)` at most.
- **Seen by every decision** after one more pass of reads, `+ (R_m + 2E)`.

**Total:** `t + R + 2C + 3R_m + 5E`.
- With R = R_m = 5 s and E ≤ 1 s, that is `25 + 2C` s.
- E.6's 60 s holds while C ≤ 17.5 s. That is `T_P + ⌈N/K⌉·T_L ≤ 17.5`, so N ≤ 28 institutions
  changing at once with K = 4 and T_L = T_P = 2 s.
- A single change, with ordinary loads, takes about 27 s.

**One exception the bound cannot cover.** A loosening that meets a breaker already open closes it
only after the newer clean reset has run from its last breach (B4), which can be up to 10,080
minutes. The bound is when the new rules apply, not when every breaker reflects them.

## 5. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | Once an instance has published v, a write under a lower version is refused (code (b)), even when the stale instance read the state after the current instance's write | B1, B2 |
| P2 | A state recorded under v is never overwritten under a lower version, even with P(I) lost | B2(c), B7 |
| P3 | A stale instance refreshes its `isOpen` copy every pass, and requests a reload | B3, B5 |
| P4 | After publication, a tightening that lengthens the clean reset keeps open a breaker a stale instance would have closed | B2, B4 |
| P5 | After publication, a loosening is not undone by a stale instance opening at the old threshold | B2, B4 |
| P6 | One institution's failed load, or one key's exception, does not stop the others | B6 |
| P7 | Publication never lowers P(I), and a database version below P(I) raises the alert without lowering it | B1, B8 |
| P8 | With no current live instance, `fs_breaker_unevaluated_seconds` alerts within 60 s | B9 |
| P9 | A pass uses one snapshot per institution, even when a refresh installs a newer one mid-pass | B4 |
| P10 | A refused write records no event, and each refusal code is counted separately | B2 |
| P11 | During a Redis outage the pass ends counted, and `isOpen` keeps the last copy | B5 |

## 6. Accepted costs

- **Settings bind from publication.** A transition made under the old settings stands, and the new
  rules act on it.
- **A stale instance can still write** before publication (at most R_m + E after the first install),
  and for at most one pass after Redis loses P(I).
- **The two outages:**
  - **PostgreSQL** (B9): breakers freeze for institutions whose newest version is not installed;
  - **Redis** (B5): every decision uses its last `isOpen` copy.
- **The breaker bound is longer than the threshold bound:** N ≤ 28 against the configuration
  contract's N ≤ 48.

## 7. Review 1's findings and their answers

| Finding | Answer |
|---|---|
| M1 MAJOR: no live current instance, no evaluation | B3's immediate reload; B9 states the liveness condition and its alert; P8 |
| M2 MAJOR: versions in Redis outlive a restore | A2's runbook step; B8 detects it and never lowers P(I); P7 |
| M3 MAJOR: old-release monitors during a rolling deploy | A3: the stop-and-drain cutover |
| M4 MAJOR: the pass count and N ≤ 40 | §4 recounted under fixed delay: `3R_m + 5E`, N ≤ 28; B5's `R_m + 2E` |
| m1 cross-slot keys | §2: P(I) carries the `{I}` hash tag |
| m2 absent values, return codes, version 0 | §2: absent means 0; B2's codes; A1: V81 seeds version 1 |
| m3 install before publish; versions within a pass | B4: one snapshot per pass; binding from publication; P4 and P5 say "after publication" |
| m4 Redis down | B5 and B6: the pass ends counted, and decisions keep the last copy; P11 |
| m5 a loosening meeting an open breaker | §4's exception |
| m6 E unbounded; §1 overstated | E is measured and alerts above 1 s; §1 corrected |
| m7 missing properties | P8 to P11 |
