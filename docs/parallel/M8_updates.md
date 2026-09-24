# M8 updates

> **Note for whoever merges.** The `m8/frontend` branch carries its own version of this file. This
> copy exists because M10 needed somewhere on `main` to leave findings for M8 and the convention is
> one updates file per milestone. At merge, M8's file wins and the section below is appended to it.

## From M10 (branch `m10/verification`, 2026-09-24)

M10 wrote the five SRS analyst journeys (`tests/e2e/`, TEST-12) against the frozen API contract and
the console's published selectors. They run against a deployed instance rather than the dev server
with mocks, because a journey that passes against a mock cannot see an escalation that reaches no
queue or a countdown that expires without a release. Two findings for M8, and one contract.

### 1. The Google sign-in button does not exist, and the endpoint does

`POST /api/v1/auth/google` is in the frozen contract and M7 implements it; the login page renders
only the email and password form. Journey 1 therefore signs in with email and password and covers
every step after that.

**What M10 will report:** the OAuth leg, and the requirement row timed from it, are recorded as
**not exercised**. Neither is reported as passing, and the verification report will say so in those
words. If the button ships before the campaign runs, M10 adds the leg and times it.

**Acceptance criterion M10 needs:** a "Continue with Google" control on `/login` with an accessible
name, reaching the existing endpoint.

### 2. The screens the journeys need are placeholders

Every route under the shell renders "Not built yet", so all five journeys fail at the first
assertion after the feed. That is the expected state today and needs no action beyond M8's own
plan; it is recorded so that a failing journey run is not mistaken for a regression.

### 3. Selectors the specs assume, which M8 owns

M10's specs use the handles M8 already publishes — `PULSE_CLASS` (`fs-pulse`) on a pulsing HIGH
card, `role="timer"` with `data-urgent` on the countdown chip, `role="meter"` named "Fraud score"
on the gauge, the navigation and main landmarks, and the level-1 heading per route. Beyond those,
the journeys assume the following, and `tests/e2e/README.md` lists them in full:

| Assumed | Journey | Note |
|---|---|---|
| The investigation drawer exposes `role="dialog"` with a tab named "SHAP Explanation" | 1 | SRS 5.4 specifies a drawer with tabs |
| The SHAP chart is reachable as a figure or a named image | 1 | it must be in the accessibility tree for M8's own Axe gate |
| A comment textbox, and buttons named "Confirm fraud" and "Escalate" | 1, 3 | FR-04-04, FR-04-10 |
| **A stable handle identifying one alert** (`data-alert-id`, or an accessible name carrying the reference) | 3 | the only way to follow one alert from an analyst's feed into a risk officer's queue; without it the escalation journey cannot prove the entry that arrived is the one that was sent |
| A target-role combobox and a reason textbox in the escalation control | 3 | FR-04-10's mandatory reason |
| A period combobox on the portfolio screen | 5 | the heatmap's `period` parameter |

Tell M10 the real selectors if they differ; the specs are cheap to adjust and are not a constraint
on M8's design. The one that is not merely cosmetic is the alert handle in journey 3.
