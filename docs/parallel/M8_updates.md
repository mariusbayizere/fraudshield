# M8 updates (branch `m8/frontend`, rebased onto `main` as `m8/frontend-rebase`)

Written by the M8 agent for the owner and the M5, M6, M7 and M9 agents. M8 owns `frontend/` and
the root `design-tokens/` (the repository layout assigns it to M8). Shared files
(`.github/workflows/ci.yml`, the root `.gitignore`, `requirements.yaml`, `docs/backlog/`,
`lab_notebook.md`, `SESSION_STATE.md`) are not edited on this branch; what M8 needs from them is
proposed below. M8's ADRs are new files numbered from 0080, following M7's 0070 and M9's 0090
blocks.

**Status: not reviewed, not merged.** Rebased onto `main` at `a7f0528` on 2026-10-18 (section 7). No requirement moves to `DONE` before Principal Review
(Part I) and merge. The M8 gate is **not** met.

## 1. What exists

The commit ids in this table and in sections 2 to 6 are the pre-rebase ids, on
`m8/frontend-pre-rebase-40f5bdc`; section 7 says what the rebase changed.

| Step (D.3 order) | Commits | Checked by |
|---|---|---|
| Design tokens | 2f6cb5c, da3166d | `tokens.test.ts`: WCAG AA for every colour pair the theme draws, both schemes; generated CSS pinned to the generator; D-36 breakpoints identical in MUI and Tailwind; D-34 motion. ESLint rejects raw hex outside the token sources (D-37). |
| Tooling | ea9f487 | Storybook 10 + a11y addon; vitest in Node (logic) and jsdom (components); axe helper; jsx-a11y strict, react-hooks, storybook lint. |
| Design-system components | d8e269f | 8 components, 45 stories; every story rendered and axe-checked by `stories.test.tsx`; a Testing Library test per component. |
| ADR 0080 (architecture) | 900da90 | Owner-approved libraries and eight requirements, each with its test. |
| Region, i18n, RTL | 66c7df5, a3c871f | Fixes d8e269f's breach of ADR 0023 (a country's currency, locale and zone named in TypeScript) and its float money. Region data comes from the country packs; en/rw/fr/sw catalogues (all non-English `machine_draft`); RTL Emotion cache; lint rejects physical left/right properties. |
| API client | a9a0a2c | Generated from `contracts/openapi` by `@hey-api/openapi-ts`; `apiDrift.test.ts` regenerates and fails on any difference (R2). |
| App shell, routing, PWA | 6f1db49, 914e4b8 | Every E.9 route; drawer / bottom bar (05B A.7); PWA precaches the shell only; Tailwind without Preflight; self-hosted Inter. `productionBundle.build.test.ts` checks R1, R3, R4, R8 and D-39 on two production builds. |
| Degraded-mode banners | 46893d7, f537c0f | **Withdrawn 2026-10-18** (section 4): the contract change is a separate proposal under review, and the shell no longer polls an operation the contract does not have. The `SystemBanner` component and its tests stay. |
| Bundle architecture | cf4cb8f | The shell is a lazy route; initial JS 158.3 KB against a 170 KB budget, no lazy chunk over 120 KB, both enforced on a real build (ADR 0080 §10). |
| Auth: session and sign-in | cb5b02b, d160b3b | Token in memory only, CSRF double-submit, every contract failure answered, guard with a safe "next". |
| Auth: registration | 2d374f2, dd0cb84 | SRS 5.3 fields, D-24 split, FR-07-07 password rule from the shared vectors (all 25 are tests), E.164 phone, availability checks. |
| Auth: recovery | 754b7e5, 13ad126, 5877a4f | Forgot password, reset with a six-digit code, unlock from an emailed link, pending approval. |
| Playwright, RTL journey | 4ef3911 | `e2e/rtl.spec.ts` (R6) in the new `frontend-e2e` workflow, one job per browser. |

## 2. Evidence (commands actually run, laptop, 2026-09-22)

