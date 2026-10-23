M9-images | m9/images | /home/marius/fs-wt/m9-images

# Images review 5, recheck (independent)

Range reviewed: `d522d74..e17cc18` (`git diff d522d74 e17cc18`), with `31eafe6` (review 5 recorded)
and `e17cc18` (the rebuilt fix) on top of `a2262d6`; for binaries, also `origin/main..e17cc18`.

Evidence used: `git diff`, `git show --stat`, `git log -p --stat`, `git rev-list --objects` with
`git cat-file --batch-check`; the log of job 120232689891 and its step list (security run
39897867857 on `a150edc`); its `zap-api` artifact (`served.yaml`, `contract-scan.yaml`,
`zap-api.json`, `zap-api.log`, `zap-api.html`), parsed with Python `yaml`/`json` (`-I`); the
branch's run list. No Docker, build or compose command was run. No outbound query carried an
identifier.

Counts: **0 BLOCKER, 0 MAJOR, 4 MINOR, 3 NOTE.**

## BLOCKER

None. B1 is fixed (see "Checked and found sound").

## MAJOR

None. M1 is fixed. M2 is fixed in 0b; one leftover in the answers file is m-a below.

## MINOR

### m-a. The answers file still says "the 6 paths off the scanned address" (review 5 M2, unfinished)

- **Where:** `docs/reviews/M9/images-review-3-4-answers.md:11` (row R3 M1).
- **What is wrong:** review 5 M2's fix asked for this row to be corrected too. It is unchanged: it
  says the 6 paths off the scanned address "are now stated in `M9_updates.md` 0b". 0b no longer
  says that (it now says the five actuator paths were sent to 8080 and `/verify/{token}` is not
  mentioned as off-address), and the run shows `/verify/{token}` was scanned at its served
  address. The row points to a statement that no longer exists and repeats the error. The new
  row M2 at `:39` ("Fixed in 0b") does not mention the row left behind.
- **Fix:** "What the floor reads, and that the five actuator paths were sent to 8080 rather than
  their management port, are now stated in `M9_updates.md` 0b."

### m-b. "reached all 89 operations" and "runs against every contract operation" count the five actuator operations, which were not reached where they are served

- **Where:** `docs/parallel/M9_updates.md:116`; `docs/security/threat_model.md:198` (R-18);
  `docs/reviews/M9/images-review-3-4-answers.md:38`.
- **What is wrong (measured):** every one of the 89 operations got at least one request in
  `zap-api.json`, but 84 at their served address and the five `/actuator/health*` operations at
  `http://127.0.0.1:8080/actuator/...`, answered 401 there. The contract serves them on the
  management port (`application.yml:49`, 8081; `docker-compose.yml:400`), which was not scanned.
  0b's own Gates bullet (`:180-184`) says so; the opening and R-18 then state the larger claim
  without the qualifier. By 0b's rule ("401 ... counts as reached") the count is internally
  consistent, but a 401 from the API's filter on a port where the operation does not exist does
  not exercise that operation.
- **Fix:** "ZAP's API scan has run and sent requests for all 89 operations, 84 at their served
  address (the five actuator operations went to 8080, not their management port)". R-18: "runs
  against the contract's operations on the API port, unauthenticated".

### m-c. "69 of 89 operations were answered 401" drops the qualifier that makes 69 right

- **Where:** `docs/parallel/M9_updates.md:185-186`.
- **What is wrong (measured):** 69 is the number of the 70 operations without `security: []`
  whose client-error answers were all 401 (the 70th, `POST /auth/refresh`, got 403). Counting all
  operations, 75 got a 401: those 69, the five actuator operations sent to 8080, and the public
  `POST /auth/login` (401, 422, 423, 429). As written, "69 of 89 operations were answered 401"
  reads as a count over all 89 and is not that count. Review 5's suggested text kept the
  qualifier ("70 of 89 operations require a key or session by the contract's markers; ... 69
  were answered 401"); the rebuild dropped it.
- **Fix:** "of the 70 operations that need a key or session by the contract's markers, 69 were
  answered 401 and `POST /auth/refresh` 403."

### m-d. "`?lang=en` gets 200" states a status the artifact does not record

- **Where:** `docs/parallel/M9_updates.md:167`; `docs/reviews/M9/images-review-3-4-answers.md:40`
  ("got 200").
- **What is wrong:** `zap-api.json` and `zap-api.log` carry no status code for
  `GET /verify/token?lang=en`. What they show: it is in neither the client-error (100000, 277
  instances) nor the server-error alert, it returned `text/html` (100001 evidence), carried the
  page's CSP (10055 evidence) and reflected `lang` into the `html` tag (10031). That is a
  non-error answer that rendered the page; 200 is the likely code, INFERRED. (Review 5 m1 also
  wrote "answered 200" from the same artifact; the inference started there.)
- **Fix:** "while the same token with `?lang=en` gets the page (no error answer recorded; status
  200 INFERRED)", and the same in the answers row.

