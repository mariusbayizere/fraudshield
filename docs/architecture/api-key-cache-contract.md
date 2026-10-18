# API-key record cache: the serving and eviction contract

Status: **option 1, approved for implementation** (2026-10-18). Reviews: option 2 drafts 1–3
unsound; option 1 reviewed SOUND_WITH_CHANGES, then two delta reviews, the last with no MAJOR. Code is to be written against this document, not the
other way round. The owner asked for it on 2026-10-18, after four code re-checks of D-15 had each
broken or widened something.

**History.** Option 2 tried to limit stale use during an outage to keys read shortly before the
outage. Its contract went through three drafts, and each one was found unsound by an independent
review.

The owner's rule was that a third unsound draft proves the design wrong, and that option 1 is then
adopted rather than a fourth draft. That has happened.

Draft 3's two MAJORs were in option 2's own mechanism: the failure run, G, and the outage-entry test
W. Its availability derivations followed the order in which failures *start*. Its rules followed
the order in which they are *observed*. That mechanism is gone.

The parts of the drafts that every review found sound are kept:
- the announcement counter and its atomic install;
- serving the current entry;
- the uncached-success rules;
- the definition of unavailability;
- the limiter checked first, and the bulkhead.

Scope: `ApiKeyAuthenticator`'s record cache, and the two filters that use it. Key verification itself
is unchanged (D-19): the HMAC under the record's pepper, the constant-time compare, and the state and
overlap checks.

## 1. Terms

All times are one instance's monotonic clock. Each instance has its own cache. Instances share only
the database and the Redis announcement channel.

| Term | Meaning |
|---|---|
| **T** | cache TTL, 2 s |
| **S** | stale cap, 10 min (owner decision 2026-10-17) |
| **record** | what one successful read returns for an existing key: id, institution, HMAC, pepper version, scopes, state, end of the rotation overlap |
| **read** | one call of `findCredential(keyId)`, against the primary database (assumption A1). Before the query is sent, the read takes its start time **s** and a snapshot **n** of the announcement count (R4). Its database snapshot is therefore at or after s. It ends in one of four ways: **succeeds** with a record; **succeeds** with "no such key"; **fails** (the database is unavailable, R8); or raises **another error** (R7). |
| **entry** | the cache's value for a key. **r** (the start of the read that installed it) and the record are immutable. **e**, when it must be read again, is `volatile`. All writes go through the key's atomic step, `ConcurrentHashMap.compute` on that key. R1 reads e without a lock. |
| **announcement** | a key-change message for a key ID, received from Redis (`onMessage`) or made locally after a commit (`keyChanged`) |

**Assumption A1.** Reads go to the primary, or to a source that reflects every commit before that
commit's announcement is sent. If M9 ever routes reads to a lagging replica, R4's guarantee no
longer holds.

## 2. Rules

**R0. Before any read: the limiter, then the bulkhead.**
- **(a) The failure limiter is checked first.** Before the authenticator is called, M7's filter
  checks the failure limiter for the client address, through a new **non-counting** operation,
  `RateLimiter.check(key, limit, window)`.
  - **Over the limit:** if the address already has 100 counted failures in its 15-minute window,
    the answer is **429** with `Retry-After`. No read and no verification happen.
  - **The check fails:** it falls back the way `attempt` does (Redis, then the in-memory limiter).
    It never produces a 500 or a 503.
  - **Counting:** failures are still counted after a failed verification (§3).
- **(b) A bulkhead covers uncached keys** (R9).

**R1. Serving from the cache.** A request whose key has a current entry C is answered from C, with
no read, if and only if `now < C.e` and `now − C.r ≤ max(T, S)`. With S ≥ T, as in production,
that is `now − C.r ≤ S`. With S below T (stale use disabled or short), an entry is still served
within its TTL, since `e = r + T` at install and only stale use renews it. Otherwise it reads (R2, R3, R7). The
order is fixed: look up C, then take s and n, then send the query.

**R2. A successful read**, with start s and snapshot n:
- **(a) Its own request** is answered with what it read: a record (then verified), or an unknown key
  (401).
