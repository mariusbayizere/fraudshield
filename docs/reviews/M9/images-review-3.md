M9-images | m9/images | /home/marius/fs-wt/m9-images

# Images review 3 (independent)

Range reviewed: `ca3653b` (ci(security): ZAP API scan from the contract) and `146d987` (docs:
hand-over section 0b; PB-158, PB-182, PB-190), on top of `abb3bf5`. `abb3bf5` itself is not in
this range and has not been reviewed here.

Evidence used: `git show`; the contract parsed with Python's `yaml`; `gh` read-only calls on this
repository's runs and job logs (including the first `zap-api` run, security run 39896801735, job
120229052632, on `146d987`, which finished during this review); the `zap-baseline` artifact of run
39887407268; and the source of `docker/zap-api-scan.py` at zaproxy tag `v2.17.0` (fetched without
any identifier). The traceback in job 120229052632 places `import_url` at line 428 of the image's
script, against line 431 at the tag, so the image's script and the tag's differ by a few lines;
statements below about the script's behaviour rest on the tag source and are marked where that
matters. No Docker container, build or compose command was run locally.

Counts: **1 BLOCKER, 2 MAJOR, 6 MINOR, 5 NOTE.**

## BLOCKER

### B1. The `zap-api` job cannot pass: ZAP's OpenAPI import crashes on the contract, so nothing is scanned

