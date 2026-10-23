M9-images | m9/images | /home/marius/fs-wt/m9-images

# Images review 5 (independent)

Commit reviewed: `d522d74` (fix(security): answer reviews 3 and 4; quiet a literal $ref), on top
of `a2262d6` / `a150edc`. Files: `.github/workflows/security.yml`, `docs/backlog/product.md`,
`docs/parallel/M9_updates.md`, `docs/reviews/M9/images-review-3-4-answers.md`,
`docs/security/threat_model.md`, and a binary wheel at the repository root.

Evidence used: `git show d522d74`; `infrastructure/bin/actionlint` with shellcheck 0.10.0 on PATH
(exit 0); the step's script extracted from `security.yml` and run through shellcheck with and
without the directives, and run under `bash -e` + `set -o pipefail` against a small fixture (3 cuts,
and 0 cuts); the log of job 120232689891 (security run 39897867857 on `a150edc`) and its `zap-api`
artifact (`served.yaml`, `contract-scan.yaml`, `zap-api.json`, `zap-api.log`), parsed with
`jq` and Python `yaml`/`json`; the log and step list of job 120229052632 (run 39896801735, on
`146d987`); the run list for `d522d74`. No Docker container, build or compose command was run
locally. No outbound query carried an identifier.

Counts: **1 BLOCKER, 2 MAJOR, 4 MINOR, 5 NOTE.**

## BLOCKER

### B1. A 3.8 MB third-party wheel is committed at the repository root

- **Where:** `shellcheck_py-0.11.0.1-py2.py3-none-manylinux1_x86_64.manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_5_x86_64.whl`
  (new file, 3,800,600 bytes; 16.2 MB unpacked, a shellcheck executable inside).
- **What is wrong:** the commit adds a downloaded tool wheel that no file references (grep for
  `shellcheck_py` finds nothing) and that the commit message does not mention. It is below the
  pre-commit `check-added-large-files` limit (`--maxkb=5120`), so no hook stops it. Merged, it
  stays in `main`'s history permanently; removing it later needs a history rewrite. It also adds
  an unreviewed third-party executable to the tree; the wrapper's METADATA says MIT, the bundled
  upstream shellcheck binary is GPL-3.0 (INFERRED from upstream, not read from the wheel).
- **Failure scenario:** the merge to `main` carries a 3.8 MB binary blob that every clone
  downloads forever, and a supply-chain or licence audit finds an unexplained executable in the
  repository root.
- **Fix:** drop the file from the commit before the merge (`git rm --cached` plus an amended or
  follow-up commit on `m9/images` before it reaches `main`; since `main` does not have it yet, a
  rewrite of `m9/images` alone keeps it out of `main`'s history). Optionally add `*.whl` to
  `.gitignore`.

## MAJOR

### M1. 0b's opening and R-18 say no ZAP run has scanned an API operation; run 39897867857 did, and 0b says so twenty lines later

- **Where:** `docs/parallel/M9_updates.md:115-117`; `docs/security/threat_model.md:198` (R-18).
- **What is wrong (measured):** both say "ZAP's API scan runs, but no run has yet scanned an API
  operation". Job 120232689891 on `a150edc` imported 109 URLs and sent requests to every one of
  the contract's 89 operations (each has at least one instance in `zap-api.json`'s alerts, e.g.
  `POST http://127.0.0.1:8080/api/v1/rules 401`, `POST .../api/v1/auth/login 422/423/429`); 0b's
  own bullet at `:159` calls it "the first to reach the API's operations". The commit therefore
  merges a self-contradiction into 0b and a false current-state line into the threat model.
  The same opening also miscounts section 0's line (`:68-70`): it has four clauses (cluster; ZAP
  baseline never run; no image built, scanned or signed; no deploy workflows), not three, and "the
  API is in the compose stack" is the parenthetical reason for the ZAP clause, not a part of its
  own (review 3 m5 made the same point). "ZAP's part is not [out of date]" is wrong as well:
  section 0 says "the ZAP baseline has never run", and it ran (run 39887407268).
  The answers file marks R3 m5 and R3 m6 as fixed; on these lines they are not.