- **(b) Usable record, and no announcement since n.** One atomic step on the key, against that key's
  removals, does this. The announcement count is compared with n **inside** the same `compute`
  as the install; a check before the `compute` would let R4(a) and R4(b) slip in between:
  - **No entry, or the entry has `r ≤ s`:** a new entry `{record, r = s, e = s + T}` is installed.
    An equal r is replaced.
  - **The entry has `r > s`:** nothing changes.
- **(c) Usable record, but an announcement (for any key) arrived since n.** Nothing is installed and
  nothing is removed. This is safe for these reasons:
  - The announced key's entry was removed by R4(b) when the announcement arrived.
  - Any entry for that key present now was installed by a read whose n was taken after R4(a)'s
    increment. Such a read sent its query after the commit, so it saw the commit (A1).
  - Entries for other keys are untouched by any announcement but their own.
- **(d) "No such key".** Nothing is installed. In one atomic step, the key's entry is removed if it
  has `r < s`.
- **(e) An unusable record.** This rule takes precedence over (b) and (c). A record is unusable if
  its state is REVOKED, or it is ROTATING with its overlap end at or before now. "Now" here is the
  authenticator's `Clock` instant, the same one verification uses, compared with verification's own
  strict predicate (usable if and only if the overlap end is after it). Such a record is not
  installed. In one atomic step, the key's entry is removed **whatever its r**. Its own request
  still gets 401 from verification (R2(a)).
  - **Safe:** a removal can never serve anything. It cannot undo an announcement or bring an entry
    back; it can only cause a read or a 503.
  - **Availability:** this costs nothing in practice. `ApiKeyService` rotates only an ACTIVE key and
    nothing moves a key out of REVOKED, so an entry removed this way could only ever have answered
    401. This assumes `Clock` does not step backwards; if it does, the cost is a read or a 503.
  - **Best effort only:** R2(e) is a tightening, not a guarantee. An older read still in flight (one
    that began before the revocation's commit) can install the old usable record afterwards,
    through R2(b)'s "no entry" branch. "r and removals" covers this. §4's bound does not rely on
    R2(e).

**R3. A failed read** (R8), with start s. Everything in R3 is one atomic step on the key. **now**
is read inside that step.
- **(a) No entry.** If the key has no current entry C, the request is refused (503).
- **(b) A fresher success.** If `s < C.r`, a read that began after this one succeeded and installed
  C. The request is answered from C's record, and nothing on C changes. There is no S check here,
  and none is needed, whatever S is (including S = 0, stale use disabled). C's snapshot is later
  than this request's start, so the answer is as fresh as a successful read of its own would have
  been: it is not stale use.
- **(c) Stale use.** Otherwise the request is answered from C's record if and only if
  `now − C.r ≤ S`. On stale use, `C.e = now + T`, so the next read comes a TTL after this failure.
  e is only ever set to r + T at install or to now′ + T at a renewal, and now is read inside the
  key's serialised step, so e never decreases. Nothing else on C, or on any other key, changes.
- **(d) Otherwise** the request is refused (503). Nothing is installed or removed.

R3 serves whatever entry is current when the failure is observed, whichever read installed it.
Requests blocked on the same outage therefore share one outcome per key, apart from where each
request's now falls against the S cap.

**R4. An announcement** for key k:
- **(a)** increments a single counter (all keys), so any read whose n is older sees it at R2;
- **(b)** then removes k's entry.

Within one instance, a received announcement is never undone:
- R2(b) refuses to install a record whose read overlapped it, even after a later removal;
- R2(c) installs nothing;
- R2(d) and R2(e) only remove;
- R3 never installs and changes only an entry still present;
- a read started after it sees the commit (A1).

So every decision taken after an announcement's receipt reflects that announcement, with two
exceptions. Both are requests concurrent with the announcement:
- **A read already in flight answers its own request** (R2(a)) with what it read.
- **An R1 lookup that took C before R4(b) removed it** is served from C. R1 reads without a lock,
  and its linearisation point is the lookup.

**R5. Unknown keys.** A key with no entry is answered only by a read (subject to R9). While reads
fail, it is refused (R3(a)).

**R6. Size cap.** About 10,000 entries. The check and the insert are not atomic, so the cap can be
exceeded by the number of concurrent installs. When a read is about to install (R2(b)) and the cache
is full, expired entries are dropped first, then all entries if it is still full. The drop touches
other keys, so it runs **before and outside** R2(b)'s atomic step on the key, never inside a
`compute`. It can remove an entry that R3(c) has just renewed. That costs a 503 (cost 2), never
safety. A dropped entry is never served stale (R3(a)).

**r and removals.** While a key has an entry, its r only increases (R2(b)). After an R2(d) or R6
removal, a read that began earlier can still install an entry with a smaller r. R4 removals are the
exception: R2(b) refuses such an install. The case after R2(d) is this:
1. a key row is deleted at time d;
2. this instance reads "no such key" and removes the entry;
3. a read that began before d then installs the old record.

That record can be served until its own `r + T`, and stale up to `r + S` in an outage. This is
within §4's window, since the deletion is a change after r that was not announced. In practice the
application cannot delete a key row at all:
- V11 grants `fs_app` SELECT, INSERT and UPDATE on `api_keys`, with no DELETE;
- no code issues a DELETE against `api_keys`;
- foreign keys reference `api_keys` from `api_keys.replaced_by` (V2) and from `batch_jobs` (V3).

R2(d) does not increment the announcement counter: that would let a flood of unknown key IDs stop
every install on the instance.

**The same applies after R2(e).** Suppose read A (start s₁) sees ACTIVE, slowly. A revocation
commits, unannounced. Read B (s₂ > s₁) sees REVOKED and removes the entry. A then finds no entry and
installs ACTIVE with r = s₁. That entry is served until s₁ + T, and in an outage stale until
s₁ + S. This is inside §4's bound, which is measured from r.

**R7. Other errors.** A read that raises anything not classed as unavailability by R8 answers
**500** to its request (§3). It does not touch the cache. It is not a failure for R3, and it is not
counted by the failure limiter.

**R8. "The database is unavailable"** is decided in one place, the authenticator, from the
exception's whole cause chain. That includes causes wrapped in Spring's `DataAccessException`. The
read **fails** if and only if some cause in the chain is one of:
- **(a) JDBC and network types:**
  - `java.sql.SQLTransientConnectionException`;
  - `java.sql.SQLRecoverableException`;
  - `java.sql.SQLTimeoutException`;
  - `java.net.SocketTimeoutException`;
  - `java.net.ConnectException`.
- **(b) An `SQLException` whose SQLSTATE is:**
  - any of class **08**: connection exceptions, including 08001, 08003, 08004, 08006;
  - any of class **53**: insufficient resources, including 53400;
  - any of class **58**: system error, including 58030 (I/O error);
  - **57P01** (admin shutdown), **57P02** (crash shutdown), **57P03** (cannot connect now: a
    database in recovery), **57P05** (idle-session timeout);
  - **57014**: query cancelled, which is how a statement timeout is reported.

**A misconfiguration also counts as unavailable.** Hikari wraps every failure to create a connection
in `SQLTransientConnectionException`, including 28P01 (bad password) and 3D000 (missing database).
So a misconfiguration is classed as unavailable: 503, with stale use until S. This is deliberate. A
new connection cannot be told apart from an outage at that point. The authenticator logs the root
SQLSTATE at error level on every failure.

**Decided as not unavailability,** so R7 (500):
- **25006** and **40001**. A SELECT on the primary cannot raise them (A1).
- **57P04**, database dropped.
- **XX001** and **XX002**, data or index corruption. A retry does not cure these.
- Every other SQLSTATE, including classes 22, 23 and 42.

On a failure, the authenticator follows R3. If R3 refuses, it throws **`KeyStoreUnavailableException`**,
an unchecked type in the auth module. That is the only signal for a 503. Any other exception
propagates unchanged.

**R9. The bulkhead for uncached keys.** A read for a key that has **no entry** must first take a
permit from a per-instance semaphore of **4** permits. It waits for a permit for **at most 500 ms**.
- **Pool size:** the permit count must be less than Hikari's `maximum-pool-size` (20 by default,
  `FRAUDSHIELD_DB_POOL`). The application refuses to start otherwise.
