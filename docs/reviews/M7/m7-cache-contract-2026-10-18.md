# Review record: the API-key cache contract and its implementation (2026-10-18)

Branch `m7/staff-auth`. Every reviewer was fresh and independent and wrote none of the work.

The owner's instruction (2026-10-18) was: write the caching and eviction rule as a contract first,
have it reviewed on its own, then implement against it. If a code re-check still finds a MAJOR in
this component, stop.

## Contract reviews

| Review | Subject | Verdict | Outcome |
|---|---|---|---|
| 1 | Option 2, draft 1 | UNSOUND (5 MAJOR) | The instance-wide "failing since" time was flawed |
| 2 | Option 2, draft 2 | UNSOUND (4 MAJOR) | The per-key failure time stuck; the success duration was ignored; "unavailable" was undefined |
| 3 | Option 2, draft 3 | UNSOUND (2 MAJOR) | The run/W mechanism reasoned about read starts, but runs are decided by observation order. Under the owner's rule, option 2 was abandoned for option 1 |
| 4 | Option 1 | SOUND_WITH_CHANGES (1 MAJOR) | A read that saw a revocation did not end stale use. Fixed by R2(e) |
| 5 | Option 1, delta | SOUND_WITH_CHANGES (1 MAJOR) | R2(e) could be undone by an older read. The claim was weakened to the 10-minute bound; no mechanism was added |
| 6 | Option 1, second delta | SOUND_WITH_CHANGES (no MAJOR) | Wording only; the contract was approved (`be9546d`) |

Draft 3 kept the owner's three fixes, which are not about option 2 (finding 4's unavailability
definition, limiter-first, and finding 3's over-eviction). All three carried into option 1.

## Code re-check (`cca1283..429ea05`)

**APPROVED_WITH_MINORS, no MAJOR.** Every rule from R0 to R9 is implemented as written. 47 tests
were examined against the deletion standard, and 47 fail with their rule deleted or weakened.

| # | Finding | Response |
|---|---|---|
| 1 | P22's clause "R7 leaves the cache untouched" was unproved | `p22ErrorThatIsNotAnOutageLeavesTheCacheUntouched`: after the R7 error, the entry is still served stale, and it was not renewed |
| 2 | P28's two-filter sequence was untested | P28 narrowed to what is proved. "Never more permissive" holds by construction |
| 3 | P19 did not test each type both bare and wrapped | Parameterised over every type, bare and wrapped; XX002 wrapped too |
| 4 | §3's check order was untested | `overLimitAddressStillGetsTheEarlierAnswersFirst` |
| 5 | The Redis test could have passed on the fallback | Asserts the count in Redis's sorted set |
| 6 | The 1 s bulkhead wait was tight for CI | 5 s |
| 7 | Three Javadocs overstated | Reworded |
| 8 | `rootState` could loop on a cause cycle | Uses an identity set |
| 9 | Pool fallback, interrupt without a second lookup, `Error` | Not changed: the defaults are Hikari's own, the interrupt path is proved by P35, and an `Error` still releases the permit |
