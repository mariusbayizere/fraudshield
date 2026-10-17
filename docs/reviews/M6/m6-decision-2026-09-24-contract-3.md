> Recorded by the author on 2026-09-24. This is the review of draft 3 of the ordering contract (at
> `d8b897b`), reviewed on its own. Draft 4 answers each finding.

# M6 review: the decision-fact ordering contract, draft 3

Reviewer: a fresh, independent reviewer. Read-only, against HEAD.

## Verdict: not sound (1 MAJOR, 3 MINOR, 5 NIT)

Resolved from draft 2: 2-M1, which the reviewer verified. `Fingerprints.of` covers the validated body
only, not the receipt time, the institution or the API key, so a same-body duplicate has the same
F and a different body has a different one. Also resolved: 2-m1 to 2-m5 and 2-n1 to 2-n6. S2r's
"latest not DECLINE" means "the customer approved". Only partly resolved: 2-M2.

Also verified:

- kafka-clients is 4.2.1, and the group uses the eager range assignor.
- `commitSync(Map)` commits only the partitions listed.
- `committed()` works in `onPartitionsAssigned`.
- The metadata is far below 4096 bytes.
- Nothing else keys on B being random.

| # | Severity | Finding |
|---|---|---|
| M1 | MAJOR | The clock resets on any non-S4 record, so orphan, valid, orphan, valid costs 10 minutes per orphan. That is k × the bound, which section 9 rejects. With about 72 or more lost parents an hour, lag grows until intents expire unread at 3 days. |
| m1 | MINOR | The commit of X+1 after a dead-letter carries no metadata, so the trip and the clock are lost if the consumer crashes while the partition is tripped. The 5 s loss claim is false. |
| m2 | MINOR | The 5 s checkpoint interval is arbitrary. Checkpoint on every re-read. The timing of the first checkpoint is unspecified. |
| m3 | MINOR | `onPartitionsLost` defaults to revoked, which would try to commit. `m` carries no offset. |
| n1–n5 | NIT | The port `compose` does not receive F. `occurred_at` differs between duplicates. Which link value is recorded is unspecified. The metric name should follow `fs_`, and a stopped instance's spool must be drained too. S0 has no invariant and no named reason. |