- **After the wait,** whether a permit was granted or not, the key is looked up again. If an entry
  is now present, any permit taken is released and the request follows R1, and on a read R2, R3
  or R7, without a permit. An interrupted wait does not look the key up again.
- **No permit within 500 ms, and still no entry:** the request is answered 503, through
  `KeyStoreUnavailableException`. It reads nothing, it is not a failure for R3, and the limiter does
  not count it.
- **Interrupted while waiting:** the interrupt flag is restored, and the request is answered 503 the
  same way.
- **A permit:** it is released in a `finally`, whatever the read's outcome: success, failure, R7, or
  any exception.
- A read for a key that **has** an entry never takes a permit.

## 3. What the filters answer

M7's filter (`ApiKeyAuthenticationFilter`) runs first, in Spring Security's chain. M6's filter
(`ApiKeyFilter`) runs after it, on the machine paths only, and calls the authenticator again
independently, through ingest's adapter in `StaffIdentityWiring`.
- **What the adapter translates.** It turns `KeyStoreUnavailableException` into ingest's own
  `ApiKeyStoreUnavailableException`, which M6's filter catches. Any other exception passes through.
- **Why the answers can differ.** Between the two calls the cache can change, through an
  announcement, the cap, R2(d), R2(e) or another read. So M6 can refuse (401, 500, 503) after M7 admitted,
  or verify a *different* record of the same key.
