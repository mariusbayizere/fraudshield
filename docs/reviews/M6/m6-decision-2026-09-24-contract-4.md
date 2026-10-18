> Recorded by the author on 2026-09-24. This is the review of draft 4 of the ordering contract (at
> `bad4263`), reviewed on its own. Draft 5 answers each finding.

# M6 review: the decision-fact ordering contract, draft 4

Reviewer: a fresh, independent reviewer. Read-only, against HEAD.

## Verdict: not sound (1 MAJOR, 4 MINOR, 4 NIT); no safety path reopened

All of draft 3's findings are resolved (3-M1 structurally). No path sends without B, sends twice,
or stalls for ever under the intended reading.

Verified:

- Repeated commits of X with new metadata are legal.
- `commitSync` of `done` must merge in the waiting partition's checkpoint.
- The revoke commit is legal under eager classic assignment, and a fenced member takes the lost
  path.
- A crash between the dead-letter and the commit of X+1 produces a second DLQ copy, which the
  replay's `event_id` dedupe republishes once.

| # | Severity | Finding |
|---|---|---|
| M1 | MAJOR | W-b's "interval between consecutive S4 answers" and "non-S4 time drains" both claim idle time. An orphan after an hour of idleness following a trip gets either 500 ms or the full bound. Owner changes and staying put give different results. |
| m1 | MINOR | Whether S5 time drains is undefined. Excluding whole intervals that contain an S5 failure over-excludes (57014 counts as transient). A crash in S5 drains through `at`. |
| m2 | MINOR | W-c2 promises more than W-b counts: the final interval of a wait, backoff-driven re-reads, and a throughput claim that fails while tripped, because the 500 ms pause holds the whole consumer thread. |
| m3 | MINOR | W-e relies on the instances' wall clocks. Clamp at zero, WARN on a future `at`, and state that clocks are synchronised. |
| m4 | MINOR | Section 11 omits the webhook consumer, which shares `EnvelopeConsumer` (Handler and D-a), and the `subscribe(topics, listener)` change. |
| n1–n4 | NIT | `SmsPolicyTest` also calls `compose`. The sender reads the intent's locale for the FAILED row. I14 and W-f understate the drain while a partition is unowned, and the revoke commit should use `position(p)`. Tests are missing for: idle after a trip, S5 draining, a future `at`, a crash between DLQ and commit, and a grace re-read that hits S5. |
