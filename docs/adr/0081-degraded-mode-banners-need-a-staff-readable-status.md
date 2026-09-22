# 0081 — Degraded-mode banners need a status endpoint every staff role can read

- **Status:** Accepted (owner decision, 2026-09-22)
- **Date:** 2026-09-22
- **Requirements affected:** FR-04-12, FR-06-05, E.9 (screen state contract), C.4 (degraded modes)
- **Defects referenced:** D-18, D-29

## Context

E.9's screen state contract puts system-wide banners above **every** screen: `ML_UNAVAILABLE`,
`DEGRADED_MODE` and "Real-time paused — refreshing every 60 s". The console renders them with
`SystemBanners`, whose conditions are pinned by test to the contract's `degraded_modes` list.

The only operation that exposed that list was `GET /admin/health`
(`x-required-roles: [ADMIN]`), which also returns Kafka consumer lag per topic and group, scoring
latency percentiles, API error rate, Redis hit ratio, database pool usage, auto-block rate and
queue depths. An analyst may not read it, and should not: that is operational detail about the
institution's infrastructure, and FR-06-05 scopes it to administrators.

So either analysts never see the banners E.9 requires, or every role reads the administrators'
health payload. Neither is acceptable.

## Options considered

1. **Show the banners to administrators only.** The analyst, who is deciding whether to trust a
   score, is exactly the person who must know the model is unavailable. Rejected.
2. **Open `GET /admin/health` to all staff roles.** Leaks component names, latencies and queue
   depths to every analyst, and widens what a stolen analyst token discloses. Rejected.
3. **Put the degraded modes on the alert WebSocket.** The banners belong above every screen, not
   only where the feed is open, and the socket is FR-04's, not the shell's. Rejected for now,
   though a push could later complement the poll.
4. **A separate, minimal status endpoint for staff.** Chosen.

## Decision

`GET /api/v1/system/status`, `x-required-roles: [ANALYST, SENIOR_ANALYST, RISK_OFFICER, ADMIN]`,
returns exactly one field:

```json
{ "degraded_modes": ["ML_UNAVAILABLE"] }
```

`SystemStatus` is `additionalProperties: false` with `degraded_modes` required, and its enum is
the same four values as `SystemHealth.degraded_modes`. It carries no component names, versions,
hostnames, metrics or queue depths: an analyst learns *that* scoring is degraded, not *how* the
platform is built. `GET /admin/health` is unchanged and stays ADMIN-only.

The console polls it every 60 seconds, the cadence E.9 gives the degraded feed ("refreshing every
60 s"), and shows one banner per active condition.

## Consequences

- **M6 must implement it** (the decision engine owns degraded state, C.4). The requirement is
  written for the M6 agent in `docs/parallel/M8_updates.md`; until it exists, the console's
  development mocks serve it and the banner area stays empty against a real backend.
- The two lists could drift. `test_degraded_modes_are_the_same_list_for_staff_and_for_admins`
  fails if they do, and checks the payload stays free of component detail.
- One more unauthenticated-to-authenticated surface: it is read-only, returns a fixed-size list
  from a closed enum, and needs a valid staff token like any other staff endpoint.
- Polling every 60 s per open console is a small, steady load; the endpoint must be cheap to
  serve (a cached in-memory flag set, not a fan-out of health checks).