- **M6 is never more permissive in admission.** It is reached only after M7 admitted.
- **Which principal counts where.** M7's principal decides scope in the contract chain. M6's
  principal is the one attached for the handler. A scope difference between them comes from a key
  change, which ADR 0071 §6 bounds.

M7's filter checks in this order:
1. no key;
2. both headers;
3. R0(a), the limiter check;
4. the authenticator.

| Case | M7's filter | M6's filter (if reached) |
|---|---|---|
| No `X-API-Key` | passes on as anonymous; the contract chain answers 401 for a non-public operation | not reached in the assembled application; 401 if reached |
| Both `X-API-Key` and `Authorization` | 401, **not counted** | not reached |
| Address already over the limit (R0(a)) | **429** with `Retry-After`; no read | not reached |
| Malformed key, other environment, unknown key, wrong secret, revoked, or past its overlap | 401, and counted as a failed key. If that counting `attempt` itself refuses (a concurrent burst past the check, cost 10), 429 with `Retry-After` | not reached; 401 if reached |
| Verified (R1, R2, R3(b) or R3(c)) | continues as the key's principal; the contract decides 200 or 403 by scope | verifies again, then attaches its principal |
| `KeyStoreUnavailableException` (R3(a), R3(d), R9) | **503, `Retry-After: 1`**, `urn:fraudshield:problem:service-unavailable`; **not counted** | the same, from the translated exception |
| Any other exception (R7) | **500**, `urn:fraudshield:problem:internal`, written by the filter as `application/problem+json`; not counted; logged at error | the same, written by M6's filter |

**Never** a 401 or 429 because the database is unavailable. **Never** a 503 for an error that is
not unavailability.

## 4. The residual window

On one instance, **a revocation is missed** while that instance answers the key from a stale entry C
(R3(c)), when the revocation:
- committed after C.r; commits before C.r are in C's record, since the read's snapshot is at or after
  C.r;
- and was not announced to this instance (Redis down, or the subscriber reconnecting).

**The bound is S = 10 minutes,** in every outage: all instances or one, continuous or intermittent.
Such a revocation is missed until `C.r + S` at the latest, for the entry C that is current when it
is served. R2(e) usually ends it sooner: the next successful read that sees the revocation removes C.
That is best effort. An older read still in flight can install the old record again, and the
bound then runs from that record's own r ("r and removals"). The same holds for other unannounced
changes, such as narrowed scopes, which end at the next installing read (R2(b)) or at S.