- **Failure scenario:** a reader of `threat_model.md` on `main` concludes the API has never been
  scanned and re-plans work that is done; a reader of 0b cannot tell which of the two statements
  holds.
- **Fix:** 0b `:115-117`: "On this branch, parts of section 0's 'Never exercised' line no longer
  hold: the API image is built, scanned and start-tested (signing runs on `main` only), and ZAP has
  run against the API, the third run reaching every operation of the contract unauthenticated (see
  'ZAP' below)." R-18: "... ZAP's API scan ran on `a150edc` (run 39897867857), unauthenticated,
  0 High; findings in `docs/parallel/M9_updates.md` 0b ...". Both files need the repository-wide
  checks (governance chain) before the merge.

### M2. "6 of the 82 paths are not on the scanned address" is wrong for `/verify/{token}`, which produced every Medium finding of the scan

- **Where:** `docs/parallel/M9_updates.md:178-179`; repeated in
  `docs/reviews/M9/images-review-3-4-answers.md` row R3 M1 ("the 6 paths off the scanned address").
- **What is wrong (measured):** `/verify/{token}` has the path-level server `/` (`served.yaml`
  `:2374-2376`), which with `-O http://127.0.0.1:8080` resolves to the scanned address. The run
  reached it there: `GET http://127.0.0.1:8080/verify/token?lang=en` (200),
  `POST http://127.0.0.1:8080/verify/token` (500), and two generated-token GETs (200). Both Medium
  alerts, the 500s and most Low alerts are on that path (0b `:164-172` says so). The five actuator
  paths, whose contract server is the management port, were also sent to `127.0.0.1:8080`
  (`GET .../actuator/health/db` etc., answered 401), so ZAP's host override replaced their
  path-level server too; they are "not served there", but by 0b's own rule (`:182`, "an answer of
  401 ... counts as reached") they count as reached. The sentence takes review 3's pre-run
  statement (review 3 M1 item 3, written when the import had crashed and marked INFERRED there)
  and states it as fact after a run that disproves half of it.
- **Failure scenario:** a reader excludes `/verify/{token}` from what the scan covered and
  concludes the verification-page findings are out of scope or spurious.
- **Fix:** "The five actuator paths are served on the unpublished management port; ZAP sent them
  to 8080, where the API answers 401 (run 39897867857). `/verify/{token}` is served at `/` on the
  same address and was scanned." Correct the answers-file row to match.

## MINOR

### m1. The 500 is attributed to "an unknown or malformed token"; the run shows the same token answered 200

- **Where:** `docs/parallel/M9_updates.md:164-166`.
- **What is wrong (measured):** the literal token `token` is answered 200 on
  `GET /verify/token?lang=en` and `GET /verify/token` (Unexpected Content-Type instances), and
  generated tokens are answered 200. The GET that returns 500 is `GET /verify/token?=`, a query
  parameter with an empty name; the POST that returns 500 is `POST /verify/token` (body not in the
  artifact). The parenthetical names a cause the evidence does not support, in the same bullet that
  says "cause not investigated".
- **Failure scenario:** whoever investigates looks at token parsing and misses the empty-name
  query parameter and the POST handler.
- **Fix:** "to `GET /verify/token?=` (an empty-named query parameter; the same token without it
  answers 200) and `POST /verify/token`; cause not investigated."

### m2. "`text/html` where the scan expected the contract's type" misattributes the alert

- **Where:** `docs/parallel/M9_updates.md:172`.
- **What is wrong (measured):** the alert is "Unexpected Content-Type was returned" [100001],
  whose description in `zap-api.json` reads "This is not one of the types expected to be returned
  by an API. Raised by the 'Alert on Unexpected Content Types' script". It compares against the
  script's fixed list of API types, not the contract. The contract declares `text/html` for
  `GET /verify/{token}` 200 (`served.yaml:2394-2396`), so for that operation the response matches
  the contract and the alert is a false positive by the contract's own terms.
- **Failure scenario:** someone "fixes" the verification page's content type, or the contract,
  to satisfy an alert that does not apply to a server-rendered page.
- **Fix:** "`/verify/...` returns `text/html` (as the contract declares), which ZAP's API-scan
  script flags because it is not an API content type."

