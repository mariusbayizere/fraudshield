# 0085 — The account's behaviour and network are drawn without a chart or graph library

- **Status:** Proposed (M8 screen 4, 2026-10-19), for the owner to decide, as for ADR 0083 and 0084
- **Date:** 2026-10-19
- **Requirements affected:** FR-04-07, FR-04-08 (deviations); FR-04-06 (none)
- **Related:** ADR 0003 (versions), ADR 0009 (licences), ADR 0080 (console architecture), ADR 0083
  (the feed's cards), ADR 0084 (the SHAP table), D-33 (contrast), D-39 (bundle)

## Context

Screen 4 adds three tabs to the investigation drawer: the account's history (FR-04-06), its
behavioural fingerprint (FR-04-07) and its transaction network (FR-04-08).

- FR-04-07 asks for four panels: an hourly heatmap, an amount histogram, a channel **pie chart** and
  a "this transaction vs your normal" table with anomalous fields in amber.
- FR-04-08 asks for a graph of the account, its counterparties and their counterparties, with
  confirmed-fraud nodes red with a glow, zoom and pan, "using vis-network or D3".

None of Recharts, D3 or vis-network is a dependency of the console. ADR 0084 kept Recharts out for
the SHAP explanation. The owner gave the reason: a library is not added to match a component a row
names when the row's content can be met without it.

## Decision (proposed)

**FR-04-07** is drawn with MUI tables, shading and bars:

- **Hours:** 24 cells in four rows of six. Each cell has its hour and its count written, and a
  swatch shaded in proportion to the busiest hour. The text sits on the plain background, so
  contrast does not depend on the shade. Bars and shades use the accent (`brand.accent`), which
  D-33 measures on paper in both modes.
- **Amounts:** one row per bin, with its range, its count and a bar to scale.
- **Channels:** one row per channel, with its count, its share as a percentage and a bar to scale,
  in place of the pie.
- **Comparison:** a table. An unusual row is drawn on the amber tint (`risk.medium`, a pair D-33
  measures), with a warning icon and the word "Unusual", so colour is not the only cue.

**FR-04-08** is an SVG drawn in a fixed layout, with no simulation:

- **Layout:** the account at the centre, its counterparties on a ring, and the second degree on an
  outer ring. Each counterparty gets a sector in proportion to the accounts hanging off it and sits
  at its centre; those accounts sit a node's room apart, centred on it, so each is drawn beside its counterparty.
  The rings grow with the number of sectors so that no two nodes overlap, and the drawing grows to
  hold them. A link's width follows its transaction count.
- **Confirmed fraud:** a red ring (`risk.high.border`), a red glow (an SVG drop shadow), the tint
  and a "!" mark.
- **Zoom:** buttons from ×0.5 to ×4, plus a reset.
- **Pan:** drag with the main button, or the arrow keys while the drawing has focus (WCAG 2.5.7
  asks for an alternative to dragging). Focus is shown by the theme's focus ring.
- **Text equivalent:** the drawing is a named group described by the counts and the instructions.
  Two tables below it list every account (token, degree, confirmed fraud) and every link (from,
  to, transactions).

There is no new dependency, licence review or chunk. The tabs are part of the drawer's lazy chunk,
so the first visit is unchanged.

## What these do not provide that the named components would

The drawings are **sufficient for the requirements' content, not equivalent to the named
charts**. M11 and M12 should describe them that way. Against the rows as written:

1. **No pie chart.** Part-to-whole is given as percentages and bars, not as angles.
2. **The heatmap has one dimension.** It covers hour of day only. The contract's `hourly_counts`
   is 24 numbers, so an hour × weekday map could not be drawn from it with any library.
3. **No axes or tooltips.** Bars and shades are relative to the largest value shown. Every number
   is written beside its bar, but two accounts' bars cannot be compared by length.
4. **No force-directed layout.** Clusters are not pulled apart, and links between two
   counterparties, or from a second-degree account to a second counterparty, cross the drawing.
   Nodes do not overlap: the rings grow until each has 36 units of its ring (a node is 28 across).
   At 50 accounts the drawing is about 1.6 times as wide as at 10, so at zoom 1 in the drawer its
   11-unit labels shrink to about 7 px and need zooming in to read. A node is labelled by the last
   four characters of its token, so two tokens with the same ending look alike on the drawing. The
   table carries the whole token.
5. **No wheel or pinch zoom, no node dragging, no hover details.** Zoom is by button only.
6. **Links to accounts not in the node list are not drawn.** The contract does not forbid them.
   They are listed in the links table. Nor does the contract make tokens unique: a repeated token
   is drawn once, where it first appears, and listed as often as it is sent (`M8B_updates.md`, item 8).

## What this does not settle

- **Timing is not measured.** FR-04-06 asks for 500 ms, FR-04-07 for 1 s and FR-04-08 for 2 s
  with 50 nodes. All three are untested.
- **Accuracy against TimescaleDB is untested.** That is M8B's to test: the server computes every
  figure, and the console only draws it.
- **The contract leaves three things open** (`M8B_updates.md`, items 6 to 8):
  - the time zone of `hourly_counts`;
  - the catalogue of `comparison.attribute` names. The table shows the raw key (`local_hour`), as
    the SHAP table shows feature names. The plain-language name is the same gap as PB-132's.
  - which of the lists are unique (tokens, links, attributes, transaction ids).

## Consequences

- FR-04-07 and FR-04-08 record this ADR as their deviation. As ADR 0084 says, **a deviation never
  promotes a row past the mock rule**: each stays IN_PROGRESS until M8B serves the operation and
  the screen is exercised against it.
- If the owner rejects this proposal, the next step is a dependency decision: D3 (ISC) or
  vis-network (Apache-2.0 or MIT). That needs ADR 0009's licence review and ADR 0003's pinning,
  and it adds a lazy chunk to the drawer.
