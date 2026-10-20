# The MCC circuit-breaker monitor: who writes breaker state, under which settings

Status: **draft 1, for independent review.**

The owner split this out of the configuration contract (`risk-configuration-propagation-contract.md`)
on 2026-10-20, after that contract's third unsound draft. Two of draft 3's MAJORs were in its C8:
- a leader-only monitor trusted a leader that could itself be stale;
- re-deciding past transitions needed history that is not kept.

This contract replaces C8. Code is written against it, not the other way round.

**Scope.** This contract governs three things:
- which instance may evaluate and write a breaker's state in Redis;
- how a breaker-settings change, delivered by the configuration contract's snapshots, reaches that
  state;
- how decisions read it.

The settings' storage, versions, dual control and propagation are the configuration contract's.

**Sources:**
- FR-03-07 and D-18;
- ADR 0061, point 4;
- `CircuitBreakerMonitor`, `MccCircuitBreaker`, `RedisCircuitBreakers`, `CircuitBreakerPort`,
  `BackgroundTasks` and `DecisionService` (`isOpen`);
- the configuration contract's C2 to C4;
- review 3 of that contract: M1, m1 and m2.

## 1. What exists

- **Every instance runs the monitor every 5 s,** on the shared two-thread scheduler. For each
  active key `(institution, MCC)` it:
  1. reads the settings from its snapshot;
  2. reads the window's one-minute buckets (kept 25 h);
  3. reads the state hash (`open`, `opened_at`, `last_breach_at`, and a change counter `version`);
  4. evaluates `MccCircuitBreaker.evaluate`;
  5. writes only a changed state, by a Lua compare-and-set on the counter.
- **Decisions read `isOpen`** from a per-instance copy. That copy is refreshed only when the instance
  itself reads or writes the state.
- **Nothing records which settings a state was written under.**
- **A stale instance can therefore undo a newer setting's effect, and nothing corrects it.** This is
  an instance that has not installed a change. For example, it can close a breaker under the old,
  shorter clean reset, or open one under an old, lower threshold.

## 2. Terms

| Term | Meaning |
|---|---|
| **v(i, I)** | the breaker-settings version installed on instance i for institution I (configuration contract C3) |
| **published version P(I)** | `fs:mcc:settings-version:<I>` in Redis: the highest v(·, I) any instance has published |
| **recorded version** | the settings version stored in a breaker's state hash by the write that last changed it |
| **R_m** | the monitor period, 5 s |
| **E** | one evaluation pass's duration, measured and exported |

**Assumptions.**
- **A1.** The configuration contract holds. In particular, an instance's settings version never
  decreases (its P2).
- **A2.** Redis is the shared store for counts and state, as today.

## 3. Rules

**B1. Every instance publishes what it has installed.** At the start of each pass, for each
institution it has a snapshot for, an instance raises P(I) to v(i, I), using a Lua "set if greater".
P(I) therefore never decreases while Redis keeps it.

**B2. Only an instance that is current may write.** The compare-and-set becomes one Lua step. It
writes the new state only if all three of these hold:
- (a) the change counter equals the one the caller read, as today;
- (b) the caller's settings version is at least P(I);
- (c) the caller's settings version is at least the state's recorded version.

A write stores the caller's settings version as the recorded version. Checks (b) and (c) run in
the same atomic step as the write.

**B3. A stale instance reads but does not write.** An instance whose v(i, I) < P(I) skips
evaluation for institution I in that pass. It still reads each key's state to refresh its `isOpen`
copy (B5), and it counts the skip.

**B4. Settings apply prospectively, from the first install anywhere.**
- Once any instance has installed version v and published it (B1), no write under an older version
  can succeed (B2(b)).
- Writes made before that, under the old version, were made when no instance had installed v. That
  is "before the change took effect", exactly as for any other decision inside the propagation
  bound.
- No transition is re-decided after the fact. A breaker opened under the old settings follows the
  new settings' rules from the next evaluation: it closes after the new clean reset, measured from
  its recorded `last_breach_at`, and stays open while the new settings see a breach.
- So no history beyond the existing buckets and state is needed (review 3, m2).

**B5. Decisions read a copy refreshed every pass.** Every instance, stale or not, reads every active
key's state once per pass and refreshes its `isOpen` copy. A decision's `isOpen` is therefore at
most R_m + E old.

**B6. Scheduling and isolation.**
- **Its own scheduler.** The monitor runs on its own single-thread scheduler, shared with nothing,
  every R_m.
- **Failures stay local.** An institution whose snapshot cannot be loaded is skipped for that pass
  and counted; the others are evaluated. A Redis failure ends the pass and is counted.

**B7. Redis loses P(I).**
- **What happens.** After a Redis failover that loses keys, P(I) is absent until the next pass
  publishes it again. In that interval, B2(b) is vacuous and only B2(c) protects a state.
- **The bound.** A stale instance can write for at most one pass (R_m + E) after the loss. Any
  current instance republishes on its next pass.
- **What a lost state means.** If the state hash itself was lost, the breaker reads closed. That is
  today's behaviour, and outside this contract.

## 4. Bounds

- **A settings change committed at DB time t is in force for breaker writes**
  (no older-version write succeeds) from the first moment any instance has installed and published
  it. By the configuration contract's C4, that is by `t + R + 2C + R_m + E`.
- **Every decision sees its effect by `t + R + 2C + 2(R_m + E)`.** That is one pass to write the
  new state and one pass for every instance to read it.
- **E.6's 60 s holds while** `R + 2C + 2(R_m + E) ≤ 60 s`. With R = R_m = 5 s, E under 1 s, and the
  configuration contract's worst-case C, that allows N ≤ 40 institutions changing at once
  (review 3, m1, corrected).

## 5. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | Once an instance has published version v, a write by an instance on a version below v is refused. This holds even when the stale instance read the state after the current instance's write | B1, B2(b) |
| P2 | A state written under version v is never overwritten by a write under a version below v, even if P(I) has been lost | B2(c), B7 |
| P3 | A stale instance still refreshes its `isOpen` copy every pass | B3, B5 |
| P4 | A tightening that lengthens the clean reset keeps open a breaker that a stale instance would have closed | B2, B4 |
| P5 | A loosening that raises the threshold is not undone by a stale instance opening at the old threshold, once any instance has published it | B2, B4 |
| P6 | One institution whose snapshot fails to load does not stop the evaluation of the others | B6 |
| P7 | Publication never lowers P(I) | B1 |

## 6. Accepted costs

- **Prospective settings.** Settings apply from the first install anywhere. A transition made under
  the old settings stands, and the new settings act on it from then on.
- **One pass after a key loss.** After Redis loses P(I), a stale instance can write for at most one
  pass.
- **The breaker bound is longer** than the threshold bound by two monitor passes (§4).