### m3. "70 of 89 operations are answered 401 by the auth filter": 70 is the contract's count; the run shows 69

- **Where:** `docs/parallel/M9_updates.md:180`.
- **What is wrong (measured):** 70 operations lack `security: []` in the served contract (Python
  parse; matches review 3). In the run, 69 of them have only 401 among their client-error
  instances; `POST /auth/refresh` (not public by the markers) was answered 403. The sentence
  states a run result while giving the contract's count.
- **Failure scenario:** minor; a later comparison against a new run finds a mismatch with no
  explanation.
- **Fix:** "70 of 89 operations require a key or session by the contract's markers; in run
  39897867857, 69 were answered 401 and `POST /auth/refresh` 403."

### m4. The answer to R3 m6 names the wrong sections and calls them dated snapshots

- **Where:** `docs/reviews/M9/images-review-3-4-answers.md:18`.
- **What is wrong:** review 3 m6 cited `M9_updates.md:397` and `:494` "in sections 5 and 7, outside
  section 0's snapshot disclaimer". In the current file those lines are `:425` (section 5, "ZAP not
  yet run (no API)") and `:522` (section 7, "not run: no API service in compose yet"). The answer
  says "sections 0 and 7 ... are dated snapshots". Section 5 is not named; neither section 5's
  heading (`:413`) nor section 7's (`:513`) carries a date. The answer does not match what it
  answers.
- **Failure scenario:** after the merge, `main`'s gate table says ZAP has not run, and the answers
  file says that was considered and is a dated snapshot, which it is not.
- **Fix:** either mark `:425` and `:522` as `main`'s state before this branch (one parenthesis
  each), or change the answer to "sections 5 and 7 are left unchanged; they are not dated and are
  stale on this branch" and leave the decision visible.

## NOTE

- **n1. Both Medium alerts are rule 10055.** `M9_updates.md:167-169` gives the rule id only for
  "directive with no fallback", which reads as if `style-src 'unsafe-inline'` came from another
  rule. In `zap-api.json` both are pluginid 10055 ("CSP: style-src unsafe-inline" and "CSP: Failure
  to Define Directive with No Fallback"), each with 2 instances (`GET /verify/token?lang=en`,
  `POST /verify/token`). The header text quoted matches the evidence exactly.
- **n2. The summary is written before the count check.** In `security.yml:168-173` the summary
  (including "NOT TESTED ...") is appended before `test "$cut" -eq 3` (`:176`). Simulated with
  0 cuts: the summary reads "scan copy: 0 nested ... replaced" plus the NOT TESTED lines, then the
  step exits 1. The step still fails correctly; the summary's wording is just unconditional.
  Acceptable as is; moving the block after the two tests would make it appear only for a valid
  copy.
- **n3. R3 m2's answer could cite the measurement.** The answer ("the job's 60-minute limit bounds
  it") restates what review 3 called the problem. The run measures the risk instead: the scan step
  ran from 04:03:04 to 04:04:40 (about 1.5 minutes) in job 120232689891. Citing that would answer
  the finding.
- **n4. Wording that the artifact does not show.** "five public JSON endpoints" (`:171`): the five
  are public by the markers; that all five answer JSON (one is a 202 from
  `password-reset/request`) is not in the artifact (INFERRED). The 429s are now observed (login,
  availability, password-reset/request), so "(INFERRED, review 3)" at `:182` understates;
  that is not an overclaim. PB-190's "the other images still skipping" (`product.md:1657`): they
  get no job at all (a "NOT BUILT" summary line from `image-matrix`), not a skipped job. "reported
  to the owner before any fix" (`:163`) and the owner's merge order (`:113-114`) have no repository
  source to check.
- **n5. What the commit's own CI shows so far.** On `d522d74`: infrastructure run 39898628560
  success (so the SC2016 failure of run 39897867844 on `a150edc` is gone), supply-chain, stack and
  devcontainer success; security run 39898628553 and ci run 39898628693 were still in progress at
  review time. The new summary lines and `zap/contract-scan.diff` (answer to R4 m4) are therefore
  not yet shown by a run.