| Check | Result |
|---|---|
| `pnpm typecheck`, `pnpm lint`, `pnpm format:check` at 4ef3911 | clean |
| `pnpm test:coverage --maxWorkers=1` at 6f1db49 | 349 tests pass; 98% statements, 93.1% branches (thresholds 90 / 85) |
| `pnpm vitest run --project build` at 914e4b8 | 7 pass: R1 on a release build and on a production-mode build with NODE_ENV=test, R3, R4, R8, no generated schemas, D-39 |
| `npx playwright test --project chromium` at 4ef3911 | 3 pass (RTL layout both directions, navigation, axe with contrast) |
| `uv run fs-licences` at 2d374f2 | 941 dependencies, 0 violations (Inter under the new OFL-for-fonts rule, 922cde8) |
| `pnpm test:coverage` at 5877a4f | 482 tests pass; 97.5% statements, 91.9% branches |
| CI on 2d374f2 | `ci`, `frontend-e2e` (3 browsers), `stack`, `devcontainer`: success |
| Mutation spot checks | see `docs/reviews/M8/m8-frontend.md` |

## 3. Changes to shared files

1. **Applied, owner-approved:** the `pnpm build-storybook` step in `ci.yml`'s `frontend` job
   (3d5c03e), one step, job name unchanged.
2. **New file, not an edit:** `.github/workflows/frontend-e2e.yml` runs the Playwright journeys on
   Chromium, Firefox and WebKit. Action SHAs are the ones `ci.yml` pins.
3. **Owner-directed:** `tools/src/fraudshield_tools/licences.py` allows OFL-1.1 for
   `@fontsource/*` and `@fontsource-variable/*` npm packages only (922cde8), with tests.

## 3b. SRS v5.0 review — shared files changed on the owner's decision (2026-09-23)

The owner reviewed SRS v5.0 (`docs/srs/v5_delta.md`) and recorded decisions in
`docs/srs/v5_decisions.md`. Carrying them out required editing files M8 does not own. Each change
is generated-source, not hand-edited output:

| File | Change | Why it could not be avoided |
|---|---|---|
| `docs/prompts/FraudShield_Master_Build_Prompt.md` Part B | **D-52, D-53, D-54** added | `docs/srs/defect_register.md` is generated from Part B; a defect cannot exist in the register without its resolution in the prompt |
| `docs/prompts/FraudShield_Master_Build_Prompt.md` D.3 | **M13 — Operational reporting** added after M12 | FR-08 needed a milestone; M9–M12 are referenced by branches and reviews, so they were not renumbered |
| `tools/src/fraudshield_tools/defect_register.py` | count 51 → 54; the count in its messages was hard-coded and now derives from the constant | the tool asserts the expected defect list |
| `tools/src/fraudshield_tools/traceability_seed.py` | SRS v5.0 added as a source for the adopted sections only; `V5_SECTIONS`, FR-08 reader, D-52/53/54 milestones and links | `fs-traceability-seed --check` rebuilds every row from its sources and fails on hand-added rows |
| `tools/src/fraudshield_tools/traceability.py` | `UX-ROLE-*` and `DEV-MATRIX-*` ID families; milestones extended to M13 | the checker validates ids and milestones against fixed lists |
| `tools/tests/test_scope_and_registers.py` | fixture copies the v5 extraction; row and defect counts updated | the tests pin both |

After the change: `fs-traceability-seed --check` and `fs-traceability check` report **305 rows,
890 tagged tests, 0 errors, 0 warnings**; `fs-defect-register --check` reports 54; `pytest
tools/tests` 206 pass. No migration, entity, contract or frontend file was touched.

### For the M7 agent — proposed amendment to ADR 0071

