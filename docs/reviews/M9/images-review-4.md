M9-images | m9/images | /home/marius/fs-wt/m9-images

# Images review 4 (independent)

Commit reviewed: `a150edc` (ci(security): scan a copy of the contract with the recursion cut), on
top of `156d2ed`. Only `.github/workflows/security.yml` changes (zap-api job, lines 138-178).

Evidence used: `git show a150edc`; the commit's `sed` range run locally on
`contracts/openapi/fraudshield-api.yaml`, and its count logic run under `bash -e` with
`set -o pipefail`; the contract and the copy parsed with Python's `yaml`, a structural diff of the
two, and a strongly-connected-components search over every `$ref` in `components`; the source of
`docker/zap-api-scan.py` and `docker/zap_common.py` at zaproxy tag `v2.17.0` (fetched through the
GitHub API with no identifier); the log of job 120229052632 (run 39896801735, the job's first
run, on `146d987`). No Docker container, build or compose command was run locally. The new job has
not run yet. Nothing below comes from a run of `a150edc`.

Owner decisions applied (2026-10-23): the copy-and-cut approach, `-P 8090` and the coverage floor as
written are taken as given. Nothing below asks to change them. The findings are about whether the
commit does what it says and whether its comments say it accurately.

Counts: **0 BLOCKER, 0 MAJOR, 4 MINOR, 4 NOTE.**

## MINOR

### m1. The `-P 8090` comment gives the wrong cause: ZAP's daemon was not on 8080 in the first run

- **Where:** `.github/workflows/security.yml:170-171`; commit message ("ZAP's proxy listens on
  8090 (-P), off the API's 8080").
- **What is wrong (measured):** in job 120229052632, the line `Failed to start the main proxy:
  Cannot listen on port localhost:8080` is logged by thread `[ZAP-DownloadInstaller]` at 17396 ms,
  while `-addonupdate` reinstalls the `network` add-on (v0.31.0, installed 16669-18423 ms). The
  daemon itself logged `ZAP is now listening on 0.0.0.0:52391` at 23675 ms, and the scan script
  reached it on that port (`HTTPConnectionPool(host='localhost', port=52391)`). Without `-P`, the
  2.17 script picks a free port (`zap-api-scan.py:308-310`, `get_free_port()`) and starts the daemon
  with `-port <that port>` (`zap_common.py:280-284`). So ZAP's proxy was already off 8080. The
  warning came from the add-on reinstall and did not stop the run, which failed later on the
  nesting depth. With `-P 8090` the daemon starts with `-port 8090`, which works. INFERRED, not
  observed: the reinstall goes back to the configured default (`localhost:8080`) and logs the same
  warning again, because `-port` did not prevent it the first time.
- **Failure scenario:** the next run logs `Cannot listen on port localhost:8080` again. A reader
  told by the comment that `-P 8090` fixed this concludes that `-P` does not work, or chases a port
  conflict that does not matter.
- **Fix (comment only, `-P 8090` stays):** "-P 8090: ZAP's daemon listens on 8090, a fixed port
  away from the API's 8080 on the host network (without -P the script picks a free port; the first
  run used 52391). The warning 'Cannot listen on port localhost:8080' in that run came from the
  add-on update reinstalling the network add-on, not from the daemon, and was harmless; it may
  recur."

### m2. The comment's list of what is NOT TESTED mislabels `/rules/preview`, and the second check's comment says "a parameter" where the use is a request body

- **Where:** `.github/workflows/security.yml:148-150` and `:168`.
- **What is wrong:** I listed every operation whose request body or parameters reach
  `RuleExpression`, by `$ref` from the contract. There are exactly three:
  - `POST /rules` (`createRule`): body `RuleDraft` → `RuleDraftBase.expression`
    (`fraudshield-api.yaml:1304`)
  - `POST /rules/preview` (`previewRule`): body **`RuleExpression` itself**, not `RuleDraft`
    (`:1328`)
  - `POST /rules/{rule_id}/versions` (`createRuleVersion`): body `RuleDraft` (`:1360`)

  No parameter reaches it. So the operation list "POST /rules, /rules/preview and the rule
  versions" is exact, but "in RuleDraft request bodies" is wrong for `/rules/preview`. The second
  `test`'s comment, "a parameter and RuleDraftBase.expression", is wrong too: `:1328` is
  `/rules/preview`'s `requestBody` schema. Responses that carry `Rule` (and so `RuleExpression`) are
  `GET /rules`, `POST /rules`, `POST /rules/{rule_id}/versions` and `PUT /rules/{rule_id}/state`.
  These are responses, so no generated request is affected.
