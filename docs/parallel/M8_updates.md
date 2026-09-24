# M8 updates (branch `m8/frontend`)

Written by the M8 agent for the owner and the M5, M6, M7 and M9 agents. M8 owns `frontend/` and
the root `design-tokens/` (the repository layout assigns it to M8). Shared files
(`.github/workflows/ci.yml`, the root `.gitignore`, `requirements.yaml`, `docs/backlog/`,
`lab_notebook.md`, `SESSION_STATE.md`) are not edited on this branch; what M8 needs from them is
proposed below. M8's ADRs are new files numbered from 0080, following M7's 0070 and M9's 0090
blocks.

**Status: not reviewed, not merged.** No requirement moves to `DONE` before Principal Review
(Part I) and merge. The M8 gate is **not** met.

## 1. What exists

| Step (D.3 order) | Commits | Checked by |
|---|---|---|
| Design tokens | 2f6cb5c, da3166d | `tokens.test.ts`: WCAG AA for every colour pair the theme draws, both schemes; generated CSS pinned to the generator; D-36 breakpoints identical in MUI and Tailwind; D-34 motion. ESLint rejects raw hex outside the token sources (D-37). |
| Tooling | ea9f487 | Storybook 10 + a11y addon; vitest in Node (logic) and jsdom (components); axe helper; jsx-a11y strict, react-hooks, storybook lint. |
| Design-system components | d8e269f | 8 components, 45 stories; every story rendered and axe-checked by `stories.test.tsx`; a Testing Library test per component. |
| ADR 0080 (architecture) | 900da90 | Owner-approved libraries and eight requirements, each with its test. |
| Region, i18n, RTL | 66c7df5, a3c871f | Fixes d8e269f's breach of ADR 0023 (a country's currency, locale and zone named in TypeScript) and its float money. Region data comes from the country packs; en/rw/fr/sw catalogues (all non-English `machine_draft`); RTL Emotion cache; lint rejects physical left/right properties. |
| API client | a9a0a2c | Generated from `contracts/openapi` by `@hey-api/openapi-ts`; `apiDrift.test.ts` regenerates and fails on any difference (R2). |
| App shell, routing, PWA | 6f1db49, 914e4b8 | Every E.9 route; drawer / bottom bar (05B A.7); PWA precaches the shell only; Tailwind without Preflight; self-hosted Inter. `productionBundle.build.test.ts` checks R1, R3, R4, R8 and D-39 on two production builds. |
| Degraded-mode banners | 46893d7, f537c0f | ADR 0081's `GET /system/status`, polled every 60 s; contract test pins the payload to the list; the shell shows one banner per mode. |
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

## 4. For the M6 agent: implement GET /system/status

The owner approved a contract change (ADR 0081, commit 46893d7). **M6 owns the server side**,
because the decision engine owns degraded state (C.4).

**Requirement.** `GET /api/v1/system/status`, staff bearer token, any of ANALYST,
SENIOR_ANALYST, RISK_OFFICER, ADMIN. Response 200:

```json
{ "degraded_modes": ["ML_UNAVAILABLE", "DEGRADED_MODE", "KAFKA_SPOOLING", "REALTIME_PAUSED"] }
```

- The body carries **only** that list: no component names, versions, hostnames, metrics or queue
  depths. `additionalProperties: false`, `degraded_modes` required, empty array when healthy.
- The values are the ones the platform already tracks for `GET /admin/health`: the ML circuit
  breaker open (`ML_UNAVAILABLE`, C.4's Resilience4j path), the Redis-down database fallback
  (`DEGRADED_MODE`), the Kafka spool in use (`KAFKA_SPOOLING`) and real-time delivery paused
  (`REALTIME_PAUSED`).
- Every console polls it every 60 seconds, so it must be cheap: read a cached in-memory flag set,
  do not fan out health checks per request.
- 401 and 403 as every staff endpoint; RFC 9457 problem details by default.
- The contract, its authorisation-matrix row and the test
  (`test_degraded_modes_are_the_same_list_for_staff_and_for_admins`) are already on
  `m8/frontend`. `/admin/health` is unchanged.

Until it exists, the console's development mocks serve it (`VITE_FS_DEGRADED` exercises the
banners) and the banner area stays empty against a real backend.

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