- **An outage on every instance.** When the database itself is down, no revocation commits during
  it. The missed ones are those committed between C.r and the onset, and an idle key may have been
  read up to S before the onset. Pool exhaustion or 53300 on every instance is different: the
  database still commits, so that case is a local outage on every instance (below).
- **An outage local to this instance.** Other instances can commit revocations during the outage.
  Only the announcement protects this instance.

**While this instance reads successfully,** R1 and R2(b) (`e = s + T`, with s taken before the
query) give ADR 0071 §6. A missed announcement costs at most T.

**A received announcement is never missed** by a decision taken after its receipt (R4), under A1.

### Accepted costs

1. **The 10-minute window above,** whenever an announcement is missed during an outage.
2. **A key whose entry was just removed gets 503 for the outage.** The removal can come from its own
   announcement, R2(d), R2(e) or the cap. A cap clear-all near the onset refuses every key that way.
3. **An announcement for any key stops installs by every read in flight** (R2(c)). Under a burst of
   announcements, caching is less effective. It does not change outage behaviour: an entry stays
   eligible until S.
4. **There is no single-flight.** At each expiry, every request for a key that arrives before the new
   entry is installed reads. In an outage, each blocked read can wait about 12–14 s:
   - Hikari's 2 s connection timeout;
   - up to about 2 s of validation when a connection is borrowed;
   - pgjdbc's 10 s socket timeout.

   M6's second call normally hits the entry that M7's call installed or renewed. When it does not
   (cost 2, cost 3, or an expiry in between), it reads, and can block, again. Backlog: a single
   in-flight read per cached key.
5. **A slow database turns caching off.** A success that takes longer than T installs an already
   expired entry.
6. **Every key is refused once `now − C.r > S`.** The exception is R3(b), whose C is fresher than
   the request.
7. **The bulkhead can give 503.** When 5 or more distinct uncached keys need a read at once and no
   permit frees within 500 ms, the extra keys get 503 (R9). That happens after a restart or deploy,
   after a cap clear-all or an announcement burst, when database latency rises, or under a flood of
   unknown key IDs.
8. **A known key ID bypasses R9.** Key IDs are public. A flood that uses a live key ID with wrong
   secrets hits the cache and takes no permit. It reads only in the expiry stampede of cost 4, and
   it is bounded by the limiter: 100 counted failures per address per 15 minutes.
   - **A healthy database:** the stampede window is the query time, a few milliseconds.
   - **A slow database, attacked from many addresses:** it can occupy connections and push this
     instance into a local outage. Under option 1, that is already inside the 10-minute window.
   - **The fix:** the single-flight backlog row (cost 4) closes it.
9. **The limiter is keyed on the remote address.** Any shared address triggers this: a proxy that
   does not preserve client addresses, a NAT, or an institution's egress gateway. One misbehaving
   client behind it can make R0(a) refuse every client there, verified keys included, for up to 15
   minutes. This was already true of the counting, and R0(a) extends it to verified keys.
10. **The limiter check runs on every request.**
    - **Healthy:** one Redis round trip is added to every API-key request.
    - **Redis black hole:** each check waits for the Redis command timeout (300 ms) before the
      in-memory fallback, which is per instance.
    - **Not atomic:** check-then-count lets an address at 99 send a concurrent burst before any of
      it is counted. R9 bounds this only for uncached keys.
11. **A misconfiguration or a bug can look like an outage** (R8): 503, with stale use until S.
    - **Misconfiguration:** a bad password or a missing database.
    - **Connection leak:** a leak in application code shows as Hikari pool exhaustion.
    - **08P01:** a protocol violation answers 503.

    The reverse misclassifications answer 500:
    - 0A000 ("cached plan must not change result type") during a rolling migration;
    - Hikari's "has been closed" during shutdown, which carries no SQLSTATE.

## 5. Properties a test must demonstrate

Each property below has a test that fails if the cited rule, or its guard, is deleted or weakened.
One requirement has no deterministic test, and rests on review: R2(b) compares the announcement
count *inside* the key's `compute`. A check moved before the `compute` differs only when an
announcement lands between the two, which no test can schedule without a seam in that gap.