- **Where:** `.github/workflows/security.yml:142-149` (`ca3653b`).
- **What is wrong (measured):** the first run of the job (run 39896801735, job 120229052632, on
  `146d987`) failed in the scan step after 51 s with exit code 3. ZAP 2.17.0's OpenAPI add-on
  (version 57.0.0, from the job's "Installed add-ons" line) logged
  `ERROR io.swagger.v3.parser.reference.ReferenceVisitor - Error resolving schema #/components/schemas/RuleDraft`
  followed by `JsonMappingException: Document nesting depth (1001) exceeds the maximum allowed
  (1000 ...)` in `OpenAPI31Traverser.traverseOpenApi`, then dropped the API connection; the script's
  `zap.openapi.import_url(...)` raised `RemoteDisconnected`. The coverage gate and the High-risk
  gate were skipped. The schema involved is the recursive `RuleExpression`
  (`contracts/openapi/fraudshield-api.yaml:3760-3777`: `all`/`any` arrays of `RuleExpression`,
  `not: RuleExpression`), reached through `RuleDraft` (`:3785-3805`). That the recursion is the
  cause is INFERRED from the reference chain in the exception (`"not" -> JsonSchema["oneOf"] ->
  ... ["properties"] ...`); it is not reproduced.
- **What is sound about it:** it fails closed. `set -o pipefail` with `| tee` propagated the
  script's exit code 3, so the step and the job are red, not green.
- **Failure scenario:** every push to this branch, and to `main` after merge, shows `security`
  red; no ZAP result of any kind exists; the M9 gate "ZAP 0 critical" has no evidence and cannot get
  any until the import works. If someone "fixes" it by switching back to a crawl or by adding
  `|| true`, the scan returns to reading nothing.
- **Suggested fix:** keep the served contract unchanged and scan from a derived copy built in the
  job: cut the recursion (for example replace the `items`/`not` targets inside `RuleExpression`
  with `{}` or a non-recursive leaf schema), write it to `zap/`, and import it as a file
  (`-t /zap/wrk/<file>`, with the override below). Say in the job and in the hand-over that the
  scanned document is a derived copy and what was cut. Alternatively test whether the add-on
  imports a 3.0 down-conversion. Whichever is chosen, its first run is the evidence; report it as
  the owner asked ("first findings reported before any is fixed").

## MAJOR

### M1. The coverage gate does not measure "every path reached"; it can pass with paths missing and its count is not the import's

- **Where:** `.github/workflows/security.yml:120` (job name "every path reached"), `:150-165`
  (the gate), `:8`; `ca3653b`'s message ("so a failed or partial import cannot pass").
- **What is wrong:**
  1. *What `Total of N URLs` counts (from the v2.17.0 source, lines 510-520):* it is
     `len(zap.core.urls())` printed **after** `zap_active_scan` and `zap_wait_for_passive_scan`,
     not after the import. The import's own count is logged separately as
     `Number of Imported URLs: N` (line 449). So the gated number is whatever the session holds at
     the end, including anything the active scan added; whether active-scan requests add entries to
     `core.urls()` is INFERRED (not checked).
  2. *URLs are not paths.* The contract has 82 paths and 89 operations (Python `yaml` parse; 29
     paths are templated). A count of URLs can exceed the path count for reasons unrelated to
     coverage: the definition URL `/api/docs/openapi.yaml` itself, one entry per method or per
     query-parameter set on the same path (INFERRED from how ZAP's sites tree keys nodes), and any
     node the scan adds. It can also fall short for reasons unrelated to import completeness: two
     operations on one path collapse to one URL when ZAP substitutes the same value for the
     template (`/alerts/{alert_id}` gets one generated value per parameter; INFERRED). With 89
     operations against a floor of 82, up to 7 operations can be missing and the gate still passes
     even before counting the definition URL.
  3. *Six of the 82 paths are not on the target at all.* `/actuator/health` and its four children
     have a path-level server `{scheme}://{instance}:{managementPort}` (default
     `http://localhost:8081`), and `docker-compose.yml:400-401` publishes only 8080 ("the
     management port (8081) stays inside the network"). `/verify/{token}` has the path-level server
     `/` (outside `/api/v1`). Whether ZAP's `hostOverride` replaces path-level servers is INFERRED
     (not observed, since the import crashed). If it does not, five requests go to a closed port; if
     it does, they go to `127.0.0.1:8080/actuator/...`, which the API does not serve there. Either
     way the floor of 82 includes five paths that cannot be "reached" as scanned operations, and
     must be made up by other URLs.
- **Failure scenario:** after B1 is fixed, the import succeeds for, say, 75 paths (for example the
  `RuleDraft` operations are dropped as unparsable, the actuator paths go nowhere); the definition
  URL, method duplicates and active-scan additions push the end count to 90; the gate prints
  "ZAP reached 90 URLs; the contract has 82 paths" and passes, and the job name says "every path
  reached".
- **Suggested fix:** gate on templates, not counts. Use `--hook`: the 2.17 script has no post-import hook
  (its hooks are `cli_opts`, `zap_started`, `zap_tuned`, `importing_openapi` before the import,
  `zap_pre_shutdown` and `pre_exit`), so dump the session's messages (method, URL, status) to
  `zap/messages.tsv` from `zap_pre_shutdown`, and treat as imported only messages that are not
  active-scan attacks (or record the import's set from `zap_tuned`-time state plus a diff; the
  simplest correct route is to be chosen and shown on a run). In the gate, turn each contract path into a regex under its effective server
  (`/api/v1` except the listed path-level ones) and fail when any non-excluded `(method, path)` has
  no imported request. List the management paths and `/verify/{token}` as explicit, named
  exclusions (or point an extra import at them). Report the status-code histogram next to it (see
  M2's 429 point and m2). Rename the job and reword the commit-level claim to what the gate checks.

### M2. The hand-over and the workflow state the import as done; the run shows it imported nothing

- **Where:** `docs/parallel/M9_updates.md:146-155` (`146d987`); `.github/workflows/security.yml:136-141`
  (`ca3653b`).
- **What is wrong:** "`zap-api-scan.py` imports every operation from the contract the API serves"
  is design intent written as fact. "It has not run yet at the time of writing" was true when
  written and is now superseded: run 39896801735 failed (B1). "How completely ZAP imports 3.1 is
  unknown until the coverage gate reads it" is wrong in mechanism: when the import fails, the scan
  step fails and the coverage gate is skipped, so the gate never reads it; and given M1, the gate
  reading it would not answer "how completely" either.
- **Failure scenario:** whoever takes over from section 0b believes the ZAP job is waiting for its
  first green run and that the coverage gate will report import completeness, when the job is red
  at the import and the gate cannot measure completeness.
- **Suggested fix:** replace the paragraph's last three sentences with the measured result (run
  and job ids, the `RuleDraft` nesting error, exit 3, gates skipped), keep "imports every
  operation" only as the job's intent, and describe the gate as what it checks after M1's change.
  Also state the rate-limit consequence below.

**Rate limiting (part of M2's "reached" question).** The unauthenticated scan meets two layers.
(a) Every operation behind `bearerAuth`/API key (70 of 89 operations by the contract's
`x-public`/`security: []` markers; 19 are public: `/health`, `/environment`, five management
`/actuator/health...` paths, ten `/auth/...` operations including `jwks.json`, and the two
`/verify/{token}` operations) is answered by the authentication filter, so active-scan payloads test the filter's 401,
not the handlers. The ingest rate limiter sits behind authentication and is not reached.
(b) The public `/auth/...` operations for sign-in, Google sign-in, registration,
availability and password reset call `LoginThrottle` (callers found by grep; `jwks.json` and the
others not checked one by one)
(`backend/auth/.../account/LoginThrottle.java:47-63`), with defaults of 10 sign-ins and 20 public
requests per IP per 15 minutes (`AuthProperties.java:154-158`); `docker-compose.yml` sets no
override (grep finds none). The import sends one request per operation, within those limits; an
active scan sends many requests per parameter, so after the first 10 or 20 per endpoint the rest
are 429 (INFERRED from the limits; not observed, since no scan has run). URL counts are blind to
status codes, so "reached" in the gate counts a 401, 404 or 429 the same as a handled request. The
honest summary of a passing scan would be: the public health, environment, JWKS and verification
operations exercised; the authentication endpoints exercised up to their rate limits; everything
else reached at the filter only.

## MINOR

### m1. `-O http://127.0.0.1:8080`: accepted by the script, effect on the server path unverified

- **Where:** `.github/workflows/security.yml:149`.
- **What is checked:** the 2.17 usage text reads "`-O` the hostname or URL to override in the
  (remote) OpenAPI spec", and the script sets `target = host_override` when the value has a scheme
  (tag source lines 442-446), so the older `urljoin(target, '//' + host_override)` path, which
  would have produced `http://http://127.0.0.1:8080`, is not taken. `-t <URL> -f openapi`, `-J`,
  `-r` (written under `/zap/wrk`, mounted from `zap/`) and `-I` ("do not return failure on
  warning"; FAIL and errors still return non-zero) match the documented usage.
- **What is not known:** whether add-on 57 keeps the server's path `/api/v1` when `hostOverride` is
  a URL without a path, i.e. whether requests go to `http://127.0.0.1:8080/api/v1/...` or to
  `http://127.0.0.1:8080/...`. INFERRED either way; no run has imported. Without `-O`, a relative
  server URL should resolve against the definition URL to the same host (also INFERRED).
- **Failure scenario:** every operation is sent without `/api/v1`, every answer is 404, and the
  URL count still passes the floor.
- **Suggested fix:** once B1 is fixed, run once with `-d` and record from the log where the
  imported requests went; fail the gate when no imported URL starts with `/api/v1/` (M1's template
  check covers this).

### m2. The scan's duration has no bound of its own inside the 60-minute job

- **Where:** `.github/workflows/security.yml:124`, `:146-149`.
- **What is wrong:** no `-c` is passed, so the script uses its `API-Minimal` policy (tag source
  line 498); nothing bounds the active scan's time. The stack start took 3 min 7 s on the first run
  (03:46:47 to 03:49:54). Whether API-Minimal over ~89 operations, with 429 and 401 answers, fits
  in the remaining ~55 minutes is INFERRED (plausible, not measured).
- **Failure scenario:** the job is killed at 60 minutes; the artifact (uploaded under `always()`)
  holds a partial log without a `Total of` line or a JSON report, and the cause reads as a hang.
- **Suggested fix:** `-z "-config scanner.maxScanDurationInMins=40"` (or equivalent) so the scan
  ends and reports inside the limit; record the first run's scan duration in the hand-over.

### m3. Section 0b's commit attribution per piece is off

- **Where:** `docs/parallel/M9_updates.md:126-127`.
- **What is wrong:** `c5e325a` is "test(persistence): cover what stays after the guards moved
  out" (persistence tests only) and belongs to piece 1's guard move, not piece 2's image.
  `4bff519` and `abb3bf5` both change `EnvironmentController` (piece 1) but piece 1 lists only
  `b745c83`, `42c13cc`. `abb3bf5` also changes `infrastructure/docker/start-tests/fraudshield-api.sh`
  (piece 2) and is not listed there.
- **Failure scenario:** someone auditing piece 2's image from its commits reads persistence tests
  and misses the start-test change.
- **Suggested fix:** piece 1: `b745c83`, `42c13cc`, `c5e325a`, `4bff519`, `abb3bf5`; piece 2:
  `581a752`, `42c13cc`, `abb3bf5`.

### m4. "Both fixed" for review 2 is the author's answer, not a reviewed result

- **Where:** `docs/parallel/M9_updates.md:130-131`.
- **What is wrong:** review 2's MAJOR-1 and MAJOR-2 are answered in `abb3bf5`
  (`images-review-2-answers.md`), which no independent review has examined (this review's range
  starts after it). MAJOR-2's answer is a rewording plus the run, not a fix as such. The stack job
  passing once (job 120198728895) is consistent with MAJOR-1's fix but a race is not shown absent
  by one pass.
- **Suggested fix:** "answered in `abb3bf5` (MAJOR-1 fixed at the healthcheck, MAJOR-2 reworded
  and the run recorded); the answers have not been independently reviewed".

### m5. The section's opening overstates what changed

- **Where:** `docs/parallel/M9_updates.md:113-116`.
- **What is wrong:** section 0 has one "Never exercised" bullet, not three lines; "the API is in
  the compose stack" comes from section 0's "What M10 needs" list. "ZAP has run against it" is
  literally true but, as the section itself says later, the run reached 4 URLs and no operation of
  the API. Section 0 also said "built, scanned or signed"; signing was skipped on the branch
  (job 120198720633, step "Push, sign and attest the SBOM by digest": skipped). 0b does not claim
  signing, which is correct.
- **Suggested fix:** "on this branch, parts of section 0's 'Never exercised' line no longer hold:
  the API image is built, scanned and start-tested (not signed off `main`), and a ZAP job has run
  against the API (it reached no operation; see ZAP below). The API is also in the compose stack."

### m6. Stale statements that ZAP never ran or is skipped

- **Where:** `docs/backlog/product.md:1657` (PB-190 item 2, edited by `146d987`: "`zap-baseline`
  (replaced by `zap-api` ...) and `images` are skipped today"); `docs/security/threat_model.md:198`
  (R-18: "The ZAP baseline and image signing jobs have not executed"); `docs/parallel/M9_updates.md:397`
  ("ZAP not yet run (no API)") and `:494` ("ZAP 0 critical | not run: no API service in compose
  yet"), which sit in sections 5 and 7, outside section 0's "snapshot of `main`" disclaimer;
  `docs/walkthrough/SESSION_STATE.md:89` ("ZAP has never run").
- **What is wrong:** on this branch both ZAP jobs ran (runs 39887407268 and 39896801735) and the
  `images` job ran for `fraudshield-api` (job 120198720633). PB-190 item 2 is true of `main` only,
  and the edit in `146d987` made the sentence look current.
- **Suggested fix:** "on `main`, ... are skipped; on `m9/images` both run" in PB-190, and update R-18
  and the gate table rows (or mark them as `main`'s state) before merge, through the repository-wide
  checks those files need.

## NOTE

- **N1. Old job id and artifact name.** Outside `docs/reviews/`, `zap-baseline` survives only in
  PB-190 item 2 (annotated as replaced) and in the workflow's historical comment
  (`security.yml:135`) and 0b's history (`M9_updates.md:146`); all are correct as history. `main`
  has no branch protection and the repository has no rulesets (`gh api .../branches/main/protection`
  returns 404 "Branch not protected"; `.../rulesets` returns `[]`), so the rename breaks no required
  check.
- **N2. Path count.** `grep -c '^  /'` gives 82, equal to `len(paths)` from a YAML parse; it
  depends on the file's two-space indentation and on no other two-space key starting with `/`. A
  `yq '.paths | length'` or Python count would not depend on formatting. The 82 is correct today.
- **N3. Fail-closed behaviour checked.** The scan step's `pipefail` + `tee` propagated exit 3
  (measured). The coverage gate exits 1 with an annotation when no `Total of` line is found
  (`security.yml:160-164`); it also matches the tag source's exact output format
  (`print('Total of ' + str(num_urls) + ' URLs')`, printed only when `-s` is not given, and `-s` is
  not given). The tee'd file has no timestamp prefix, so the anchored `sed` pattern fits.
- **N4. Active scanning the throwaway stack is acceptable.** ZAP runs with `--network host` against
  a stack built in the job and torn down with `down -v` under `always()`; the public operations it
  can exercise (registration, password reset, sign-in) write to that stack's databases, Mailpit and
  WireMock only. No external host is in the contract's servers except the management/localhost
  templates.
- **N5. Statements not checkable from the repository.** The owner decisions quoted in 0b
  (2026-10-22 list; 2026-10-23 "not now"; "flake candidate, owner 2026-10-23") have no repository
  source to compare with. "A shared object of about 406 MB" matches `libxgboost.so` at 405,981,153
  bytes (`M9_updates.md:750`); "carries ... NCCL" is loose, since NCCL is a declared dependency wheel
  (`Requires-Dist: nvidia-nccl-cu12`), not inside that object (`:753-757`).

## Checked and found sound

- **Run and job ids in 0b and PB-158** (all via `gh`): ci run 39887407153 on `abb3bf5`, java job
  120198691826 success, `IngestApiTest` "Tests run: 13, Failures: 0, Errors: 0"; supply-chain run
  39887407173, job 120198720633 "image fraudshield-api", "fraudshield-api: 404 MB (limit 500 MB)",
  start test, Trivy and SBOM steps success; stack run 39887407143, job 120198728895: "fraudshield-api
  healthy after 125 s (build included)", health 200, `{"synthetic_data":true}`, 401 without a key,
  APPROVE on the rule fallback, `Idempotent-Replayed: true`, decision read back 200,
  "fs.transactions.raw: 0 record(s) before, 1 after", "463.9MiB / 1GiB"; devcontainer run
  39883492949 on `4bff519` is `workflow_dispatch`, success, and the earlier devcontainer runs on the
  branch were failure (581a752, 42c13cc) or skipped (c5e325a, 4bff519 push), so "first executed
  pass" holds; run 39887407378 on `abb3bf5`: attempt 1 job 120198735684 failure, attempt 2 job
  120208474438 success.
- **PB-158 third sighting:** `ConditionTimeoutException ... was not fulfilled within 10 seconds` at
  `ResilienceApiTest.java:376`, "Tests run: 112, Failures: 0, Errors: 1" in the ingestion API
  module (reactor summary: ingestion API FAILURE, all others SUCCESS); `abb3bf5`'s only change under
  `backend/ingest/src/main` is `EnvironmentController` made final and the `@PreAuthorize` removed;
  "three occurrences in four days" (20, 22, 23 October) and "two of them in the devcontainer"
  match PB-158's entries; the devcontainer runs `make ci` with the Docker stack
  (`devcontainer.yml:1-3`), and the correlation is stated as not established. PB-182's "failed three
  times" matches.
- **ZAP baseline result in 0b:** one alert, Non-Storable Content, Informational, on
  `http://127.0.0.1:8080/` (401), evidence `no-store` (artifact JSON); "Total of 4 URLs"; "Job
  spider error accessing URL http://127.0.0.1:8080/ status code returned : 401 expected 200".
- **Reviews:** review 1 counts 2 BLOCKER, 0 MAJOR, 5 MINOR, 8 NOTE; review 2 counts 0 BLOCKER,
  2 MAJOR, 8 MINOR, 9 NOTE and accepts `c5e325a` and `4bff519`; N1 led to the class being final
  again; N2 is the java job's fail-fast, "not changed" matches the answers file.
- **Branch base and piece 4:** merge base with `origin/main` is `4e0a7bc`; `ml/Dockerfile` does not
  exist and `supply-chain.yml:78,91` gives `fraudshield-ml` 1200 MB and the "NOT BUILT" line;
  `fs-scorer serve` takes `--packs` (required), `--bundle`/`--mlflow`, `--feature-store`, `--redis`,
  `--insecure` (`ml/src/fraudshield_ml/serving/cli.py:23-57`); `FRAUDSHIELD_SCORER_TARGET:
  127.0.0.1:1` in compose; PB-172, PB-179, PB-113 exist; WireMock mappings hold only `.gitkeep`.
- **Open items:** stack job name and `timeout-minutes: 45`; `STACK_INPUTS` covers
  `backend/.*/src/main/.*`; `tools/tests/test_deployment_guard_everywhere.py` checks every
  `@SpringBootApplication` module's `pom.xml` for the guard dependency, and the notebook entry of
  2026-10-22 says so.
