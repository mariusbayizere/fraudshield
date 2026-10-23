# M10 step 2, ingest leg: independent reviews

Three review rounds, each by an independent reviewer agent that read the diff, the installed Locust
2.43.4 source and the backend on the branch, and edited nothing. The findings below are summarised
from their reports, 2026-10-23; each is followed by its answer.

## Review 1: the harness changes and `m10-harness.yml` (commit `ab40437`, before it ran)

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | High | The preflight caught only `OSError` and `ValueError`; anything else (an `http.client.HTTPException`, a missing key) escaped, and Locust's event hook logs a listener's exception and carries on, so users spawned against an unchecked target | The whole preflight is wrapped; any exception refuses with exit 3 |
| 2 | High | "Locust only logs a task's exception" and "would also have exited 0" were false: Locust records task exceptions and exits 1 (`main.py:626-627`). Only the shape defect gave exit 0 | Corrected in the README, the docstring and the commit message, as an inference from the source |
| 3 | Medium | The key check (`GET /decisions/{id}`) tested the `decisions:read` scope, not `ingest:write` | The key check is an ingest `POST` with a malformed body, which must answer 400; the API checks the key and scope before it reads the body |
| 4 | Medium | Exit statuses 5 and 6 were claimed but no case triggered them | Two mutant cases restore the shape and the pacing defect and must exit 5 and 6 |
| 5 | Medium | In a distributed run, a refusal on a worker sets only the worker's status; the master never sees 3 or 4 | Documented as a limitation, not fixed; filed as PB-197, to fix before any distributed run |
| 6 | Medium | The commit message miscounted the cases | Corrected |
| 7 | Low | `runner.quit()` called inside `test_start` kills its own greenlet | Quit from a spawned greenlet |
| 8 | Low | The stats CSV misses the last second of a run, and a fixed 5 s wait for Kafka could let late records into the next case | Counts from the harness's own provenance line; Kafka polled until two reads agree |
| 9 | Low | The proxy wrote its counts file before binding its port | Written after binding |
| 10 | Low | The `use_common_options` comment overstated what it does | Corrected |
| 11 | Low | Checkout kept the token while unpinned PyPI packages ran | `persist-credentials: false` |
| 12 | Low | The README's exit-3 row omitted non-401/403 key-check statuses | Corrected |

## Review 2: the recheck (commit `b75a60c`)

No blocking findings. The reviewer reproduced the refusal and mutant cases against a scratch fake
upstream. Five wording points, all answered in `4ac1092`: the proxy's ingest count includes the
preflight's key check; a mutant whose substitution fails stops the script rather than failing one
case; "writes nothing" narrowed to "writes no transaction or decision"; the commit message's
description of the refusal cases; the README's distributed-mode sentence marked as an inference.

## Review 3: the FX seed (`fix/demo-fx-rates`), the failure messages (`c31d163`) and the records (`37ff5f3`)

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | Major | A failed connection gives a response with no body; `json()` then raises `TypeError`, which `json_object` did not catch, so the request left the failure table and showed only as a task exception | `TypeError` caught |
| 2 | Major | The FX cause was stated as fact before the sentence that marked it as inferred, in `M10_updates.md`, the notebook and the seed test's comment | Each now says it is inferred from the ratio and the code |
| 3 | Major | The review claims in `M10_updates.md` and PB-197 traced to no record | This file |
| 4 | Minor | The artifact's CSVs, summary and logs give slightly different counts | The logs' final tables are named as the source, with the CSV figures and the replays stated |
| 5 | Minor | The owner's words were paraphrased under the owner's name | Quoted verbatim |
| 6 | Minor | "The class of failure is now caught" overreached two mutants | Narrowed |
| 7 | Minor | PB-196 omitted that withholding the reason from clients is by design (`IngestService.java:89`) | Cited, and the acceptance separates logging the reason from changing the body |
| 8 | Minor | The seed's rate date Javadoc claimed to precede any transaction the demo can receive; validation bounds only the future | Reworded |
| 9 | Minor | `JdbcFxRates` caches a missing rate for ten minutes | Stated in PB-195's acceptance |
| 10 | Minor | No test where one currency already has a rate | Test added; the loader's own refusals remain untested |

