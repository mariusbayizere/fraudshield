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