- **Failure scenario:** someone checking the cut against the contract looks for a parameter that
  does not exist, or assumes the preview body still has one level of `RuleExpression`.
- **Fix:** line 148-150: "NOT TESTED as a result: nested rule expressions in the request bodies of
  POST /rules and POST /rules/{rule_id}/versions (RuleDraft.expression) and POST /rules/preview
  (a bare RuleExpression)". Line 168: "(the /rules/preview request body and
  RuleDraftBase.expression)".

### m3. The comment states ZAP's body generation and the import's mechanism as fact; neither has been observed

- **Where:** `.github/workflows/security.yml:144-145` ("expands references without a depth
  limit"), `:150-151` ("ZAP generates those bodies with an empty object where a nested expression
  would be").
- **What is wrong:** no run has imported the copy, so how ZAP builds a body for
  `RuleExpression`'s `oneOf` with `{ type: object }` leaves is not known. It could be
  `{"all":[{}]}`, a leaf `{field, op}` branch, or something else, depending on how the add-on picks
  among `oneOf` branches. That is INFERRED either way. What the first run shows is narrower:
  swagger-parser's `OpenAPI31Traverser.deepcopy` hit Jackson's
  `StreamWriteConstraints` nesting limit (1000) while resolving `RuleDraft`. "Without a depth
  limit" is an interpretation of that.
- **Failure scenario:** the commit's own description of the gap is cited later as observed
  behaviour.
- **Fix:** "ZAP's OpenAPI import (swagger-parser) followed the self-reference until Jackson's
  nesting limit (1000) and failed …"; and "ZAP is expected to generate those bodies with `{}`
  where a nested expression would be (INFERRED until a run's messages show it)". After the first
  green run, replace the INFERRED wording with what the run's requests show.

### m4. The job's output records what the copy alters, but not what it leaves untested

- **Where:** `.github/workflows/security.yml:163-164` (the step summary line); owner decision (1),
  "the job must record what the copy alters and what is therefore not tested".
- **What is wrong:** the step summary gets only `scan copy: 3 nested RuleExpression reference(s)
  replaced by { type: object }`. The "NOT TESTED" statement appears only as a YAML comment in the
  workflow source, which a reader of the run (summary or artifact) does not see. The artifact keeps
  `zap/served.yaml` and `zap/contract-scan.yaml` (`path: zap/`, `if: always()`), so the alteration
  can be recovered by diffing them, but the consequence cannot. Whether a source comment satisfies
  "the job must record" is the owner's call. This review reads "record" as the run's output.
- **Failure scenario:** a green run's summary shows "0 High-risk alerts" and the URL count, with
  nothing saying that the three rule operations' bodies were cut.
- **Fix:** append one more line to `$GITHUB_STEP_SUMMARY` in the same step, e.g. `NOT TESTED:
  nested all/any/not rule expressions in the bodies of POST /rules, POST /rules/preview, POST
  /rules/{rule_id}/versions (scan copy; served contract unchanged)`, and optionally
  `diff served.yaml contract-scan.yaml > zap/scan-copy.diff || true` so the artifact carries the
  alteration explicitly.

## NOTE

### n1. The checks count lines in one textual form, so they are not a cycle check. A missed cycle still fails, at the import

- **Where:** `.github/workflows/security.yml:160-169`.
- `sed` and both counts match only the flow form `{ $ref: "#/components/schemas/RuleExpression" }`.
  A future self-reference written in block style (`$ref: ...` on its own line), with single
  quotes, or through another schema (mutual recursion) would leave `cut` at 3 and the `grep -cF`
  count at 2, so the step passes with recursion still in the copy. The comment at `:165-166` says a
  contract change must not be "silently scanned with the recursion back". That holds only in the
  sense that the import then crashes as in run 39896801735, and the scan step fails closed (exit 3
  through `pipefail` and `tee`, as measured in review 3). The failure is loud, but it shows up as
  a ZAP crash, not as this step's check. A `yaml` cycle check (as in "checked" below) would turn it
  into a clear message. This is optional and needs no change now.
- Today the contract has exactly one cycle (see below), so this is about future changes only.

### n2. The cut changes nothing the scan could otherwise exercise: the rule operations need a bearer token, and this branch has no handler for them

- The three operations above inherit the global `security: [bearerAuth]`
  (`fraudshield-api.yaml:42-43`, no per-operation override, `x-required-roles: [RISK_OFFICER]`).
  The scan is unauthenticated, so their requests are answered by the authentication filter (401).
  That follows from the job's own comment at `:141-142`; the 401 is INFERRED, not observed. I
  found no Spring mapping for `/rules` in `backend/` (grep for `Mapping(.*rules`, `previewRule`,
  `createRuleVersion` in main sources: none). With or without the cut, no rule-expression body
  reaches a handler in this scan. The "NOT TESTED" statement is still correct and conservative.
  It would be more informative to add that these operations are reached only at the
  authentication filter.
- "The rule DSL's own tests cover parsing": `backend/rules/src/test/.../json/RuleDraftVectorsTest.java`
  and `.../dsl/RuleCompilerTest.java` exist. I did not check whether they cover deep nesting. A
  grep for a depth limit in `RuleExpressionParser.java` found none. Whether Jackson's default read
  nesting limit bounds a hostile deep tree is INFERRED and outside this commit.

### n3. The import runs the OpenAPI add-on that `-addonupdate` installs, not the one pinned in the image

- In job 120229052632 the daemon started with `openapi` 57.0.0 (installed add-ons line), then
  `-addonupdate` (`zap-api-scan.py:345-350`) installed `openapi v58.0.0` at 12424 ms. The import
  failed at 25042 ms. So the crash most likely came from v58 (INFERRED from the ordering).
  Review 3's attribution to 57.0.0 should read "57.0.0 at start, 58.0.0 installed before the
  import". The digest pin fixes ZAP's core but not this add-on. A later run can import with a
  different version. Recording the add-on version in the step summary would make runs comparable.
  This note does not ask for a change to `-addonupdate`.

### n4. Facts about the coverage floor after this change (the floor itself unchanged, as decided)

- The floor still counts `^  /` in the repository's contract. The copy has the same 82 path keys
  (measured on both files), so the copy does not shift the floor.
- With `-t <file>` ZAP no longer fetches `/api/docs/openapi.yaml` itself, so that URL is no longer
  in ZAP's session through the import. The definition URL no longer adds one to the count it
  used to (INFERRED; the earlier run never reached the count).
- With a file import, the script takes `target = urls[0]` and then replaces it with the override,
  because `-O` has a scheme (`zap-api-scan.py:433-446`). The active scan's target is
  `http://127.0.0.1:8080` as before.

## Checked and found sound

- **The served document is the file.** `ApiDocsController` (`backend/ingest/.../web/ApiDocsController.java`)
  reads `/openapi/fraudshield-api.yaml` from the classpath once and serves the bytes at
  `/api/docs` and `/api/docs/openapi.yaml`. `backend/ingest/pom.xml:125-131` copies
  `contracts/openapi/fraudshield-api.yaml` to `openapi/` in the jar. `backend/Dockerfile:20` copies
  `contracts/openapi/` into the build, and the first run's build log shows `Copying 1 resource from
  ../../contracts/openapi to target/classes/openapi`. `ApiDocsTest.theServedDocumentIsTheFrozenContractByteForByte`
  asserts byte equality. The path is public (`StaffIdentityWiring.java:34`). So running the `sed`
  on the repository file predicts what it does to `zap/served.yaml`.
- **The `sed` range and substitution do exactly what the comment says.** Run on the contract, the
  range starts at `    RuleExpression:` (`:3760`, its only occurrence at that indent) and ends at
  the next 4-space key, `    RuleDraft:` (`:3785`). Indented lines such as `      oneOf:` do not
  match the end pattern. `diff` shows exactly three changed lines, 3767 (`all.items`), 3772
  (`any.items`) and 3777 (`not`), each now `{ type: object }`. The two uses outside the definition
  (`:1328`, `:3798`) are unchanged. `cut` = 3 and `grep -cF` = 2, so the step passes on today's
  contract.
- **The YAML still parses, and the other schemas still mean the same.** The copy loads with
  `yaml.safe_load`. A structural diff against the contract shows exactly six key changes, at
  `RuleExpression/oneOf/0/properties/all/items`, `.../1/properties/any/items` and
  `.../2/properties/not` (`$ref` removed, `type` added), and nothing else anywhere in the document.
  `not` here is a property name under `properties`, not the JSON Schema keyword, so `{ type:
  object }` is a valid replacement. The leaf branch is untouched. Every other schema, path, server,
  parameter, response and example is identical.
- **No other recursion.** A Tarjan SCC search over every `$ref` in all `components` sections
  (155 nodes) finds exactly one cycle in the contract, the self-loop on `RuleExpression`, and none
  in the copy. There are no external or dangling references.
- **The count logic under `bash -e` (the step's shell is `/usr/bin/bash -e {0}`, from the log) with
  `set -o pipefail`.** `diff` exits 1 when the files differ and `grep -c` exits 1 on zero matches.
  `pipefail` makes the pipeline non-zero in both cases, and `|| true` keeps the assignment from
  ending the step. `grep -c` still prints its count, so `cut` is correct: 3 on today's file and
  `0` when the files are identical (measured). If `diff` fails (exit 2, file missing), `cut` is
  `0` (measured), and `test "$cut" -eq 3` fails. In every case I found it fails closed or prints
  the right number. The summary line is written before the `test`, so a failing count is still
  recorded. In `test "$(grep -cF ...)" -eq 2`, a zero count prints `0` and the test fails. A
  `grep` error gives an empty string, `test` errors (exit 2), and the step fails. One limit:
  `diff` and `grep -c` count lines, not occurrences, so two references on one line would count
  once. That cannot happen with today's three lines and is covered by n1's fail-closed path.
- **`-t /zap/wrk/contract-scan.yaml`.** A target without `http(s)://` is treated as a file:
  `target_file = os.path.join('/zap/wrk/', target)`, which for an absolute path is the path itself.
  It must exist or the script exits 3 (`zap-api-scan.py:278-291`). It is then imported with
  `zap.openapi.import_file(target_file, host_override)` (`:433-435`). The file is written by the
  runner into the bind-mounted `zap/` (mode 777 directory; files 644, readable by the container
  user).
- **`-P 8090` with `--network host`.** `-P` sets `port` (`:199-200`). The daemon starts with
  `-port 8090 -host 0.0.0.0` (`zap_common.py:280-284`), and the script's client proxies through
  that port. Nothing in `docker-compose.yml` publishes 8090 (8080 API, 8089 WireMock, others
  elsewhere). That the runner itself has 8090 free is INFERRED. A fixed port gives up the free-port
  check the script does when `-P` is absent. That is acceptable under the owner's decision.
- **`-O http://127.0.0.1:8080` with the relative server `/api/v1`.** The override is passed to
  `import_file` as its target. Whether add-on 57/58 keeps `/api/v1` from the relative server when
  the override has no path, and what it does with the path-level servers of `/actuator/health*`
  and `/verify/{token}`, is INFERRED (unverified; review 3 m1 still applies). With a file import
  there is no definition URL to resolve a relative server against, so the override is now the only
  source of scheme and host. The first green run's request URLs will settle it.
- **The comment's other statements.** "The contract the API serves is unchanged": true, the commit
  touches only the workflow. "Taken from what the API serves": true (`curl -fsS` of
  `/api/docs/openapi.yaml`, failing on HTTP errors). "Crashed on it at its first run (… run
  39896801735: 'Document nesting depth (1001) exceeds the maximum allowed (1000)')": matches the
  log. The commit message's "the step fails unless exactly those three are cut" is accurate for the
  flow form (see n1 for the limit).
