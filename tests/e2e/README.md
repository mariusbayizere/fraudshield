# End-to-end journeys (TEST-12)

**NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).** No run,
no browser matrix result and no timing exists. The five specs here describe the journeys the
specification names, against the frozen API contract and the console's published selectors.

| Spec | Journey (SRS section 9, TEST-12) | Requirements |
|---|---|---|
| `journey1-confirm-fraud.spec.ts` | Sign in → feed loads → HIGH alert pulses → drawer → SHAP renders → CONFIRM FRAUD with a comment → alert removed | FR-04-01, FR-04-02, FR-04-03, FR-04-05, FR-04-04, D-34, D-44, NFR-PERF-06, NFR-PERF-07 |
| `journey2-medium-countdown.spec.ts` | MEDIUM countdown to zero → auto-release label | FR-03-02, D-13, D-10 |
| `journey3-escalation.spec.ts` | Analyst escalates to a Risk Officer | FR-04-10 |
| `journey4-model-promotion.spec.ts` | Admin promotes a new model version | FR-06-03, D-11 |
| `journey5-heatmap.spec.ts` | Risk Officer views the fraud heatmap | FR-05-02, D-46 |

## Why these are here and not in `frontend/e2e`

M8's suite runs the console against Vite with mocked APIs, which is right for a component
milestone: it proves the console renders and stays accessible. M10 verifies the system, so these
run against a deployed instance with the real API, the real scorer and real data flowing. A
journey that passes against a mock cannot see an escalation that reaches no queue, a countdown
that expires without a release, or a heatmap cell below its k-anonymity threshold — and those are
the failures the journeys exist to catch.

The two suites share selectors deliberately. Where M8 publishes a handle (`PULSE_CLASS`, the
countdown's `role="timer"` and `data-urgent`, the gauge's `role="meter"`), these specs use it.

## Running them

There is no separate package: the Playwright that ships with the frontend runs this config.

```console
$ FS_E2E_BASE_URL=https://<console-host> \
  FS_E2E_PASSWORD_ANALYST=... FS_E2E_PASSWORD_RISK_OFFICER=... FS_E2E_PASSWORD_ADMIN=... \
  pnpm --dir frontend exec playwright test --config ../tests/e2e/playwright.config.ts
```

Passwords come from `.demo-credentials`, which `make seed-demo` writes locally and git ignores.
No password, token or key is committed here, and the specs refuse to run without them rather than
falling back to a default.

Timings (`NFR-PERF-06`, `NFR-PERF-07`, `NFR-PERF-08`, and promotion and rollback in journey 4) are
benchmark rows. They are always recorded as annotations and only asserted when
`FS_E2E_ASSERT_TIMING=1`, which the campaign sets on the dedicated machine, so no green run on a
shared runner can be read as a performance result.

## What these specs assume that does not exist yet

Every console route on `m8/frontend` still renders its "not built yet" placeholder, so all five
journeys fail today at the first assertion after the feed. That is the expected state; M8 builds
the screens, and the campaign runs these against them.

Selectors that exist now: `PULSE_CLASS` (`fs-pulse`) on a pulsing HIGH card, `role="timer"` with
`data-urgent` on the countdown chip, `role="meter"` named "Fraud score" on the gauge, the risk
badge's "HIGH RISK" text, the navigation and main landmarks, and the level-1 heading per route.

Selectors these specs assume and M8 must provide (or tell M10 the real ones):

| Assumed | Used by | Note |
|---|---|---|
| The investigation drawer exposes `role="dialog"` with a tab named "SHAP Explanation" | 1 | SRS 5.4 specifies MUI Drawer with tabs |
| The SHAP chart is reachable as a figure or a named image | 1 | it must be in the accessibility tree for the M8 Axe gate anyway |
| A comment textbox and buttons named "Confirm fraud" / "Escalate" | 1, 3 | FR-04-04, FR-04-10 |
| `data-alert-id` on an alert card | 3 | the only way to follow one alert from the analyst's feed to the officer's queue; an accessible name carrying the alert reference would do as well |
| A target-role combobox and a reason textbox in the escalation control | 3 | FR-04-10's mandatory reason |
| A period combobox on the portfolio screen | 5 | the heatmap's `period` query parameter |

Journey 1's sign-in uses the email and password form. The SRS journey says Google OAuth, and
`POST /api/v1/auth/google` exists in the contract, but no console affordance does: the campaign
runs the OAuth leg against the deployed identity provider once that button ships, and records
NFR-PERF-08 from it. Until then journey 1 covers every step after sign-in, and the plan records
the OAuth leg as not exercised rather than quietly dropping it.