## NOTE

- **n-a. "findings filed" in R-18** (`threat_model.md:198`). No backlog item for the ZAP findings
  exists (`git grep` of `docs/backlog/product.md` for `verify/{token}`, 10055, CORP finds only
  PB-190 item 2). 0b says "reported to the owner before any fix". "Filed" reads as backlog items;
  "findings listed in `docs/parallel/M9_updates.md` 0b" says what is true.
- **n-b. "`d522d74` stays only on the remote branch until it is replaced"**
  (`images-review-3-4-answers.md:33`). True when written; `origin/m9/images` is now `e17cc18` and
  no remote ref contains `d522d74`. Past tense would keep it true: "`d522d74` was replaced on the
  remote branch by this rebuild". The B1 row's account of how the wheel got in (`pip download`
  fallback, `git add -A`) has no repository source; recorded, not checked.
- **n-c. CI on `e17cc18` and repository-wide checks.** At recheck time infrastructure (39900536882)
  and supply-chain (39900536947) were green; security, ci, stack and devcontainer were in
  progress. The edits touch `threat_model.md` and `product.md`, so the governance chain applies
  before the merge; whether it was run locally is not shown by the commit.

## Checked and found sound

- **(a) No binary.** `git ls-tree -r e17cc18` has no `.whl`/archive/executable-like name.
  `git log -p --stat origin/main..e17cc18 | grep -i whl` matches only two lines of review 5's own
  text (`images-review-5.md`), no file. `git diff --numstat origin/main e17cc18` has no binary
  entry; no blob over 500 kB enters in `origin/main..e17cc18`. `31eafe6`'s parent is `a2262d6`,
  `e17cc18`'s is `31eafe6`; `d522d74` is not an ancestor and no remote ref contains it.
  `git diff --quiet d522d74 e17cc18 -- .github/workflows` exits 0: workflows byte-identical.
- **Diff scope.** `d522d74..e17cc18` changes only `product.md` (one line), `M9_updates.md` 0b,
  the answers file, `threat_model.md` R-18, adds `images-review-5.md`, and deletes the wheel.
- **M1.** "No run has yet scanned an API operation" is gone from 0b and R-18 (no match in
  `docs/` outside review 5). Section 0's line (`M9_updates.md:68-70`) has four clauses; both
  quoted clauses ("the ZAP baseline has never run", "no service image is built, scanned or
  signed") match it word for word, and the two stated as still true (nothing applied to a
  cluster; no deploy workflows: `.github/workflows` holds ci, dataset, devcontainer,
  infrastructure, security, stack, supply-chain) hold.
- **M2 in 0b.** The "6 of the 82 paths" sentence is gone; the five actuator paths were sent to
  8080 and answered 401 (`GET http://127.0.0.1:8080/actuator/health/db` 401, etc.); 8081 is the
  management port; `/verify/{token}` is no longer called off-address.
- **m1.** The 500s are on `GET /verify/token?=` and `POST /verify/token` (evidence "500"); the
  POST body is not in the report. Status of `?lang=en`: see m-d.
- **m2.** 100001's description says "not one of the types expected to be returned by an API.
  Raised by the 'Alert on Unexpected Content Types' script"; `served.yaml` declares `text/html`
  for both GET and POST `/verify/{token}` (200 and 410).
- **m4.** The answer now names sections 5 and 7; the stale lines (section 5's table, section 7's
  gate table) are unchanged and the answer says 0b supersedes them, which review 5 offered as an
  acceptable option.
- **n1.** Both Medium alerts are pluginid 10055, 2 instances each, header text as quoted.
- **n3 / "about 1.5 minutes".** Step 6 "ZAP API scan of the contract's operations" ran
  04:03:04 to 04:04:40 (1 min 36 s), in job 120232689891, all steps success.
- **n4.** The five CORP instances are `environment`, `health`, `auth/availability`,
  `auth/jwks.json`, `auth/password-reset/request`, all `security: []`, so "five public endpoints"
  holds. "429 answers were observed": evidence 429 on `POST /auth/login`,
  `POST /auth/password-reset/request` and `GET /auth/availability`; the sentence does not claim
  one for registration, correctly (none observed). PB-190 "get no job" matches review 5's reading
  of `image-matrix`.
- **Other numbers re-measured.** 89 operations on 82 paths; 70 without `security: []`; 109 URLs
  imported, 282 in total, 3 references cut, 0 High-risk; 13 alert types with the risks 0b gives;
  277 client-error instances.
- No other new overclaim found in the changed text.