Raised for the owner, not decided: the country packs' rates are 2024 World Bank averages held fixed
for the simulation, and ADR 0063 and V61 say development and demo databases hold synthetic rates
only (D-21). Recorded in PB-195.

## Review 4: the accepted-currency split (`dd36b91`, amended to `7e0c418`, before it ran)

No blocking findings in either round. Answered in the amended commit: a statistics reset now clears
the split too (the review named `reset_stats`; checking Locust's source showed that `--reset-stats`
resets on spawning complete without firing it, so both are handled, and the recheck confirmed the
two stay together); the passing case requires at least 60 accepted, so that "a missing currency is a
finding, not chance" holds (below 1e-5 at 60); "accepted" narrowed to what the harness counts as a
success; and the split described as a local run's, since in a distributed run a worker's
`accepted_new` covers only its last report to the master (PB-197). Nits: a variable made local, the
script's case table, the commit subject, a line re-wrapped.

## Review 5: the re-run's record in `M10_updates.md` (`9f33452`), against the run's artifact

Every number matched its source. Answered: the record claimed "the ingest harness" where only the
`sustained` scenario ran (the other five are filtered out by `--tags sustained` and have never
run), now said in both places; the M8B prerequisite and "no cluster" attributed to the owner, as the
plan does not state them; the key-revoked row's count of 10 noted as including the key check; the
seed's commit given as `6bdb77a`; "job" corrected to the workflow's run; lines re-wrapped. The
recheck found the filtered-scenario sentence true of nine logs of ten, not every one (the
shape mutant spawns no user), now said. The reviewer could not verify Review 4's account of its
rounds: no review transcripts were among its sources.

## Review 6: PB-198 and PB-199 (`0f2c704`, amended to `214cf28`)

Every number and identifier matched its source. Four points answered: PB-199 said step 2's
three defects were in the one scenario that ran, where they were in code all six scenarios share
(the plateau shape, the base class's pacing, `response.js` in every scenario), and dropped that
"after at most one request per user" is inferred from Locust's source; "none has ever sent a
request" rested on the record, now said so; PB-198 said a duplicate publish would pass every run,
where the allowance caps it at one per user. The recheck found nothing to change. The owner then
asked for PB-199 to record the first point as the reason to raise its priority.

## Review 7: the records of `7947dc5` (PB-199's raised priority, the notebook sighting, safe-push)

Every fact matched (`safe-push` only on `m8/frontend-rebase`; the pushed tree identical to the
reviewed one). Answered: the sighting's number now says what it counts, and names the different
case `5640dcf` calls "the fourth variant"; PB-199 says `response.js` was also repeated in each of
the five scenarios' own tasks, not only in shared code; "identical to the reviewed commit" narrowed
to its tree; Review 6's count corrected to four points.

## Review 8: the per-record match (PB-198; `bca3357`, amended to `0a09cff`, then `6b3e094`, `c8f6aa2`)

Four rounds, before each run. Answered: `docker compose exec` inside the offsets loop would have
taken the loop's stdin and read one partition of three (high; `</dev/null`); a short or failed read
of a partition is reported as such, not as missing records; the reconciliation cannot abort the
script; a stale ids file is removed before each case; a failed ids write is logged and changes
nothing; driven by the after-snapshot, so a missing before-snapshot is reported; a duplicate's row
says whether its `event_id` repeats and whether the id was replayed; `--formatter-property` instead
of `--property`, whose deprecation warning Kafka 4.3 prints on stdout (seen in run 39956259180);
a line whose key is not a UUID is counted apart as unparsed. Noted, not fixed: CI never runs mypy
over `tests/performance` (filed as PB-200), and offsets are computed from position, which a stray
line would shift.
