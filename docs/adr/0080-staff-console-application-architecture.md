# 0080 — Staff console application architecture: routing, API client, mocks, PWA, styling, fonts, i18n, direction and region

- **Status:** Accepted (library choices and requirements 1–3 set by the owner, 2026-09-22)
- **Date:** 2026-09-22
- **Requirements affected:** FR-04-09 (filter state in the URL), FR-04-12 (accessibility), E.9
  (routes, screen states, PWA, performance budgets), H.4 (API types from OpenAPI, Zod at the
  boundary)
- **Defects referenced:** D-29, D-37, D-38, D-39, D-43; ADR 0003 (versions), ADR 0009 (licences),
  ADR 0023 (Africa-wide portability, right-to-left)

## Context

M8's app shell needs a router, an API client, mocks for services that are not merged yet
(M5–M7 are in progress on other branches), an offline app shell, Tailwind beside MUI, a font,
localisation and text direction. C.1 names TanStack Query, react-i18next and Workbox. It does not
name the router, the API generator, the mock layer, the Vite PWA plugin or the font delivery.

Measured facts that constrained the choice (2026-09-22, npm registry):

- `openapi-typescript` 7.13 declares `typescript: ^5.x`. ADR 0003 pins TypeScript 6.0.3.
  `@hey-api/openapi-ts` 0.99.0 declares `>=5.5.3 || >=6.0.0` and generates types, a fetch
  client and Zod schemas from one spec.
- `@tanstack/zod-adapter` 1.167 declares `zod: ^3.23.8`. TanStack Router accepts any Standard
  Schema validator directly, and Zod 4 implements Standard Schema, so the adapter is not needed.
- `@fontsource-variable/inter` 5.3.0 is OFL-1.1. Its Latin and Latin Extended variable-weight
  files are 48 KB and 85 KB; Latin Extended covers Kinyarwanda and French diacritics.

While writing this ADR, a violation of ADR 0023 was found in M8's own design-system commit
(d8e269f). `Money` defaulted to one country's currency and locale, and `StaleNotice` hard-coded
one country's IANA zone and abbreviation. ADR 0023 forbids naming a country, currency or zone in
TypeScript source. `Money` also took a JSON number, which the contract forbids ("Money is a
decimal string plus an ISO 4217 currency; never a JSON number"). Decision 9 corrects both.

## Options considered

1. **React Router 8** vs **TanStack Router 1.170**. Both are MIT and support React 19. TanStack
   Router types its routes and validates search parameters with a schema at the route, which is
   what FR-04-09 needs (filter state in the URL, validated on entry). It also shares its data
   model with TanStack Query, which C.1 already requires.
2. **openapi-typescript + openapi-fetch + hand-written Zod** vs **@hey-api/openapi-ts**. The
   first does not support TypeScript 6. Hand-written Zod would drift from the contract.
3. **MSW** vs a mock server process. MSW intercepts in the browser and in Node, so the same
   handlers serve development, Vitest and Playwright.
4. **vite-plugin-pwa (Workbox generateSW)** vs a hand-written service worker. The plugin is the
   Workbox integration C.1 names.
5. **Fonts from Google Fonts** vs **self-hosted**. Google Fonts leaks staff IP addresses to a
   third party, needs a CSP exception, fails offline and costs a round trip on 3G: the same
   reasons D-38 gives for flags.

## Decision

1. **Routing:** TanStack Router with code-based routes and lazy route components (D-39). Search
   parameters are validated by Zod 4 schemas passed directly to `validateSearch`.
2. **Server state:** TanStack Query only (H.4). No global client store.
3. **API client:** `@hey-api/openapi-ts`, pinned exactly, generates types, a fetch client and Zod
   schemas from `contracts/openapi/fraudshield-api.yaml` into `frontend/src/api/generated/`. The
   output is committed. Responses are validated with the generated Zod schemas in development
   and tests (H.4). Production skips response validation to keep the schemas out of the initial
   bundle (D-39).
4. **Mocks:** MSW 2 handlers under `frontend/src/mocks/`, built from the contract's types. They
   start only in development, and only by a dynamic import behind `import.meta.env.DEV`, so a
   production build cannot contain them.
5. **PWA:** `vite-plugin-pwa` in `generateSW` mode precaches the app shell only. The service
   worker never caches API responses. Offline data is D-29's job (masked, encrypted, 12-hour
   expiry, through TanStack Query persistence), not the service worker's.
6. **Styling:** Tailwind 4.3 through `@tailwindcss/vite`, importing its theme and utilities
   layers but not Preflight (D-37). MUI's `CssBaseline` owns resets, and the generated
   `tailwind-theme.css` supplies the token values.
7. **Fonts:** Inter is self-hosted from `@fontsource-variable/inter`, limited to its Latin and
   Latin Extended subsets through our own `@font-face` rules, with `font-display: swap` and the
   system stack as fallback. No request goes to a font CDN. `fs-licences` allows OFL-1.1 for
   `@fontsource/*` and `@fontsource-variable/*` npm packages only (922cde8).
