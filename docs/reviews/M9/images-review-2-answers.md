# M9 images, review 2: answers

The review is `images-review-2.md` (branch `m9/images`, `42c13cc..db89bbf`; 0 BLOCKER, 2 MAJOR,
8 MINOR, 9 NOTE). The owner asked for the two post-review fixes of piece 2, `c5e325a` and
`4bff519`, to be examined as items of their own; the review accepts both, with MINOR-5, MINOR-6
and N1. The answers are made in the commit that follows it.

| # | Severity | Finding | Answer |
|---|---|---|---|
| MAJOR-1 | MAJOR | `pii-vault-migrate` can lose the initdb race, now that the API waits for it | Fixed at the cause: both PostgreSQL healthchecks run `pg_isready -h 127.0.0.1`, which fails while initdb's temporary server listens on the socket only. Both one-shots also retry their connection (`FLYWAY_CONNECT_RETRIES=20`) |
| MAJOR-2 | MAJOR | "Proves" is claimed before any run | Reworded: the start test lists what it checks and says a pass is evidence only from a recorded CI run; the image start test's comment says "checks". The first stack-job run is reported with its run ID and the `docker stats` line |
| MINOR-1 | MINOR | Step 7 can pass on old records and does not wait | Fixed: the offsets are read before step 4, and step 7 waits up to 30 s for them to grow |
| MINOR-2 | MINOR | The start test can test a stale image | Fixed: `up --build` |
| MINOR-3 | MINOR | ZAP switches on unannounced, uncached, without a wait bound | ZAP's `up` now has `--build --wait-timeout 900`; the switch is announced in the hand-over, and its first findings are reported before any is fixed (owner, 2026-10-22) |
| MINOR-4 | MINOR | `STACK_INPUTS` misses the Maven wrapper | Fixed: `backend/mvnw` and `backend/.mvn/` added (checked against the regex) |
| MINOR-5 | MINOR | `ApiKeyHasherTest` has no known-answer vector; one assertion cannot fail | Fixed: a vector computed with Python's `hmac`; the assertion that could not fail is gone |
| MINOR-6 | MINOR | `CompositeKeysTest` varies one field per key | Fixed: each key is compared against one variant per field |
| MINOR-7 | MINOR | The notebook entry is imprecise in three places | Fixed: both applications on persistence's classpath named, "passed throughout" marked inferred, and the gitleaks case described as a different mechanism with a shared outcome |
| MINOR-8 | MINOR | `make env` fails without `openssl` | Fixed: it warns and skips the key; only the API needs it |
| N1 | NOTE | `@PreAuthorize("permitAll()")` does nothing; `final` was given up for it | Reverted: the annotation and the CT_CONSTRUCTOR_THROW exclusion are removed and the class is `final` again, like the other ingest controllers. The URL-level contract policy is what admits the anonymous call. Review 1's m5 answer is superseded by this one |
| N2 | NOTE | Fail-fast hid the `final` defect at `42c13cc` | Recorded. The defect was introduced at `42c13cc` and first visible on `c5e325a`'s run. The java job's fail-fast is left as it is in this branch |
| N3 | NOTE | Steps 1 and 3 show less than they seem | Recorded; step 4 is what shows the route and the authenticated path |
| N4 | NOTE | 1 GiB with MaxRAMPercentage=75 | Recorded; the first `docker stats` line is the measurement. Not changed before a run shows a need |
| N5 | NOTE | `fail()` inside `$(...)` | Fixed: `request` prints 000 on a connection failure instead of failing in the subshell |
| N6 | NOTE | Brief's attribution of `DemoSeedConfigurationTest` | Accepted: it came with `b745c83` |
| N7 | NOTE | The notebook's lesson is not applied | Fixed: `tools/tests/test_deployment_guard_everywhere.py` fails when a module with a `@SpringBootApplication` does not depend on `fraudshield-deployment-guard`; the notebook says so |
| N8 | NOTE | Pepper comparison is textual | Fixed: compared case-insensitively |
| N9 | NOTE | Stack job cost | Accepted as intended: it is the only job that starts the API in Compose |

Editorial: one phrase of the review (line 122) was reworded from the PEM header text to "label
`PRIVATE KEY`", because pre-commit's `detect-private-key` hook refuses the header string even in
prose. The finding is unchanged.