## Would `main` be worse than `m9/images` at `a150edc`?

Yes, on two counts: B1 (a binary blob in history, not removable after the merge without a
rewrite) and M1 (the threat model's R-18 states something false about the current state, which
`a150edc`'s R-18 did not, since it was merely stale). The workflow change itself is an
improvement: it fixes the infrastructure job's SC2016 failure and changes no gate.

## Checked and found sound

- **SC2016 directives (`security.yml:160`, `:163`).** Without the two directives, shellcheck
  reports exactly two SC2016, on the `self=` assignment and on the `sed` command; with them,
  none. An SC2016 line appended after the step is still reported, so the directives apply only to
  the next command each (the `sed` directive covers the two-line continued `sed`). The trailing
  `# reason` after the directive is accepted. `actionlint` exits 0.
- **Exit status under `bash -e` + pipefail.** `cut=$(diff ... | grep -c '^> ' || true)` gives 3
  for the fixture (diff exits 1, `|| true` absorbs the pipeline status; grep's count still on
  stdout) and 0 when nothing is cut. `diff ... > zap/contract-scan.diff || true` cannot fail the
  step and does not touch `cut`. The `{ ...; } | tee -a` block exits 0. `{rule_id}` inside double
  quotes is not brace-expanded. With 3 cuts the step exits 0; with 0 cuts it exits 1 at the test.
- **Comments against reviews 3 and 4.** R4 m1: the `-P` comment now matches job 120229052632
  (warning from `[ZAP-DownloadInstaller]` at 17396 ms, daemon on 0.0.0.0:52391); R4 m2: the
  operation list and "/rules/preview's request body and RuleDraftBase.expression" match the copy
  (the two remaining flow references are at `contract-scan.yaml:1328`, under `/rules/preview`'s
  request body, and `:3798`, `RuleDraftBase.expression`); R4 m3: both mechanisms marked INFERRED,
  and "resolving RuleDraft" matches the log ("Error resolving schema #/components/schemas/RuleDraft");
  R4 m4 and n1: summary lines and the cycle-check caveat are in the step. R3 m3: 0b's table
  matches review 3's suggested attribution exactly. R3 m4: 0b now says review 2's answers are the
  author's and not re-reviewed. R3 N5: NCCL as a separate wheel. R3 m1: requests carry `/api/v1`
  (e.g. `http://127.0.0.1:8080/api/v1/environment`, `/api/v1/auth/login`).
- **Third scan bullet's numbers.** Job 120232689891 (`a150edc`, success, every step success):
  "scan copy: 3 nested RuleExpression reference(s) replaced"; "Number of Imported URLs: 109";
  "Total of 282 URLs"; "ZAP reached 282 URLs; the contract has 82 paths"; "ZAP High-risk alerts:
  0". The artifact's diff is exactly the three `all`/`any`/`not` lines inside `RuleExpression`.
- **Third scan bullet's alerts.** All 13 alert types in `zap-api.json` are covered: Medium (2,
  CSP), Low (server error 500 on `GET /verify/token?=` and `POST /verify/token`; COEP, COOP and
  Permissions-Policy on the two verification-page requests; CORP on `environment`, `health`,
  `password-reset/request`, `availability`, `jwks.json`; unexpected content type on four
  `/verify/...` requests), Informational (client error count 277, mostly 401; Authentication
  Request Identified on `POST /api/v1/auth/login`; User Controllable HTML Element Attribute on
  `GET /verify/token?lang=en`, Low confidence; `user_id` in the audit-events query string;
  Non-Storable Content on five responses). Severities match.
- **Second scan bullet.** Job 120229052632 on `146d987`: scan step failure with "Process
  completed with exit code 3", both gates skipped, the nesting-depth message as quoted.
- **Floor description.** "counts every URL ZAP holds after the scan, not what the import
  reached" is consistent with the run (109 imported against 282 total).
- **PB-190 item 2 and R-18's other clauses.** `supply-chain.yml` builds only images whose
  Dockerfile exists and pushes/signs only when `github.ref == 'refs/heads/main'`
  (`:147`), so "images are signed on `main` only" and "`images` builds `fraudshield-api` only"
  hold.
