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

## Findings

None recorded yet; the milestone review fills this table.

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|

## Evidence reproduced (claimed vs measured)

Filled at review.

## Residual risks

- Axe runs in jsdom, which does no layout or paint, so colour contrast is measured by
  `tokens.test.ts` over every token pair, not on rendered pixels. The Playwright journeys run
  axe with contrast in real browsers.
- jsdom never matches `:focus-visible`, so the tooltip opening on keyboard focus is left to the
  Playwright journeys. The unit test proves keyboard reachability and the tooltip's content.
