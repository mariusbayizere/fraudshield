# 0101 — Prerequisites for the chaos and benchmark campaign

- **Status:** Accepted for M10's own conduct, 2026-09-24. The items it places on other milestones
  are proposals recorded in their updates files, not changes made on their behalf.
- **Date:** 2026-09-24
- **Requirements affected:** NFR-PERF-01…08, NFR-PERF-10, NFR-REL-01…06, TEST-09, TEST-12,
  MOB-DEV-01…07, FR-02-07, FR-02-09, ML-GATE-12, ML-GATE-13, TEST-10, ML-DATA-01
- **ADRs referenced:** 0010 (gate numbers only from a dedicated machine recorded before
  measurement), 0032 (the scoring gate is a throughput requirement), 0034 (the skew carry), 0035
  (M5's carried rows), 0059 (M6's latency carry), 0090 (the shared spool and its M10 chaos case)

## Context

M10 is a campaign, not a feature: it measures a system other milestones built, on hardware nobody
has yet rented. Its harnesses are written (`tests/performance`, `tests/chaos`, `tests/e2e`) and
none has been executed. The work of preparing them surfaced conditions that are invisible until
the day the campaign starts, and each has the same failure mode — **the campaign appears to run
and proves nothing**:

- A chaos experiment whose selector matches no pod reports success. Three of the five Part C.4
  cases target data stores (PostgreSQL and TimescaleDB, Redis, Kafka) that have no manifests in
  this repository; M9 fixes only their label contract. Those experiments will select nothing.
- The application namespace enforces restricted Pod Security and denies all traffic in both
  directions by default. A chaos controller installed into it is rejected, and an agent without a
  network policy cannot reach the pods it is meant to disturb — again, quietly.
- Every console route renders a placeholder, so the five journeys fail at the first assertion
  after sign-in. That is honest failure rather than false success, but it is discovered late.
- A number measured before the machine is recorded in `docs/benchmarks/hardware.md` cannot be
  rescued afterwards (ADR 0010), and the campaign's most expensive rows are the ones most likely
  to be run first by someone eager to see a figure.

## Decision

1. **The campaign starts with a proof that each harness can fail.** Before any measured row: one
   chaos experiment that injects nothing and is confirmed to have selected the intended pods; one
   short load run at a low rate confirming authentication, the synthetic-data check and the
   response-contract check; one journey on one browser. A harness that cannot demonstrate its own
   failure mode does not produce evidence.
2. **The machine is recorded before the first measurement**, as its own section in
   `docs/benchmarks/hardware.md`, written from commands run on it, including whether its vCPUs are
   dedicated. No row may cite a run that predates that section.
3. **Chaos Mesh is installed in its own namespace**, never in the application namespace, and is
   granted access by an explicit network policy. The policy is part of the campaign's setup record
   so that a later reader knows what was opened and when it was closed.
4. **A case whose target does not exist is recorded as not run.** It is never approximated by
   disturbing something adjacent, and its requirement row stays open. This applies today to the
   three data-store cases and to the two cases that need a multi-node cluster.
5. **Each prerequisite is written into the owning milestone's updates file with the acceptance
   criterion M10 needs**, so that the milestone that can fix it sees it before the campaign starts:
   M9 for the data-store manifests and the chaos controller's placement, M8 for the console's
   screens and the selectors the journeys need, M6 for the rate limit of ADR 0100.
6. **The campaign does not begin until M6, M7, M8 and M9 are on `main` and the hardware is
   decided** (owner direction, 2026-09-24).

## Consequences

- M10's harnesses sit unexecuted on `m10/verification` until those conditions hold, which is the
  intended state and is stated in every file.
- The prerequisite list is a commitment: if a milestone declines an item, the campaign records the
  affected rows as not run rather than substituting a weaker experiment.
- The first day of the campaign is spent proving the instruments work rather than producing
  numbers. That is the cheapest hour of the milestone: every alternative discovers the same
  problems after a measurement has been believed.
- Nothing here closes a requirement or authorises spending. The machine specification is
  `docs/benchmarks/m10_machine_spec.md`; the decision to rent is the owner's.