8. **Localisation and direction (D-43, ADR 0023):**
   - react-i18next is built into the shell from the first screen. It has English (default),
     Kinyarwanda, French and Kiswahili, the contract's `Locale` enum, with lazily loaded
     namespaces.
   - Every non-English string carries a status, `machine_draft` or `reviewed`, in a sidecar
     catalogue. A translation-status report lists them.
   - Text direction comes from the active language through `i18next.dir()`. It sets
     `<html dir>`, MUI's `direction`, and an RTL Emotion cache (`stylis-plugin-rtl`) for MUI's
     own styles.
   - M8 code uses CSS logical properties only. A lint rule rejects physical left/right
     properties in `sx` and styles.
   - None of the four languages is right-to-left, so the direction can be forced for testing
     with `?dir=rtl`. That changes layout only, and a Playwright test exercises the shell in
     RTL.
9. **Region data from the country packs (ADR 0023):**
   - Currency minor units, the UTC offset and the country code come from
     `dataset/generator/params/countries/*.yaml`, through a generated JSON file (as the design
     tokens are generated). No country, currency or zone is named in TypeScript source.
   - Money is formatted from the contract's decimal string, never a float.
   - Clock times show the zone as its offset, e.g. "14:02 UTC+2". The packs carry an offset but
     no zone abbreviation, and adding one is a dataset change proposed in
     `docs/parallel/M8_updates.md`.
   - A synthetic, entirely assumed "Country Z" pack renders through the same components in a
     test, with no code change: ADR 0023's behavioural acceptance test, applied to the UI.

## Requirements, each with its test

| # | Requirement | Test |
|---|---|---|
| R1 | MSW and mock handlers never reach production | `productionBundle.test.ts` runs a production `vite build` and fails if any emitted file contains `msw`, `mockServiceWorker` or the handlers' marker string, or if `mockServiceWorker.js` is emitted |
| R2 | Generated API code never drifts from the contract | `apiDrift.test.ts` regenerates the client from `contracts/openapi/` into a temporary directory and fails on any byte difference from `src/api/generated/`. It runs in CI inside `pnpm test:coverage`. |
| R3 | Fonts are self-hosted, Latin and Latin Extended only | `productionBundle.test.ts`: the built CSS and JS reference no font CDN host, and the only font files emitted are Inter's Latin and Latin Extended subsets |
| R4 | No Preflight (D-37) | `productionBundle.test.ts`: the built CSS lacks Preflight's global reset rule |
| R5 | Every string exists in all four languages, with a status | `catalogues.test.ts`: the same keys in `en`, `rw`, `fr` and `sw`; every non-English key has a status |
| R6 | Right-to-left works | Playwright `rtl.spec.ts`: with `?dir=rtl`, `<html dir="rtl">`, and the navigation and page layout mirror; axe passes. The lint rule for physical properties has its own test. |
| R7 | No country named in code; Country Z works | `region.test.tsx`: Country Z's currency, minor units and offset render through `Money` and `StaleNotice`; the generated region data is pinned to the packs |
| R8 | The service worker never caches the API | `productionBundle.test.ts`: the generated `sw.js` has no runtime route for `/api/` |

## Consequences

- `@hey-api/openapi-ts` is pre-1.0, and a minor release may change its output. The exact pin and
  R2 make any change visible as a diff, reviewed with the upgrade.
- R1, R3, R4 and R8 need a production build inside the test suite, which adds tens of seconds
  to `pnpm test`. It is one build shared by all four checks.
- Translations other than English start as `machine_draft` and are listed as such until a native
  speaker reviews them. None is presented as reviewed.
- The zone abbreviation D-43 asks for ("CAT") waits for a pack field. Until then the UI shows the
  offset, which is exact but less familiar.

## Amendment 2026-09-22 — §10, the bundle budget is an architecture, not a hope

The shell alone measured **194.8 KB gzipped** of initial JavaScript against D-39's 200 KB, before
a single screen existed. A budget with 5 KB left is a budget already spent, so the owner directed
that the architecture carry it.

**Decisions.**

1. **The initial bundle is the auth shell only**: providers (theme, region, catalogues, query
   client), the router, the start-up error screen, and the route that is being visited. The
   console's frame — app bar, navigation drawer, bottom bar, banners — is itself a lazily loaded
   route component, not part of the entry chunk.
2. **Every screen and every heavy dependency loads per route**: the free DataGrid, Recharts, the
   map (D-46), the graph library, Framer Motion, and each language's namespaces beyond the one in
   use. A screen that needs one of these owns its chunk.
