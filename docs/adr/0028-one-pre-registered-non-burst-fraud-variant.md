# 0028 — One pre-registered non-burst fraud variant, and why it is not the circularity declined for countries

- **Status:** Accepted
- **Date:** 2026-09-22
- **Decided by:** repository owner, 2026-09-22, on the reasoning below (author drafted, owner directed)
- **Requirements affected:** D-08, ML-DATA-02, PB-59, PB-60, PB-61

## Context

M4's battery measured both of PB-46's generalisation experiments and both came back null.
**Leave-one-country-out**: removing a country from training entirely changes its AUC by at most
0.002. **The novel sub-variant**: a fraud shape present only in the test period is caught at
100.0% against 95.1% for the shape the model trained on — easier, not harder.

One mechanism explains both, and the feature-group ablation independently: this benchmark encodes
fraud as **bursts** — several transactions in a short window, usually to a counterparty new to the
account. No fraud parameter is keyed by country
(`test_no_fraud_parameter_is_keyed_by_country`, PB-59), so a country hold-out withholds no
mechanism. The novel sub-variant changes *when* the drain happens, not *what* it looks like, so a
burst detector catches it regardless. Four disjoint feature groups each reach AUC ≥ 0.845 **alone**
(PB-60), because each is a different view of the same burst.

Two decisions follow from this, and they are not the same decision.

## Decision 1: leave-one-country-out is accepted as a null result, and the generator is not changed

The only way to make LOCO informative is for fraud to differ by country. No fraud parameter does.
Adding one now would require inventing a fact about the world — *how* fraud differs between
Rwanda, Kenya, Tanzania, Uganda and the DRC — and the sourcing pass of 2026-09-18 read the primary
sources available for these markets in full and found none that gives fraud-type prevalence by
country. There is no independent source to ground such a parameter in. The only input available
to choose one would be "whatever makes LOCO show a gap", which defines the ground truth from the
test's desired answer. That is circular by construction, not by carelessness, and no amount of
care in choosing the number changes it.

**PB-59 is closed as a null result, reported as such**, with the mechanism tested
(`test_no_fraud_parameter_is_keyed_by_country`) rather than merely asserted, and the generator is
untouched.

## Decision 2: one pre-registered, typology-grounded, non-burst variant is added

A generalisation test needs a genuinely different mechanism to have a chance of being informative,
and `novel_esim_delayed_drain` was not one — novel in *when*, still a burst. Unlike the country
case, a second mechanism is available here that does not require inventing anything: victim-
initiated social-engineering transfers are a documented mobile-money fraud pattern independent of
this benchmark — the "sent by mistake, please return" reversal scam, where the victim, not an
attacker, initiates a single transfer, often to a counterparty with whom they already have
established contact, because the scam depends on that transfer looking exactly like an ordinary
payment.

### Why this is not the circularity declined for Decision 1

The two cases look similar — both are new generator mechanisms added *after* seeing that the
existing tests came back null — and the difference is not that one was designed with more care.
It is what the mechanism is grounded in.

1. **An independent source exists for this typology and none exists for country-specific fraud.**
   Reversal scams are a documented mobile-money fraud pattern, described the same way regardless
   of what any particular detector relies on. No source describes how SIM-swap or mule-account
   fraud differs between these five countries. The reversal-scam mechanism's defining properties
   — one transaction, victim-initiated, a known counterparty — come from the typology, not from a
   list of "whatever would make this model's existing tests non-trivial".

2. **The overlap with this benchmark's blind spot is not coincidental, and that is worth stating
   rather than hiding.** Burst-structure and counterparty-novelty are the two axes PB-60 and
   PB-61 found doing the work, and this variant removes exactly both. That is not designing a test
   to fail: a social engineer wants precisely one transaction that looks ordinary, to a contact
   the victim already trusts, *because that is what evades detection in the real world too* —
   burst-based and novelty-based detectors are exactly what this fraud type is built to survive.
   The same structural fact explains why the benchmark is blind to it and why real reversal scams
   work. Recognising that connection is not the same act as inventing a country difference with no
   basis beyond "this would make LOCO fail" — one is reading a real mechanism correctly, the other
   has no mechanism to read.

3. **Pre-registration makes the distinction checkable, not just asserted.** The variant's
   definition, its typology justification and the predicted model result are written into
   `docs/research/lab_notebook.md` and committed **before** the dataset is regenerated and before
   any measurement exists. The commit hash freezes the design: the mechanism cannot be adjusted in
   response to seeing whether the model catches it. This is the same discipline PB-56's lead-tail
   prediction used the same day, and the same one this project's evidence-provenance apparatus
   (`fs-evidence`, commit-before-run) enforces for every measurement — provenance taken from the
   system at the moment the work happens, not asserted afterward.

4. **The design does not secretly guarantee the result it might be hoped to produce.** The amount
   and channel are still available to a detector — the mechanism removes burst-structure and
   counterparty-novelty specifically, and states so, rather than claiming invisibility on every
   axis. If the model still catches it easily through amount or timing, that is a genuine, useful,
   reportable result, not a design failure to be patched. The procedure commits to reporting
   whatever the measurement shows, including a positive detection result, without revising the
   variant afterward.

### What would make a future addition circular by this same test

Any generator change whose *justification* is "this would make an existing null result non-null",
rather than an independent source, fails the test in (1) regardless of how carefully it is
implemented. Any change whose defining properties were chosen by checking which axes the current
model relies on and picking the complement — rather than reading them off a real, independently
described mechanism — fails the test in (2) even if a superficial typology citation is attached
after the fact. The ordering in (3) is not decoration: a definition edited after a first
measurement, even if the edit looks like it points the same direction as the original, is a
different and unregistered experiment wearing the pre-registered one's name.

## Consequences

- `fraud.reversal_scam_probability` and `fraud.reversal_scam_amount_multiplier` join
  `fraud.yaml`, both `ASSUMED` with the typology and the sizing rationale (a power calculation
  against the test-period active-customer population, not a result) recorded in place.
- The mechanism is independent of every existing scenario's incident budget (`plan()`,
  `_match_total`) by design, so it adds a small number of rows to the test period without
  disturbing any other scenario's planned row count — verified against the full gate check at
  200,000-row scale before this ADR was accepted.
- `fs-features battery`'s `by_variant` table picks the new `scenario_variant` up with no code
  change, since it groups by whatever variant strings the labels carry.
- Whatever the measurement shows, PB-61's record is updated with it, and this ADR's argument is
  not retroactively strengthened or weakened by the result — that is what pre-registration means.

## Alternatives rejected

- **Make fraud country-specific instead**, so LOCO becomes informative. Rejected as Decision 1
  explains: no source exists to ground it, so the alternative is inventing the result the test is
  supposed to discover.
- **Design a harder novel sub-variant instead of a new scenario.** Rejected for the same reason a
  harder country difference was rejected: "harder" with no independent target is "whatever defeats
  the current model", which is the circularity this ADR exists to avoid.
- **Skip a second generalisation test and rely on the ablation alone.** Rejected: PB-60's
  redundancy finding explains *why* the existing tests are uninformative, but does not itself
  constitute a test that could fail. A benchmark whose every generalisation experiment is designed
  by the same hand that wrote the detectors needs at least one test grounded outside that
  benchmark's own construction to be worth calling a generalisation claim at all.
