# Review: M8 front-end, branch `m8/frontend`            Reviewer role: Principal Reviewer
Date: opened 2026-09-22 (UTC)   Branch/commit: in progress; the design-system section is at d8e269f
Requirements: FR-04-12 (accessibility), E.9 design tokens and components   Defects: D-33, D-34, D-36, D-37
Verdict: **not yet given.** This record collects evidence step by step; the milestone review
(Part I, one pass) gives the verdict before M8 merges.

## Checks re-run (command → result)

| Commit | Command | Result |
|---|---|---|
| d8e269f | `pnpm typecheck`, `pnpm lint`, `pnpm format:check` | clean |
| d8e269f | `pnpm test:coverage --maxWorkers=1` | 243 tests pass; 100% statements, branches, functions, lines |
| d8e269f | `pnpm build-storybook` | 44 stories built, the same 44 that `stories.test.tsx` axe-checks |
| d8e269f | CI `ci` 35719973368, `frontend` job | success |
| 2d374f2 | CI `ci` 35771102331, `frontend-e2e` 35771102320 (Chromium, Firefox, WebKit), `stack`, `devcontainer` | success |
| 98acac4 | CI `ci` 35758074849 | **failure**: `pnpm build-storybook`. Storybook builds with the app's vite.config.ts, so vite-plugin-pwa ran there and failed on Storybook's 3.3 MB manager bundle. Fixed in a904662. |
| 1c9bd00 | CI `ci` 35763389660 | **failure**: `pnpm lint`, a spread-on-Storage error committed in d160b3b and fixed in 2d374f2. The same run's `java` job also failed, on a tree whose backend is identical to a green run; it passed again on 2d374f2. |

## Mutation spot checks (what was broken → which test failed)

At d8e269f. One file per mutant, restored with `git checkout`; the tree was clean before and after.

| # | Mutant | Test run | Outcome |
|---|---|---|---|
| 1 | RiskBadge text in the badge hue, not the text-safe token | RiskBadge | caught |
| 2 | Block threshold `>` instead of `>=` | RiskBadge (tierOf) | caught |
| 3 | Flag threshold `>` instead of `>=` | RiskBadge (tierOf) | caught |
| 4 | Score range check removed | RiskBadge (tierOf) | caught |
| 5 | Gauge percentage rounds instead of flooring | RiskScoreGauge | caught |
| 6 | Gauge percentage loses its epsilon (0.57 shows 56) | RiskScoreGauge | caught |
| 7 | Gauge not keyboard-reachable (`tabIndex={-1}`) | RiskScoreGauge | caught |
| 8 | Gauge meter loses `aria-label="Fraud score"` | stories.test.tsx (axe) | **equivalent mutant** (below) |
| 9 | Countdown turns red at `<= 10` | CountdownChip | caught |
| 10 | Chime callback fires every tick | CountdownChip | caught |
| 11 | Countdown goes below zero | CountdownChip | caught |
| 12 | Pulse ignores reduced motion | RiskCard | caught |
| 13 | Pulse never stops | RiskCard | caught |
| 14 | MEDIUM cards pulse too | RiskCard | caught |
| 15 | Channel chip on the SRS fill hue, not the chip token | ChannelChip | caught |
| 16 | Money loses tabular numerals | Money | caught |
| 17 | "Last updated" in UTC, not Kigali time | ScreenStates | caught |
| 18 | Banners drop `KAFKA_SPOOLING` | SystemBanner (contract test) | caught |
| 19 | Banners interrupt (`role="alert"`) | SystemBanner | caught |

**Mutant 8 is equivalent, not a gap.** With its `aria-label` removed, the meter still has an
accessible name: MUI's Tooltip names an unlabelled child from its string `title`, so the meter is
read as "XGBoost + LightGBM ensemble. 0.85+ = HIGH risk auto-block threshold." That was measured,
not assumed. Axe is right to find no violation, because the tooltip supplies an accessible name.
The specific name "Fraud score" is held by `RiskScoreGauge.test.tsx`
(`getByRole('meter', { name: 'Fraud score' })`); the mutant was run against the axe suite only.

**The axe suite has teeth.** Removing `aria-hidden` from the gauge's value ring exposes an unnamed
progressbar. That fails all 8 gauge stories with `serious aria-progressbar-name`.

### Region, i18n and RTL (at 66c7df5)