3. **Budgets, gzipped, enforced by `productionBundle.build.test.ts`** on a real production build,
   in `pnpm test` and therefore in CI:

   | What | Budget | Measured at this amendment |
   |---|---|---|
   | Initial JavaScript (entry plus everything `index.html` preloads) | **170 KB** (30 KB under D-39's 200 KB) | 158.3 KB |
   | Any single lazily loaded chunk | **120 KB** | 39.1 KB (the shell) |

   The initial budget is deliberately below D-39's limit so that the next screen cannot spend the
   last of it. Raising either number needs a new amendment and the owner's agreement, not an edit
   to a constant.
4. **Per route, the budget is the route's own chunks**: a route may load up to the 120 KB chunk
   budget on top of the initial bundle. The heaviest routes are expected to be the alert feed
   (DataGrid), the investigation drawer's SHAP and behavioural charts (Recharts), the network
   graph (D3-force) and the risk officer's heatmap (D-46); each must stay inside it, or split
   further — for example loading a drawer tab's chart only when that tab is opened.

**How the 194.8 KB came down.** `zod` became `zod/mini` (−16 KB), the MUI `Select` became a native
one (−10 KB), and the shell became a lazy route (−36 KB). React, MUI's styling engine, the router,
TanStack Query and i18next are what remain, and they are the floor for any screen.

## Amendment 2026-10-19 — §10, the margin, on a measured basis (owner decision)

**What the two numbers protect.** 200 KB is the SRS's own gate (MOB-PERF-06: initial JavaScript
"< 200 KB" gzipped on 3G and 4G, and the build fails above it), set beside the 3G timing targets
MOB-PERF-01 (first contentful paint under 2.5 s) and MOB-PERF-03 (time to interactive under 5 s).
It stays. 170 KB was this ADR's own margin, chosen as 30 KB under 200 so that one screen could not
spend the rest; it was a round number, not derived from a measurement.

**Why the entry grew with no screen attached.** Between the first amendment (158.3 KB, `cf4cb8f`)
and the first analyst screen (167.9 KB) the entry grew by 9.9 KB. Measured commit by commit with
the test's own method (production build, the first country pack, gzip level 6, the entry plus
everything `index.html` preloads), and attributed by the modules in each build's source maps:

| Commit | What it did | Entry | Change | Modules that entered |
|---|---|---|---|---|
| `cf4cb8f` | this ADR's first amendment | 158.0 KB | | |
| `cb5b02b` | session and sign-in | 164.0 KB | +6.0 | the generated API client (fetch client, serializers, server-sent-events support), `session.tsx`, `tokens.ts`, `csrf.ts`, `loginSearch.ts`, design-token JSON |
| `2d374f2` | registration | 164.2 KB | +0.2 | none; `router.tsx` grew |
| `754b7e5` | recovery, unlock, pending approval | 165.4 KB | +1.2 | none; four more route definitions in `router.tsx` |
| rebase onto `main` | | 165.4 KB | 0 | |
| `02555dd` | `/system/status` withdrawn | 165.8 KB | +0.3 | none; the chunks split differently |
| `74b4117` | the alert filters follow the contract | 167.1 KB | +1.3 | `zod/v4/mini/iso`; `alertSearch.ts` grew (lists, UUIDs, dates, patterns) |
| to `9824c96` | the filters' review fixes | 167.9 KB | +0.8 | `alertSearch.ts` grew (amounts read from numbers) |
| screen 1 | the alert feed | 168.9 KB | +1.0 | none; the lazy chunk changed how shared code splits |
| screen 2 | the investigation drawer | 169.3 KB | +0.4 | none; one more lazy route |

**So nothing grew by accident.** Six of the 9.9 KB are the cost of restoring a session at start-up:
every visit asks the refresh cookie for a session before it renders, so the API client and the
session code are needed before any screen. Two more are the URL validation of the feed's filters,
which must sit with the route definitions in the entry. The rest is route definitions. One part is
dead weight: the generated client's server-sent-events support, which the console never uses,
costs 0.7 KB (measured by stubbing it out); removing it means patching generated code and is left
alone for now.

**Decision.**

1. **200 KB stays the gate** the SRS sets. It is a requirement, not this ADR's to move.
2. **The margin is 185 KB** (`productionBundle.build.test.ts`). The basis: the screens built so
   far cost the entry 0.4 to 1.0 KB each, and the largest single change measured was 2.1 KB (the
   filters' validation). Six analyst screens remain, and the risk officer's and administrator's
   after them; at 169.3 KB, 185 KB leaves 15.7 KB, room for that work at the measured rate while
   staying 15 KB under the SRS gate. It is still a margin, so a screen that needs more than its
   share must come back with a measurement, not an edit to the constant.
3. **The timings are measured, not inferred from size.** A size gate stands in for timing targets
   only as a proxy, and MOB-PERF-01 and MOB-PERF-03 had never been measured. The Playwright suite
   measures them on a production build under throttled 3G and a slowed CPU, in CI
   (`e2e/performance.spec.ts`).