v5 §02/§23.1 require "no raw SQL" and "no database queries in services". The owner kept the hybrid
rule. ADR 0082 (in M8's block) records the four reasons in the project's own words — audit-chain
insert ordering, append-only triggers versus dirty checking, hypertables and security-barrier
views, and set-based refresh-token revocation — plus ADR 0017's PII vault as a fifth. ADR 0071 is
yours; a one-paragraph amendment pointing at ADR 0082 and D-54 would close the loop.

### For the M9 agent — what v5 adds that M9 does not have

1. **Service Dockerfiles with budgets**: six images, multi-stage, non-root, HEALTHCHECK, size caps
   (api < 280 MB, ml < 1.2 GB, frontend < 45 MB, worker < 300 MB). Only `docker-compose.yml` exists
   today; no service Dockerfile is on any branch.
2. **Distributed tracing**: OpenTelemetry agent on the API, SDK on the Python services, context
   propagated through Kafka headers, 10% sampling with 100% on errors and slow spans. The stack row
   already names an OpenTelemetry Collector; v5 names Jaeger as the UI, which is M9's choice to
   make.
3. **Helm is rejected** (`v5_decisions.md` row 8): kustomize stays, because it is built, validated
   by kubeconform and covered by 24 policy tests. The deviation is recorded there.

## 4. GET /system/status: withdrawn from this branch, proposed on its own

**What happened.** On 2026-09-22 this branch added `GET /system/status` to the contract (46893d7,
with ADR 0081, which records it as an owner decision) and the console polled it (f537c0f). The
request to M6 to implement it was never acted on: **`main` has no such endpoint**, and nothing on
`main` exposes the degraded modes to staff (the `DegradedMode` in `backend/decision` is internal
state, and `GET /admin/health` is not implemented either).

**The owner's direction (2026-10-18).** The contract is frozen, so the addition is treated as any
other contract change: a reviewed proposal, not something a rebase brings back. Accordingly:

- the rebase dropped 46893d7, so the contract, its authorisation-matrix row and its test are
  unchanged from `main`;
- the console no longer reads the endpoint: `systemStatus.ts` and its test are removed, the shell
  renders no banners, the generated client matches `main`'s contract again, and the development
  mock is gone (one commit at the top of the branch);
- the proposal is on its own branch, `m8/system-status-proposal`, cut from `main`: the contract
  change, ADR 0081 marked **Proposed**, and its contract test, for an independent review.

**Consequence.** The degraded-mode banners E.9 puts above every screen have nothing to read. No
register row owns them by name, so none is marked; any row or gate criterion that cites the
banners is not DONE until the proposal is accepted, an implementation exists on `main` and the
console reads it.

### Also for M6, during the merge: name the CSRF cookie in the contract

**Fix this before the console consumes the real endpoints, not after** (owner, 2026-09-24). D-27's
double-submit requires the console to read a cookie and echo it in `X-CSRF-Token` on `/auth/refresh`
and `/auth/logout`. The contract names the header (`CsrfToken`) and the refresh cookie
(`fs_refresh`), but **never the CSRF cookie**. Both sides already use `fs_csrf` and each pins it
with its own tests — `CsrfDoubleSubmitFilter.COOKIE` plus four tests on `m7/staff-auth`,
`csrf.ts` plus `csrf.test.ts` on `m8/frontend` — so neither can drift silently on its own, but
nothing ties them to each other. The agreement holds by coincidence.

**Proposed change** (M6 owns the contract during this merge): name the cookie in
`contracts/openapi/fraudshield-api.yaml` — either a documented cookie scheme beside `refreshCookie`
or, at minimum, the `CsrfToken` parameter description stating which cookie the header must equal —
and add a contract test asserting the name, so both implementations assert against the contract
rather than against each other. M8 has not made the change: `contracts/` is shared, and M8's last
contract change was made only on the owner's explicit instruction.

## 5. Proposed, not made (files M8 does not own)

1. **Generator: the password schema.** `@hey-api/openapi-ts` renders `Password` (`allOf` plus
   `x-validation`) as an intersection of `unknown`, which validates nothing. Registration will
   apply `contracts/validation/password-vectors.json` itself; the contract owner may prefer a
   plainer schema for generators. The same generator also drops unknown fields instead of
   rejecting them, so `additionalProperties: false` is enforced by the contract tests, not by the
   generated Zod.
2. **Contract: the CSRF cookie has no name, and both sides pin it separately** (re-checked
   2026-09-24). D-27's double-submit needs the console to read a cookie and echo it in
   `X-CSRF-Token`, which refresh and logout require. The contract names the header (`CsrfToken`)
   and the refresh cookie (`fs_refresh`) but never the CSRF cookie. Both sides do pin the name, by
   their own tests and to the same value:
   - server: `CsrfDoubleSubmitFilter.COOKIE = "fs_csrf"`
     (`m7/staff-auth:backend/auth/.../security/CsrfDoubleSubmitFilter.java:29`), asserted by
     `LoginTest.java:46` (the cookie is set at sign-in) and exercised by `SessionTest.java:52,61,73`
     and `AuthorisationMatrixTest.java:306`;
   - console: `CSRF_COOKIE = 'fs_csrf'` (`frontend/src/auth/csrf.ts`), asserted by
     `frontend/src/auth/csrf.test.ts` and by the session test that checks the header is sent.

   So neither side can drift silently on its own — but nothing ties them **to each other**, and the
   artefact that should is the contract. **Proposal:** name the cookie in
   `contracts/openapi/fraudshield-api.yaml` (a `cookieAuth`-style documented scheme, or at minimum
   the `CsrfToken` parameter description saying which cookie the header must equal), and add a
   contract test asserting that name, so both implementations assert against the contract rather
   than against each other by coincidence. M8 has not made this change: `contracts/` is shared, and
   the last contract change was made only on the owner's explicit instruction.
3. **Country packs: a zone abbreviation.** D-43 asks the UI to show "CAT"; the packs carry
   `utc_offset_hours` but no abbreviation, so the console shows "UTC+2". Proposal: a
   `timezone_abbreviation` parameter with the packs' usual provenance.

## 6. Risks and notes for other agents

- **Bundle budget (ADR 0080 §10).** The initial JavaScript is **158.3 KB** gzipped against a
  self-imposed 170 KB, itself 30 KB under D-39's 200 KB; no single lazy chunk may exceed 120 KB
  (the shell is 39.1 KB). The shell's frame is a lazy route, so the initial bundle is the auth
  shell only. Every screen and every heavy dependency (DataGrid, Recharts, map, graph, Framer
  Motion, other languages' namespaces) loads per route.
  `productionBundle.build.test.ts` fails the build when either budget is exceeded.
- **Playwright browser cache, action needed by other projects.** Installing Playwright 1.63's
  headless Chromium (revision 1243) **removed** the older `chromium-1223` and
  `chromium_headless_shell-1223` from the shared `~/.cache/ms-playwright` on this machine:
  Playwright garbage-collects builds no installed version claims. Any other project or session on
  this laptop that used them must reinstall its browsers (`npx playwright install`) before its
  next run.
- React 19.3, MUI 9.4, Tailwind 4.3 and Vite 8.3 (ADR 0003) are now all installed in `frontend/`.
- `SystemBanners` renders exactly the OpenAPI `degraded_modes` enum, and `catalogues.test.ts`
  pins the language list to the contract's `Locale` enum. A contract change there needs an M8
  change too.
- The risk bands are D-02's (flag 0.60, block 0.85, both inclusive), in
  `frontend/src/design-system/risk.ts`.

## 7. Rebase onto main (2026-10-18)

`m8/frontend` (40f5bdc, 37 commits on `72e7790`) was rebased onto `main` at `a7f0528`
(`m7-complete`, the M4 tail, M7's carries and PB-69). The original is kept as
`m8/frontend-pre-rebase-40f5bdc`.

- **Dropped:** 46893d7, the `/system/status` contract change (section 4).
- **Conflict, 495a2dc (SRS v5.0 decisions):** `traceability.py`'s milestone list now has both
  `main`'s M8B (after M9, before M10) and this branch's M13 (last, as this branch had it). Build
  prompt D.3 lists M13 between M11 and M12; the register sorts it after M12. That order is the
  owner's to settle. The register was taken from `main` and re-seeded: 305 rows, every progress
  field on `main` unchanged, this branch's 47 new rows and 22 defect links reproduced exactly.
- **Alert filters follow the contract** (owner, 2026-10-18: the contract wins). The URL filters
  were a free-text `q` that `listAlerts` has no parameter for, one tier from the risk tiers
  (`high`/`medium`/`low`, where the feed's tiers are `HIGH`/`MEDIUM`/`ANOMALY`) and one channel.
  They are now exactly `listAlerts`' filters with the contract's names and types: tier, channel
  and outcome as lists without repeats, transaction and account, amount range, date range,
  analyst and sort. A test fails if the filters and the generated `listAlerts` query type differ,
  or if the value lists differ from the contract's enums. **No free text is needed:** FR-04-09
  names exactly the filters `listAlerts` takes, so `/search` is those filters, and no contract
  change is proposed.
- **Rows that depend on the analyst API say so in their notes** (owner, 2026-10-18): FR-04-02 to
  FR-04-11, UX-DASH-01 to 09, UX-ROLE-AN-01 to 04, UX-ROLE-SN-01 to 04 and TEST-06, each naming the
  contract operations it needs. Two have **no contract operation at all**: UX-ROLE-SN-02 (alert
  reassignment) and UX-ROLE-SN-04 (team performance); each needs a reviewed contract change for
  M8B first.

## 8. Numbering reserved by this branch

Claimed 2026-10-18, after reading every branch: the highest backlog id in use anywhere is PB-130
(`main`, M7's block). ADR and Flyway numbers are global.

| Kind | Reserved for M8 | Used |
|---|---|---|
| ADR | `0080`–`0089` | `0080` (console architecture), `0081` (the `/system/status` proposal, on `m8/system-status-proposal`), `0082` (SRS v5.0 adoption), `0083` (the feed is a card list), `0084` (the SHAP explanation is a table) |
| Backlog | `PB-131`–`PB-140` | `PB-131` (only `validate.ts` imports the generated Zod), `PB-132` (D-45's plain-language templates) |

## 9. The analyst screens (from 2026-10-18)

Built against mocks generated from the contract: `frontend/scripts/generate-mock-examples.ts`
copies `contracts/openapi/schema-examples.yaml`, which the contract tests validate, into
`frontend/src/mocks/generated/examples.json` (`examplesDrift.test.ts` pins it). Test fixtures start
from the same examples, and every response a screen reads is parsed by the generated Zod in tests.
None of this is evidence that the API works (section 7; `M8B_updates.md`).
The generator leaves out `ApiKeyCreated`, whose placeholders have the shape of real credentials:
`.gitleaks.toml` allows those exact values in the contract's file only. The first version copied
them and CI's history scan failed; the branch's history was rewritten before merge (2026-10-18) so
no copy is in it, and `examplesDrift.test.ts` fails if one returns.

**Order.** Each screen reuses the one before it.

| # | Screen | Route | Contract operations (M8B) | State |
|---|---|---|---|---|
| 1 | Alert feed | `/alerts` | listAlerts, getDashboardStats | built, under review |
| 2 | Alert detail and SHAP | `/alerts/$alertId` | getAlert, getAlertExplanation, getScoringResult | built, under review |
| 3 | Decision panel | in the detail | decideAlert, undoAlertDecision, escalateAlert | built, under review |
| 4 | Account context | in the detail | listAccountTransactions, getBehaviourProfile, getAccountNetwork | not started |
| 5 | Anomalies | `/anomalies` | listAlerts (queue=anomaly) | not started |
| 6 | Escalations | `/escalations` | listAlerts (queue=escalated) | not started |
| 7 | Search | `/search` | listAlerts (identifiers, amounts, dates) | not started |
| 8 | My performance | `/me/performance` | getMyPerformance | not started |

**Owner decisions on screen 1 (2026-10-18).**

- `/system/status` belongs to M8B, with three design questions open (`M8B_updates.md`).
- **No real-time channel is invented.** FR-04-02 and UX-DASH-01 are BLOCKED on a contract addition
  M8B must serve; the screen polls every 60 s and the rows say so.
- **Cards, not the DataGrid** (ADR 0083). UX-DASH-02 records the deviation. The owner directed
  DONE_WITH_DEVIATION, then ruled (2026-10-19) that the mock rule wins: **a deviation never
  promotes a row past it**, so UX-DASH-02 stays IN_PROGRESS.
- **UX-ROLE-SN-02 and UX-ROLE-SN-04** are unowned work for M8B; no screen is built for them.
- **The bundle limit** is not moved; what it protects is reported to the owner first.

**Screen 1, the alert feed.** Today's figures (the HIGH and MEDIUM counts filter the feed), the
tier, channel and status filters and the order, all in the URL as listAlerts parameters, and the
standard queue as cards with FR-04-03's eight elements, a page at a time.

- **No real-time channel in the contract.** FR-04-02's WebSocket is not defined anywhere in
  `contracts/`, so the feed and its figures re-read every 60 s, E.9's degraded cadence. FR-04-02
  and UX-DASH-01 cannot be DONE until the contract has the channel and M8B serves it.
- **Cards, not the DataGrid.** UX-DASH-02 names the free MUI X DataGrid, which is not a
  dependency (ADR 0080 names it as an expected heavy route dependency; adding it is a licence and
  version decision, ADR 0009 and ADR 0003). The feed is a card list on every width until then.
- **The initial-bundle budget is nearly spent.** Before this screen the entry was 172,195 bytes
  gzipped against 174,080 (170 KB). The new lazy chunk made the bundler move react-i18next's
  `useTranslation` into the entry, through `RegionContext.tsx`, and pushed it to 174,996.
  `useFormatLocale` moved to `region/formatLocale.ts`, so the entry no longer needs it: 172,999,
  with 1,081 bytes left. The feed's own chunk is 19.4 KB. Later screens can push it over again;
  raising the budget is the owner's (ADR 0080 §10).
- **Deletion checks** (code broken, then the screen's tests run): the 60 s refresh, the queue,
  the anomaly frame, the several-statuses option, the correlation ID and the countdown each fail a
  test when removed. Dropping an emptied list in `toggled` fails no page test, because the
  route's validation drops it too; `FeedFilters.test.ts` tests it as a unit.
- **Independent review (2026-10-18): CHANGES_REQUIRED, 2 MAJOR, 8 MINOR, 6 NIT; fixed in the
  next commit.** MAJOR: the filter chips were 32 px with pressed shown by colour alone (now 48 px,
  outlined or filled with a check mark, and the selects full height); the FR-04-03 row said
  "tested" while deleting the SHAP arrows or hard-coding the currency failed no test (now asserted,
  with a fixture whose currency is neither the region's nor the example's). MINOR, all fixed: an
  empty 2xx body crashed the feed and the figures; a refresh could show an alert twice; a decided
  MEDIUM alert kept counting down; focus fell to the page after "Clear filters", and the count was
  not announced reliably (one live region now, previous results kept while a filter loads);
  filters a link carried with no control here were invisible (now removable chips); two register
  notes overstated; `aria-pressed` was untested; every catalogue loaded at start (now `common` and
  `designSystem` only, the rest on the route that asks). Not changed: one timer per countdown chip,
  and filter changes replace the history entry rather than adding one.
- **Re-review of that round: APPROVED_WITH_MINORS**, both MAJORs and every listed item fixed and
  each caught by a test when deleted. Its own minors were fixed after it: the empty-body guards are
  now tested with the schema check off, as in production (`queries.test.ts`); the previous
  filter's results are no longer kept while a new filter loads (they could show the wrong empty
  state); an error is announced once; a link's dates and amounts are written in the locale and the
  region's zone, and each such chip removes its filter when pressed, with a name that says so; the
  filters keep a visible focus ring. A delta review then found two of those fixes untested, and a
  removed chip dropping focus: now tested (stale results, chip focus, `formatDateTime` in two
  languages and a fractional offset). The focus ring is set in code and not measurable in jsdom,
  so no test claims it. Open, recorded: the on-demand catalogues suspend under the
  app's one Suspense boundary (`App.tsx`, no fallback), the same mechanism every lazy route
  already uses; the component tests load all catalogues eagerly, so only the Playwright journeys
  exercise it.
- **What `[FR-04-12]` covers here:** axe in jsdom, on a wide and a phone width, finds no
  violations. It does not measure contrast (`tokens.test.ts` checks every colour pair the theme
  draws, D-33) or target size (no layout in jsdom; the 48 px is set in code, not measured).

**Owner decisions on screen 2 (2026-10-19).**

- **No Recharts** (ADR 0084). The ADR lists what the table does not give that the named chart
  would, so M11 and M12 call it sufficient, not equivalent.
- **No plain-language templates written by the engineer.** PB-132 stays open for a domain owner;
  the rows say so, and M11 is told the console has a feature table, not explanations analysts
  have been shown to act on (`M11_updates.md`, "From M8").
- **Bundle:** 200 KB stays the SRS gate; the margin moves to 185 KB only after the entry's
  unexplained growth (reported as 158.3 KB to 172.2 KB; measured, 158.0 to 167.9 KB) is explained, with ADR 0080 amended on the measured
  basis; and the 3G timings (MOB-PERF-01, 03) are measured in CI.

**Screen 2, the investigation drawer** (`/alerts/$alertId`, a child of the feed's route, so the feed
stays on screen with its filters). On large screens a 560 px drawer that is not modal; below the
lg breakpoint a full-screen dialog. Two tabs: the summary (the transaction, the model's scores from
getScoringResult, the decision history) and the SHAP explanation (getAlertExplanation, read only
when its tab is opened).

- **The generated schemas for four closed compositions were unusable.** The contract closes
  `AlertDetail`, `Rule`, `IpAllowlistEntry` and `ApiKeyCreated` by listing every member's
  property names beside `additionalProperties: false`. The generator turned that into an index
  signature of `never`: the TypeScript type made every property `never`, so code typed with it
  compiled unchecked, and the Zod schema rejected the contract's own example, so the development
  and test check would have failed on every real alert. `scripts/generate-api.ts` now removes the
  two unsatisfiable fragments and fails if a generator upgrade stops producing them, and
  `src/api/contractExamples.test.ts` parses all 68 examples in `schema-examples.yaml` (66 schemas,
  `ApiKeyCreated` included) with their generated schemas; restoring the fragment fails it. The 13
  inline examples in `fraudshield-api.yaml` are not covered.
- **SHAP is a table, not Recharts.** FR-04-05 and UX-DASH-05 name Recharts' HorizontalBarChart.
  The explanation is an accessible table with bars to scale, ranked by effect, with D-45's top 10
  (5 on a phone) and one netted row for the rest. No dependency is added; like the DataGrid, it is
  a deviation for the owner to decide.
- **No plain-language descriptions yet.** D-45 requires deterministic, localised templates per
  feature, keyed `feature.<name>.increases_risk|decreases_risk` by the scorer: 88 keys in four
  languages, and wording analysts act on. They do not exist; the drawer shows the feature's name
  and raw value instead. Filed as PB-132.
- **Bundle:** the entry is 173,324 bytes of the then 174,080 (170 KB) budget, 756 left; the drawer is its own 11.5 KB
  chunk.
- **Deletion checks:** reading the explanation before its tab opens, dropping the sort, the top-10
  cut, the 404 no-retry rule (tested with a client that retries), the focus move, Escape, the
  non-modal drawer, the fallback warning and the direction arrow each fail a test when removed.
- **Independent review: CHANGES_REQUIRED, 2 MAJOR, 7 MINOR, 4 NIT; fixed in the next commit.**
  MAJOR: the docked drawer covered the right 560 px of the feed, so "the feed stays usable" was
  not true (the feed now keeps that width clear while the drawer is docked); closing dropped
  keyboard focus to the page (it now returns to the card that opened the drawer, or to the main
  region). MINOR, all fixed: the explanation's error path, switching alerts with the SHAP tab open,
  the decision-state chip, the anomaly tier, a system decision without a comment and Escape on the
  phone dialog are now tested; the examples test reads the contract itself, asserts an exact count
  and covers `ApiKeyCreated` (the "68" was right for the contract, not for the mocks' copy); the
  bars use the theme's SHAP colours; `aria-controls` is set only on the selected tab; the phone
  view starts below 600 px, not below the drawer's breakpoint; three register notes corrected.
  NIT, fixed: closing replaces the history entry, so Back does not reopen the drawer; the hidden
  bar column's header is hidden too; the generator fails if its fix stops matching. Not changed:
  the mocks answer any alert id with the example.
- **Deletion checks of that round:** the feed's reserved width, the focus return and its fallback,
  the per-alert tab reset, the state chip, the anomaly chip and the selected-only `aria-controls`
  each fail a test when removed.
- **Re-review of that round: APPROVED_WITH_MINORS**, both MAJORs fixed and each caught by a test.
  Its minors were fixed after it: focus now returns however the drawer goes (close, Escape or the
  browser's Back), matched by path so a card link carrying filters still finds its card (tested;
  an id the router percent-encodes should match too, but the fixtures are UUIDs, so it is not); Back after closing is tested not to reopen the drawer; the SHAP tab is
  under axe too. Each fails a test when removed. Not tested: the theme's SHAP colours on the bars
  (their contrast is, in `contrastPairs.ts`).
- **Last check of that round: APPROVED_WITH_MINORS**, fixed after it: a malformed escape in any
  link can no longer throw from the lookup, and returning focus does not scroll the feed. Neither
  has a test of its own.

**The 3G timings, first measured (2026-10-19).** A first visit to the sign-in page, production
build, Chromium with the Moto G4 profile, 1 Mbps and 200 ms, CPU slowed four times, median of
three: **first contentful paint 3.6 s against MOB-PERF-01's 2.5 s (fails)**; largest contentful
paint 3.6 s (target 4.0 s), time to interactive 3.6 s (5.0 s), total blocking time 0 ms (300 ms),
layout shift 0.0003 (0.1) pass. There are no long tasks: the page waits on the network, not the
CPU, so the bundle passes its size gate while first paint misses its target. The job logs the
request waterfall to find what first paint waits for; the next run's waterfall is the evidence
for any fix. The `performance` job in `frontend-e2e` is red until then.

**Screen 3, the decision panel** (in the drawer, under the tabs). A comment of 10 to 2000 code
points enables Confirm fraud and Mark legitimate (decideAlert); success closes the drawer, takes
the alert out of every loaded feed page at once, and offers Undo (undoAlertDecision) from the
server's `undo_until`, never longer than D-44's 5 s grace. An analyst can escalate to a senior
analyst or a risk officer, a senior analyst to a risk officer (escalateAlert); a risk officer is
not offered escalation. Every action carries the alert's version and an Idempotency-Key: a refused
action goes again under a new key, an unanswered one under the same (D-29). Stale-alert,
passed-deadline and escalation-level refusals are said in words. The notices live in the feed, so
they outlive the drawer a decision closes.

- **Not built:** the offline queue and replay (D-29), notifying the target role of an escalation
  (no real-time channel in the contract), the risk officer's override (FR-05-01, a risk officer's
  screen).
- **Deletion checks:** the 10-code-point threshold (at 9), code-point counting, the key rotation
  on refusal and its reuse on no answer, the feed drop (with a refetch that never answers), the
  grace clamp, the deadline message, the role filter, the decided-alert panel, the version sent
  and closing on success each fail a test when removed. A guard that a risk officer cannot
  escalate was redundant with the role filter and is gone.
- **Independent review: CHANGES_REQUIRED, 2 MAJOR, 7 MINOR, 4 NIT; fixed in the next commit.**
  MAJOR: Confirm fraud and Mark legitimate shared one Idempotency-Key, so after a Confirm with no
  answer, Mark legitimate would have been replayed by the server as the fraud decision while the
  console said "marked legitimate" (each action's key now follows its content); Undo was out of a
  keyboard user's reach within 5 s (Ctrl+Z or ⌘Z now undoes, and the notice says so). MINOR, all
  fixed: the Undo window ran from the response and could be stretched by hovering (it now runs from
  the send, on its own timer, and a clock ahead of the server's still shows the notice); the
  immediate feed re-read could put a decided alert straight back (the feed is no longer re-read on
  success; whether listAlerts lists a pending decision is put to M8B); escalation's 422, 404 and
  401 were worded as comment errors or "try again"; an older refusal could mask a newer one; an
  Undo by someone else or with no answer was "could not be undone" (now named, and the no-answer
  case keeps Undo); untested branches now tested; the counter is pluralised. NIT: the unused
  `ApiError.current` is gone, the undo mock is marked, the reason field is full width.
- **Deletion checks of that round:** one key for all actions, the shortcut, the server's deadline,
  re-reading the feed on success, the escalation and 404 wording, keeping an old refusal while
  sending, keeping Undo after no answer, the not-the-author message and forgetting a refused
  escalation's key each fail a test when removed.
