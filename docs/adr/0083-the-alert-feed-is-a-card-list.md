# 0083 — The alert feed is a card list, not the MUI X DataGrid

- **Status:** Accepted (owner decision, 2026-10-18)
- **Date:** 2026-10-18
- **Requirements affected:** UX-DASH-02 (deviation), FR-04-02, FR-04-03, MOB-COMP-04
- **Defects referenced:** D-35 (DataGrid row pinning is a paid feature), D-39 (initial bundle);
  ADR 0003 (versions), ADR 0009 (licences), ADR 0080 (console architecture)

## Context

UX-DASH-02 specifies the analyst's feed as an "MUI DataGrid virtualised" with columns for the risk
badge, score gauge, amount, channel, masked name, merchant, time, countdown and actions, HIGH
pinned at the top, and virtualisation for 10,000 rows. `@mui/x-data-grid` is not a dependency of
the console; ADR 0080 names it only as a heavy route dependency a screen might bring.

Screen 1 of the analyst screens (M8_updates.md section 9) built the feed as a list of cards, each
carrying all eight elements FR-04-03 requires, plus the MEDIUM countdown, on every screen width.

## Decision

The feed stays a card list. The DataGrid is not added (owner, 2026-10-18).

The requirement's intent is a list an analyst can scan quickly, highest risk first. The cards meet
it: every UX-DASH-02 column is an element of the card, the order is the server's (`listAlerts`
sorts HIGH first, then probability, D-10), and the same component serves the phone layout 05B
A.7 asks for. Adding a library to match a component a UX row names is the wrong trade when the
requirement's content is already met.

## Consequences

- **UX-DASH-02 is met with a deviation**: cards instead of grid rows. Pinning is the server's
  priority order (as D-35 resolves it without the paid feature). "Virtualised for 10,000+ rows"
  becomes cursor paging: the feed renders the pages loaded, 50 at a time by default, and does not
  hold 10,000 rows in the DOM.
- **Not covered by this decision**: the row's "new alert animates in from right (Framer Motion)",
  which depends on the real-time channel the contract does not have (M8B_updates.md), and its
  action buttons, which are the decision panel's (screen 3).
- No new dependency, licence review or bundle chunk.
