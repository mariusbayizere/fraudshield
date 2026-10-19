# 0084 — The SHAP explanation is an accessible table with bars, not a Recharts chart

- **Status:** Accepted (owner decision, 2026-10-19)
- **Date:** 2026-10-19
- **Requirements affected:** FR-04-05, UX-DASH-05, MOB-COMP-08 (deviations), D-45, UX-ROLE-AN-02
- **Defects referenced:** D-39 (initial bundle), D-45 (44 features under time pressure); ADR 0003
  (versions), ADR 0009 (licences), ADR 0080 (console architecture), ADR 0083 (the feed's cards)

## Context

FR-04-05 asks for a "SHAP waterfall chart" built with "Recharts HorizontalBarChart" and an MUI
Tooltip per bar carrying a plain-language description; UX-DASH-05 repeats it; MOB-COMP-08 asks for
a vertical top-5 view on phones with a "Show all" bottom sheet. Recharts is not a dependency of the
console. Screen 2 (`M8_updates.md` section 9) drew the explanation as a table: one row per
contribution, largest effect first, with the feature, its raw value, the signed effect, an arrow,
and a bar to scale in the theme's SHAP colours; the base value, final margin and calibrated
probability are stated above it; the first view shows the top 10 (5 below 600 px) and one row
netting the rest (D-45).

## Decision

The explanation stays a table. Recharts is not added (owner, 2026-10-19), for the reason ADR 0083
gives for the DataGrid: a library is not added to match a component a row names when the row's
content can be met without it. The table also reads in order to a screen reader, row by row,
which a chart needs extra work to do.

## What the table does not provide that the named chart would

This decision makes the explanation **sufficient for the requirement's content, not equivalent to
the chart**. M11 and M12 should describe it as such. Against FR-04-05, UX-DASH-05 and MOB-COMP-08
as written, the table does not provide:

1. **A waterfall.** Nothing shows the cumulative path from the base value to the final margin,
   bar by bar; the two ends are stated as numbers. (The named HorizontalBarChart would not have
   drawn a waterfall either: the requirement names both.)
2. **A diverging axis.** Every bar grows from the same edge; whether a feature raised or lowered
   the risk is carried by colour, arrow and sign, not by the bar extending left or right of zero.
3. **An absolute scale.** Bars are relative to the largest effect shown; there are no axis ticks,
   so two explanations' bars cannot be compared by length.
4. **Per-bar tooltips.** There is no hover or focus tooltip. The plain-language text it would hold
   does not exist (PB-132), so this gap is the templates' as much as the table's.
5. **MOB-COMP-08's phone layout.** No vertical orientation and no bottom sheet; "Show all" expands
   the table in place.

## Consequences

- FR-04-05, UX-DASH-05 and MOB-COMP-08 record this ADR as their deviation. **A deviation never
  promotes a row past the mock rule** (owner, 2026-10-19): each stays IN_PROGRESS until M8B's
  getAlertExplanation exists and the screen is exercised against it.
- No dependency, licence review or chunk; the drawer's chunk is 11.5 KB.
- If an analyst study (not possible on synthetic data) found the waterfall or a diverging axis
  changes decisions, this decision should be revisited; nothing here claims it would not.