| Mutant | Outcome |
|---|---|
| Money ignores the pack's minor units | caught |
| formatMoney accepts any string | caught |
| formatMoney formats through a float | caught |
| Offset label drops minutes | caught |
| Clock ignores the region's offset | caught |
| regionFor falls back to the first pack | caught |
| sw catalogue loses a string | caught |
| fr loses its `many` plural form | caught |
| rw drops a placeholder | caught |
| A status claims an invalid value | caught |
| RTL Emotion cache loses `stylis-plugin-rtl` | **survived**; fixed by a3c871f (MUI's icon margins must swap sides), then caught |
| ThemeRoot never sets `<html dir>` | caught |
| Lint allows `marginLeft` | caught |
| `packs.json` edited by hand | caught |

### App shell and production build (at 6f1db49 and 914e4b8)

| Mutant | Outcome |
|---|---|
| Mocks start in every mode | caught |
| Mock guard keyed on `DEV` again | **survived** the release build alone; a second build with NODE_ENV=test added (914e4b8), then caught |
| Service worker caches `/api/` | caught |
| Preflight imported | caught |
| Font CDN `@import` at the top of fonts.css | **ineffective mutant**: an `@import` after other rules is invalid CSS and was dropped, so nothing reached the output. Retried as a `<link>` to the CDN in index.html: caught |
| All Inter subsets shipped | caught |
| Responses validated in production | **equivalent for now**: no screen calls `checked()` yet, so it is tree-shaken either way. Re-run when the first screen uses it. |
| Tier filter fails instead of dropping | caught |
| Drawer anchored physically right | caught |
| Skip link targets nothing | caught |
| Bottom bar loses Search | caught |
| Language choice not remembered | caught |

### Playwright (at 4ef3911)

| Mutant | Outcome |
|---|---|
| `?dir=` override ignored | caught (rtl.spec.ts) |
| RTL cache without `stylis-plugin-rtl` | caught (rtl.spec.ts) |
| Navigation links straight inside `<ul>` | caught by the AppShell axe test. The first attempt replaced only the opening tag and did not compile, so it proved nothing; it was redone with both tags replaced. |

### Auth (at 2d374f2, 754b7e5)

39 mutants across the session, sign-in, registration, password policy and recovery.
Caught after the fixes below; the survivors are recorded because each was a gap in the tests,
not in the code:

| Survivor | Why it survived | Fixed by |
|---|---|---|
| Token read from localStorage | No test asserted the token never reaches storage | `tokens.test.tsx` (d160b3b) |
| Authorization header dropped | No test asserted the header is sent | `tokens.test.tsx` (d160b3b) |
| Guard renders the console to an anonymous visitor | The test asserted the redirect, not that content stayed hidden | `session.test.tsx` (d160b3b) |
| Password length in UTF-16 units | The shared vectors reach past the BMP only through byte counts | `password.test.ts` (dd0cb84) |
| Employee ID pattern widened | No test rejected a malformed ID | `fields.test.ts` (dd0cb84) |
| "Code is on its way" echoes the address | The assertion was a substring match | `recovery.test.tsx` (13ad126) |
| Six-digit code widened to any digits | That test left the email empty, so the button was disabled anyway | `recovery.test.tsx` (13ad126) |
| Reset sends a constant token | The fixture token was that same constant | `recovery.test.tsx` (5877a4f) |

Two notes on method:

- An early "links straight inside `<ul>`" mutant replaced only the opening tag, so the file did
  not compile and the "catch" proved nothing. Mutants must compile; it was redone.
- At 754b7e5 the mutation loop's `git checkout --` discarded uncommitted improvements to the
  same files, which had to be redone. The rule stands: commit before any mutation run.

## Findings

None recorded yet; the milestone review fills this table.

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|

## Evidence reproduced (claimed vs measured)

Filled at review.

## Residual risks

- `productionBundle.build.test.ts` sat in `src/build/`, which the root `.gitignore` hides, so
  6f1db49 described a test it did not contain, and CI never ran it until 914e4b8. Any new
  directory named `build/` anywhere in the tree is invisible to git.

- Axe runs in jsdom, which does no layout or paint, so colour contrast is measured by
  `tokens.test.ts` over every token pair, not on rendered pixels. The Playwright journeys run
  axe with contrast in real browsers.
- jsdom never matches `:focus-visible`, so the tooltip opening on keyboard focus is left to the
  Playwright journeys. The unit test proves keyboard reachability and the tooltip's content.