| # | Property | Rule |
|---|---|---|
| P1 | A key with an entry is admitted through an outage until C.r + S, and refused after it | R3(c), R3(d) |
| P2 | R3(c)'s S check uses now after the block: a read that began before C.r + S and failed after it gets 503 | R3(c) |
| P3 | A failure whose read began before C.r is answered from C and does not renew it | R3(b) |
| P4 | Requests blocked in the same outage on one key are all admitted while within S | R3(c) |
| P5 | A failed read that blocked longer than T is not repeated by the next request within T | R3(c), `now + T` |
| P6 | A renewed entry is not served past C.r + S without a read | R1 |
| P7 | A key never read by this instance gets 503 during an outage | R5, R3(a) |
| P8 | An announcement received before an outage is honoured during it | R4(b), R3(a) |
| P9 | An announcement received while a failing read is blocked: that request and later ones get 503 | R4, R3(a) |
| P10 | An announcement for this key received while a successful read is in flight: that request is answered, and nothing is installed | R2(c), R4(a) |
| P11 | An announcement for key j while key k's successful read is in flight: k's entry is neither installed nor removed | R2(c) |
| P12 | An announcement for key j does not remove or change key k's entry | R4(b) |
| P13 | "No such key" answers 401, is not cached, and removes an older entry, which is then not served stale | R2(d) |
| P14 | "No such key" does not remove an entry with r ≥ its s | R2(d) |
| P15 | While an entry is present, an older read cannot replace a newer one | R2(b) |
| P16 | When full, expired entries are dropped before live ones; a dropped entry gets 503 in an outage | R6, R3(a) |
| P17 | `keyChanged` and `onMessage` both evict and both stop the install of a read they overlap | R4, R2(c) |
| P18 | While reads succeed, an unannounced revocation is honoured within T | R1, R2(b) |
| P19 | Each R8(a) type and each listed R8(b) SQLSTATE, bare and wrapped in a `DataAccessException`, is a failure; 42601, 25006, 40001, 57P04, XX001, XX002 and a non-SQL exception are not | R8 |
| P20 | Hikari's `SQLTransientConnectionException` wrapping 28P01 is a failure | R8 |
| P21 | Both filters answer 503 with `Retry-After: 1`, not counted, on unavailability | §3, R8 |
| P22 | Both filters answer 500 `internal` for bad SQL, without touching the cache or the limiter | R7, §3 |
| P23 | An address over the limit gets 429 without a read; the repository is not called | R0(a) |
| P24 | The limiter check does not count: N checks by a verified key leave the count unchanged | R0(a) |
| P25 | A limiter error during the check falls back and never answers 500 | R0(a) |
| P26 | With 4 uncached reads blocked, a fifth unknown key gets 503 after at most 500 ms without a read, while a cached key still refreshes | R9 |
| P27 | A permit is released after a success, a failure, an R7 error and an unexpected exception | R9 |
| P28 | M6's filter refuses (503, 401) after M7 admitted when the cache changed in between, and answers 503 from the translated exception | §3 |
| P29 | "Both headers" answers 401 and is not counted | §3 |
| P30 | A successful read returning REVOKED, overlapping an unrelated announcement, removes the ACTIVE entry; a following outage gets 503 | R2(e) over R2(c) |
| P31 | A successful read returning REVOKED, when the entry has a later r, removes it | R2(e) over R2(b) |
| P32 | A read that returns ROTATING past its overlap end installs nothing and removes the entry | R2(e) |
| P33 | A read with r equal to the entry's replaces it | R2(b) |
| P34 | A request that waited on the bulkhead while another installed the key is served from that entry; if that entry has expired, it reads without a permit | R9, re-lookup |
| P35 | A bulkhead timeout or interrupt answers 503, is not counted, and an interrupt restores the flag | R9 |
| P36 | Start-up fails when the permit count is not below the pool size | R9 |
| P37 | No path moves a key out of REVOKED or out of an expired ROTATING state (the premise of R2(e)'s availability claim) | R2(e) |
