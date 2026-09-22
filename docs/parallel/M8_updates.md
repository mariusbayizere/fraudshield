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
| Playwright, RTL journey | 4ef3911 | `e2e/rtl.spec.ts` (R6) in the new `frontend-e2e` workflow, one job per browser. |

## 2. Evidence (commands actually run, laptop, 2026-09-22)

| Check | Result |
|---|---|
| `pnpm typecheck`, `pnpm lint`, `pnpm format:check` at 4ef3911 | clean |
| `pnpm test:coverage --maxWorkers=1` at 6f1db49 | 349 tests pass; 98% statements, 93.1% branches (thresholds 90 / 85) |
| `pnpm vitest run --project build` at 914e4b8 | 7 pass: R1 on a release build and on a production-mode build with NODE_ENV=test, R3, R4, R8, no generated schemas, D-39 |
| `npx playwright test --project chromium` at 4ef3911 | 3 pass (RTL layout both directions, navigation, axe with contrast) |
| `uv run fs-licences` at 66c7df5 | 940 dependencies, 0 violations (Inter under the new OFL-for-fonts rule, 922cde8) |
| Mutation spot checks | see `docs/reviews/M8/m8-frontend.md` |

## 3. Changes to shared files

1. **Applied, owner-approved:** the `pnpm build-storybook` step in `ci.yml`'s `frontend` job
   (3d5c03e), one step, job name unchanged.
2. **New file, not an edit:** `.github/workflows/frontend-e2e.yml` runs the Playwright journeys on
   Chromium, Firefox and WebKit. Action SHAs are the ones `ci.yml` pins.
3. **Owner-directed:** `tools/src/fraudshield_tools/licences.py` allows OFL-1.1 for
   `@fontsource/*` and `@fontsource-variable/*` npm packages only (922cde8), with tests.

## 4. Proposed, not made (files M8 does not own)

1. **Contract: analysts cannot see degraded modes.** `degraded_modes` exists only on
   `GET /admin/health` (`x-required-roles: [ADMIN]`), but E.9 puts the `ML_UNAVAILABLE`,
   `DEGRADED_MODE` and "Real-time paused" banners above every screen for every role. Proposal: a
   small read-only `GET /system/status` returning `degraded_modes` to any authenticated staff
   role, or the same list on the alert WebSocket. Until then, only admins can be shown the
   banners.
2. **Contract/generator: the password schema.** `@hey-api/openapi-ts` renders `Password`
   (`allOf` plus `x-validation`) as an intersection of `unknown`, which validates nothing.
   Registration will apply `contracts/validation/password-vectors.json` itself; the contract
   owner may prefer a plainer schema for generators.
3. **Country packs: a zone abbreviation.** D-43 asks the UI to show "CAT"; the packs carry
   `utc_offset_hours` but no abbreviation, so the console shows "UTC+2". Proposal: a
   `timezone_abbreviation` parameter with the packs' usual provenance.

## 5. Risks and notes for other agents

- **Bundle margin.** The initial JavaScript is 194.8 KB gzipped against D-39's 200 KB, with only
  the shell. It was 224 KB before `zod` became `zod/mini` and the MUI select a native one. Every
  screen must load lazily; `productionBundle.build.test.ts` fails the build when the budget is
  exceeded.
- **Playwright browser cache.** Installing Playwright 1.63's headless Chromium (revision 1243)
  removed the older `chromium-1223` and `chromium_headless_shell-1223` from the shared
  `~/.cache/ms-playwright`. A session that used them must reinstall with its own Playwright
  version.
- React 19.3, MUI 9.4, Tailwind 4.3 and Vite 8.3 (ADR 0003) are now all installed in `frontend/`.
- `SystemBanners` renders exactly the OpenAPI `degraded_modes` enum, and `catalogues.test.ts`
  pins the language list to the contract's `Locale` enum. A contract change there needs an M8
  change too.
- The risk bands are D-02's (flag 0.60, block 0.85, both inclusive), in
  `frontend/src/design-system/risk.ts`.
