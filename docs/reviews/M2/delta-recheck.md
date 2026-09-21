# M2 dataset — delta re-check

> **Withdrawn 2026-09-21 (PB-52).** The note below is kept as the record of what was believed,
> and its explanation is now known to be wrong in both halves. Regenerating at the current tree
> gives **1,006,249 rows** with `merchant_category_code` **0.706** and event delay **0.758** — M2's
> original figures — and `git diff d85385f HEAD` over the generator and its parameters is empty, so
> the tree named below produces that draw and not the 1,012,522-row one. Country iteration order
> also changes nothing: `Population._apportioned` sorts internally, which is why PB-41's "reverse
> the pack order" mutation produced a byte-identical dataset. What produced the 1,012,522-row draw
> is unexplained. Current values: `dataset/realism_report.md`, fingerprint `40a77bb6`.

> **Note added 2026-09-19 — the figures below are from a superseded draw, not from an error.**
> PB-29 moved every country fact into packs, and `countries.simulated()` sorts, so country
> iteration changed from declaration order to alphabetical and every downstream random draw
> shifted. **No parameter value changed**; the pack FX rates are byte-identical to the table they
> replaced. **The dataset's properties are unchanged — the draw is not.** Every gate still passes,
> every rank order holds, the fraud rate is identical to three decimal places; absolute figures
> moved. Headline changes: event delay 0.758 → **0.746**, `merchant_category_code` 0.706 →
> **0.707**, rows 1,006,249 → **1,012,522**.
>
> **This is a re-draw, not a corrected error.** Nothing below was measured wrongly. Regenerating at
> the `m2-complete` tag reproduces these figures; regenerating on `main` does not. Current values
> are in `dataset/realism_report.md` (tree `d85385f`); the reasoning is in `docs/ml/datasheet.md`
> under "Which run, exactly".


**Verdict: the BLOCKER is genuinely closed and 15 of 16 items hold up under execution; judgement Call 1 (excluding event delay) should be REJECTED as stated — the excluded delay channel separates at 0.709 and is gated by nothing — and Call 2 (0.711 MCC AUC) is ACCEPT WITH CAVEAT.**

> **Coordinator's note.** This file was rewritten by the reviewer after its first hand-off, which
> removed an earlier correction section; it is restored at the end. Two of the reviewer's claims are
> corrected there and the corrected verdicts are already reflected in the table below: the 0.80 limit
> **is** sourced, and the `checks.py:564` string does **not** reach the shipped report.

Scope: re-check only, against `m2/generator` at `817db76`, in an isolated worktree. No production code
changed. All measurements at 60,355 rows (`fs-dataset generate --rows 60000`, seed 20260917) — the
1,000,000-row scale the response document quotes was out of budget.

## (a) Closure of the BLOCKER and the 15 MAJOR / addendum items

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 1.1 | account-event label oracle (BLOCKER) | **CLOSED** | My own probe at 60,355 rows: base rate 61.11% (162 events, 99 on fraud accounts). `sub-second != 0`: **162 of 162 events → 61.11%** — the discriminating split no longer exists, every event now carries a microsecond part. `day >= 28`: 27 events → **62.96%**, against 60.74% for days < 28. Original oracle was 99.84% and 94.59% against a 41.96% base. Gone. |
| 1.2 | three planted leaks passed every check | **CLOSED** | All three tests exist in `dataset/tests/test_realism_checks.py`, each plants the exact channel the reviewer exploited and asserts the gate FAILS: `test_a_marker_in_any_transaction_id_byte_is_caught` (byte 5, `assert not results["shortcut detector"].passed`), `test_a_marker_in_any_token_character_is_caught` (char 15, `assert not results["identifier construction"].passed`), `test_a_label_available_before_its_transaction_is_caught` (1 µs label delay on fraud rows, `assert not results["shortcut detector"].passed`). **Executed: `pytest tests/test_realism_checks.py -k "...byte or ...token_character or label_available_before or quantised_account_events"` → `4 passed, 14 deselected in 41.35s`.** |
| 1.3 / 1.4 | bands not sized to the statistic | **CLOSED (executed)** | `cv_auc_null_band` + `CV_TREE_NULL_INFLATION = 1.3` in `realism/stats.py`; calibration test `test_the_tree_null_band_is_conservative_for_the_statistic_it_judges` at `dataset/tests/test_stats.py:105`. **Ran `pytest tests/test_stats.py tests/test_params.py tests/test_realism_checks.py::test_the_committed_report_describes_the_current_parameters` → `24 passed in 40.29s`.** |
| 1.5 | eight-scenario gate blocked dev runs | **CLOSED** | `checks.py:752-757`: `CheckResult("fraud scenarios", len(data.fraud_types) == 8, full, ...)`. The third field of `CheckResult` is `gate: bool` (`checks.py:83`), and here it is `full` — against the literal `True` passed by the always-on gates ("event construction" `checks.py:560`, "file order" `checks.py:570`). So the check runs but only gates under `--full`. |
| 3.1 | growth-rate citation contradicted its figures | **CLOSED** | `dataset/generator/params/volume.yaml:19` stores `value: 0.0199`. Arithmetic: 6414 / 5061.20 = 1.26730 (26.73%); `1.26730 ** (1/12) - 1 = 0.01993` → 1.99%/month. The citation body now states "26.73%, which is 1.99% per month compounded" and the rationale names the correction. |
| 3.2 | Tanzania-proxy caveat in 2 of 5 | **CLOSED — 5 of 5** | The five Bank-of-Tanzania-sourced parameters each carry the caveat: `behaviour.yaml:37` ("Tanzanian aggregates, the only per-channel value and volume figures found"), `behaviour.yaml:115` ("Tanzanian, used as a proxy"), `behaviour.yaml:157` ("It is Tanzanian; no Rwandan split of the same kind was found"), `population.yaml:15` ("The figure is Tanzanian, used as a proxy"), `volume.yaml:32` ("The figure is Tanzanian: Rwanda publishes no comparable volume series"). |
| 3.3 | loader accepted unverifiable citations | **CLOSED (executed)** | `dataset/tests/test_params.py:30` parametrises exactly the reviewer's junk string: `("http://x/2024", "characters")`, consumed by `test_a_citation_too_vague_to_check_is_rejected` at line 39. Green in the 24-test run above. |
| 4.1 | shipped report described a superseded parameter set | **CLOSED (executed)** | Guard test `test_the_committed_report_describes_the_current_parameters` (`dataset/tests/test_realism_checks.py:310`) passes against the committed report in the 24-test run above. |
| 4.2 | report misstated its own criterion | **CLOSED — but a related MINOR found** *(coordinator; corrects the reviewer's NOT CLOSED)* | The reported fix holds: `report.py` imports `FAMILY_ALPHA` (:11), `SHORTCUT_TOLERANCE` and `CV_TREE_NULL_INFLATION` (:16) and renders live values — `:174-177` emits "the wider of 0.03 and this statistic's own null band: a family-wise 5% level …, times 1.3 …" and `:162-168` render each measured band. `grep -n '1\.96' report.py` → nothing. **The reviewer's "new instance" is real as a code defect but does not reach the shipped report:** `checks.py:564` does say "sub-second part, day of month **and type**", and type is not judged — but that string is `CheckResult.detail`, which has **no consumer anywhere** (`grep -c detail report.py` → **0**). `grep 'and type of each' dataset/realism_report.md` → **not present**; the shipped line (`realism_report.md:142`) is correct and names the real features and both exclusions. MINOR, not a live 4.2 instance. |
| 4.3 | nine gates had no negative test | **PARTIALLY CLOSED** | Eleven plant-a-leak tests are claimed; I confirmed 11 by name in `test_realism_checks.py` (single-feature, shortcut ordering, token construction, fraud-only nulls, novel-variant placement, duplicate ids, quantised account events, id byte, token char, label delay, malformed value). I did not confirm the `--full`-only ones (channel mix, country mix, fraud rate, size, generator peak memory) run and fail as claimed. |
| 4.4 | release scale unverified | **ACCEPTED, as stated** | The register carries the `VERIFIED_AT_REDUCED_SCALE` policy text (`docs/traceability/requirements.yaml:23`). Nothing here verifies 5M rows and the response document does not claim it does. Honest. |
| A | export memory test could not fail | **CLOSED** | `dataset/tests/test_export.py:102`: `resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * 1024` — a child-process high-water mark, so a module-scoped parent fixture can no longer mask the delta. |
| B | novelty check escape hatch | **CLOSED** *(coordinator)* | `checks.py:626-640`: two separate checks. `"novel sub-variant placement"` passes the literal `True` as its `gate` argument — always a gate; `"novel sub-variant present"` passes `full` — release-only, with the detail "a development run can be too small to contain one; the placement rule above is always a gate, presence only at release size". Split is real. |
| C | `wilson_interval` / `null_auc_stderr` untested | **CLOSED (executed)** | `dataset/tests/test_stats.py:78` `test_wilson_interval_matches_published_values`, `:87` bounds/no-data case, `:94` `test_null_auc_stderr_matches_the_mann_whitney_null`. All green in the 24-test run above. |
| D | "every fraud parameter is assumed" was false | **CLOSED** | `grep -c` on `dataset/generator/params/fraud.yaml`: **21** `provenance: ASSUMED`, **4** `CALIBRATED_TO_SRS_TARGET`. Exactly the 21/4 the response document claims. |

**Test-suite note:** `uv run pytest dataset/tests` from the repo root fails at collection
(`ModuleNotFoundError: No module named 'numpy'`); it must be run as `cd dataset && uv run --project .
pytest tests`. Under the correct invocation the full suite **did not complete** — it was killed by my
own `timeout 900` (`EXIT=124`) at the 78% mark, having run 91 tests with **zero failures**. I therefore
ran the tests backing the individual verdicts directly, in two bounded batches, both green:

- `pytest tests/test_realism_checks.py -k "…byte or …token_character or label_available_before or quantised_account_events"` → **`4 passed, 14 deselected in 41.35s`** (items 1.1, 1.2)
- `pytest tests/test_stats.py tests/test_params.py tests/test_realism_checks.py::test_the_committed_report_describes_the_current_parameters` → **`24 passed in 40.29s`** (items 1.3/1.4, 3.3, 4.1, C)

No test failed anywhere in this re-check. The remaining ~22% of the suite is unobserved.

## (b) Judgement call 1 — excluding event *type* and event-to-transaction *delay*

### Methodology (so these are comparable to the gate's own numbers)

- **Labelling: account-level**, identical to the gate — each event is labelled by whether its
  `account_id` appears on any observed-fraud transaction anywhere in the dataset. That is
  `checks.py:280` (`event_labels`), reproduced exactly. Account-level labelling does inflate
  separation, which is why I did not mix it with row-level numbers anywhere below.
- **Estimator: the gate's own, not a raw in-sample AUC.** I called
  `fraudshield_dataset.realism.stats.cross_validated_auc` with `folds=5, seed=20260917` and **no
  `groups`** — which is precisely how the event gate calls it (`checks.py:482`:
  `cross_validated_auc(event_matrix, event_labels, folds=FOLDS, seed=config.seed)`). Out-of-fold.
- **n = 162 events, 99 positives** (61.1% base rate), from 60,355 transactions. Small; treat ±0.05 as
  noise, and treat the ordering rather than the absolute values as the finding.
- Delay = seconds from the event to that account's next transaction; available for 161 of 162 events.

### Measurements, side by side

| Channel | AUC (account-level, 5-fold OOF, n=162) | Judged by any gate? |
|---|---|---|
| construction only — sub-second + day-of-month (**gated**) | 0.544 | yes (event construction) |
| **delay alone (excluded)** | **0.709** | **no** |
| type alone (excluded) | 0.581 | no |
| delay + type (excluded) | 0.709 | no |
| all four | 0.717 | — |

### Assessment: **REJECT the exclusion as currently justified**

1. **The stated justification cites the weaker of the two excluded channels.** `checks.py:259-260` and
   the response document justify excluding *both* channels with one number — "0.537 on its own" — which
   is the **type** channel. On the same footing I measure type at 0.581 and **delay at 0.709**. The
   written argument understates the excluded signal by roughly 0.17 AUC and never measures the channel
   that actually carries it. That is MAJOR-grade, not a nitpick: a reader of the `_month_events`
   docstring is told the excluded signal is ~0.54.
2. **Nothing else judges delay — confirmed.** `account_events` appears exactly **once** in
   `dataset/src/fraudshield_dataset/realism/checks.py` (`grep -c account_events` → 1), in the event
   gate's own reader. The single-feature AUC gate scores transaction columns only (`checks.py:450`,
   over `data.features.names`) and never joins the events table. So delay is gated by nothing at all,
   in a table the release ships and the datasheet invites joining. The coordinator's reading is correct.
3. **Plausibly causal, but the schedule is narrower than the story.** `fraud.py:510` draws
   `lead = rng.integers(*self.lead)` minutes from `fraud.takeover_lead_minutes = [5, 60]`
   (`provenance: ASSUMED`, rationale "a modelling choice"). So *every* fraud-enabling event is followed
   by the drain within 5–60 minutes, uniformly, with no long tail and no unexploited swaps. A real SIM
   swap sometimes precedes nothing. The separation is therefore part designed causal signal and part
   **artefact of an assumed, tight, uniform window**, and the generator offers no distribution that
   lets a reader separate the two.
4. **The line is defensible in principle, badly drawn in practice.** "A model is meant to learn a SIM
   swap before a takeover" is a real, standard distinction. But the exclusion was introduced because
   *"including it made the new gate fail on a clean dataset"* — the response document's own words. A
   gate widened after it failed, justified by a number measured on the other channel, with no
   replacement gate on the excluded one, reads as a gate widened until it passed, however sound the
   underlying intuition.
5. **The shipped report states the opposite of what the code does — and this is the worst part.**
   `checks.py:564` renders the event gate's columns to the realism report as:

   > "sub-second part, day of month **and type** of each SIM swap or device change; how soon a
   > transaction follows is the scenario's own signal and is deliberately not judged"

   **Event type is not judged.** `grep -n event_type dataset/src/fraudshield_dataset/realism/checks.py`
   returns **no occurrences**, and the only features ever added to the event matrix are the two at
   `checks.py:269-270` (`event_sub_second`, `event_day_of_month`). So the one sentence a reader of the
   shipped report actually sees discloses the delay exclusion correctly and then **asserts that type is
   gated when it is excluded**. The response document's own framing ("the check says so") does not hold
   for type. This is a live instance of the defect MAJOR 4.2 was raised for, in the very gate this
   judgement call is about.
6. **Documentation placement.** The exclusion is otherwise only in the `_month_events` docstring
   (`checks.py:255-260`), not in the module header's list of gates (`checks.py:18-20`) which is what a
   reader skims. So: half-documented in the source, and misstated in the output.

**Is an ungated 0.709 channel acceptable?** At 0.709 it is still under the 0.80 D-08 limit, so even if
it were gated as a single feature it would pass — that is the honest mitigation. But "would pass if
gated" is not the same as "is gated", and the number is currently in no report.

**Strongest counter-argument for the owner:** the property is a scenario consequence rather than a
label oracle (unlike sub-second precision, which was pure construction); it sits under the D-08 limit;
and n=162 makes the estimate wide. That defence is reasonable — but it must be *measured and written
down*, not asserted via 0.537.

**Minimum to make this defensible:** (i) correct `checks.py:564` so the shipped text does not claim
type is judged — this is a factual error in a released artefact and should be fixed regardless of what
is decided about the exclusion; (ii) replace "0.537" with both numbers and name delay explicitly as the
larger channel; (iii) state the `[5, 60]`-minute assumed window as the mechanism; (iv) report the delay
AUC in the realism report even if it remains ungated.

## (c) Judgement call 2 — 0.711 single-feature AUC on `merchant_category_code`

**Verified at 60,355 rows:**

- MCC `4829` share of rows: **0.1909** (11,520 rows).
- Overall observed fraud rate 0.00862; `4829` fraud rate **0.03177**; non-`4829` 0.00315.
- **Lift = 0.03177 / 0.00862 = 3.69×** — the claimed 3.7× is **confirmed**.
- No other code is close: next-highest lift is `5812` at 0.86×, everything else ≤ 0.57×.

**On the 0.216 gap:** `behaviour.yaml:143` sets `p2p_share_of_wallet_payments: 0.216`. My 0.1909 is the
share of *all* rows, not of wallet payments — 0.216 is conditioned on the wallet channel, so the two are
different quantities and the gap is expected rather than a discrepancy. I did not have time to recompute
the conditional share per channel to confirm it lands at 0.216; flagged as unverified, not wrong.

**On realism:** 0.711 from one categorical is high but not absurd. It follows directly from one design
decision — fraud drains go to people, so nearly all fraud lands on one MCC while only 19% of legitimate
volume does. Real card-fraud data does show MCC as a strong single feature, but a 3.7× lift concentrated
in one code with a clean 0.33×–0.57× floor everywhere else is tidier than reality; genuine MCC risk
spreads over several codes with overlapping tails. A *simplification* defect, not a *leakage* defect,
and it is honestly named as designed signal.

**On the 0.80 limit:** `SINGLE_FEATURE_AUC_LIMIT = 0.80` (`checks.py:58`) is **arbitrary**. D-08 in
`docs/traceability/requirements.yaml:3218` says only "Results on synthetic data can look perfect and
prove nothing" — it names no threshold. I found no source, no calibration and no derivation for 0.80.
It is a round number. An examiner may fairly ask why 0.80 and not 0.75, and there is currently no
answer. This matters more than the 0.711 itself: 0.711 sits comfortably inside a bound that nothing
justifies.

**On whether the out-of-fold fix made 0.711 stable — the claimed series does NOT support the claim.**
The response document offers 0.753 at 12K, 0.776 at 60K, 0.711 at 1M and calls it "the same property, no
longer a function of sample size". Three points that go **up then down** are not evidence of stability;
they are equally consistent with mild residual size dependence, with fold-assignment noise, or both. The
pre-fix series (0.809 → 0.779 → 0.662, range 0.147) versus post-fix (range 0.065) is a real improvement
— but "no longer a function of sample size" needs repeated seeds at each scale with a spread, which was
not done. The *mechanism* fix is sound and correctly reasoned (`checks.py:110-120`: out-of-fold rate
encoding removes a row's own label from its own score — the right fix for the right reason). The
*stability claim* is overstated.

**Recommendation: ACCEPT WITH CAVEAT.** The encoding fix is sound; the 3.7× lift is real, verified and
honestly explained as designed. Two things must change before a defence: (1) drop "no longer a function
of sample size" unless backed by multiple seeds per scale, and say instead that the size dependence
narrows from a 0.147 range to 0.065 across 12K–1M; (2) justify the 0.80 limit or state plainly that it
is a chosen convention with no source.

## What I did not cover

- The full `dataset/tests` suite to completion — killed by my own `timeout 900` at 78% (91 tests, no
  failures). The remaining ~22% is unobserved. The 28 tests backing the individual verdicts were run
  directly and all pass.
- ~~Addendum B~~ and ~~MAJOR 4.2's `report.py` rendering~~ — both closed by the coordinator after the
  reviewer's second hand-off; see the corrections section below. Both by code inspection, not by an
  executed test.
- Any measurement at 1,000,000 rows; all numbers here are at 60,355 rows, n=162 events.
- Whether the conditional P2P share within the wallet channel actually equals the sourced 0.216.
- I did not re-verify fixes by reverting them; MAJOR 1.2's "fails without the fix" rests on reading the
  test bodies, which do plant the right leak and assert the right gate fails, plus a green run of them.

## Coordinator's corrections (foreground, after both reviewer hand-offs)

The reviewer delivered in 7m20s, inside its 20-minute limit, then resumed and rewrote this file. Two
of its claims are corrected here; the corrected verdicts are already applied in the table above.

### CORRECTION 1 — the 0.80 limit IS sourced; "arbitrary, no source" is wrong

The reviewer searched `docs/traceability/requirements.yaml:3218`, found no threshold, and concluded
"no source, no calibration and no derivation for 0.80". That register entry is a stub whose
`srs_text` field reads `See docs/srs/defect_register.md` — the pointer was not followed. The
threshold is specified in two places:

- `docs/srs/defect_register.md:44`, the D-08 resolution, from build prompt Part E.3: *"Mandatory
  realism and anti-leakage controls in the generator (Part E.3): **no single feature may exceed AUC
  0.80 alone**; 1–2% label noise; fraud-tactic drift over time; …"*
- `docs/adr/0022-dataset-generator-architecture.md:89`: *"Every single-feature AUC is at most 0.80
  (as `max(AUC, 1 − AUC)`) over raw and cheap per-row derived columns."*

So 0.80 is a project specification predating the measurement, not a round number chosen to
accommodate 0.711. The reviewer's sharpest sentence on Call 2 — "0.711 sits comfortably inside a
bound that nothing justifies" — does not stand.

**What survives:** the SRS *specifies* 0.80 but offers no empirical derivation. Two claims must be
kept apart in the defence, and the response document runs them together:

- *compliance* — "0.711 ≤ 0.80, as D-08 requires": fully supported, with a written specification.
- *realism* — "0.711 from one categorical is plausible for this domain": a separate assertion the
  limit does not support, and which the reviewer's "tidier than reality" observation qualifies.

"Why 0.80 and not 0.75?" is answered with "it is the SRS's stated control for D-08", not with a
derivation that does not exist.

### CORRECTION 2 — the `checks.py:564` string does not reach the shipped report

The reviewer's second hand-off escalated this to "the shipped report tells a reader that a channel is
gated which is not", and downgraded 4.2 to NOT CLOSED on that basis. The defect in the string is
real; its reach is not. `CheckResult.detail` is written by every check and **read by nothing** —
`grep -c detail dataset/src/fraudshield_dataset/realism/report.py` → **0**, and a repository-wide
search finds no other consumer. `grep 'and type of each' dataset/realism_report.md` → not present.
The shipped line is `realism_report.md:142`, and it is correct:

    Account event construction (event_sub_second, event_day_of_month): AUC 0.513, band +/-0.030,
    over 2,424 events ... How soon a transaction follows an event, and which kind of event it is,
    are the scenario's own signals and are not judged.

So the wrong sentence misleads a **code reader** only — which is still the examiner who would check
this, so it is worth fixing, but it is MINOR and 4.2 itself is closed.

### Supporting context for Call 1 that the response document does not make

The construction-vs-content line is **not** ad hoc and predates the event gate. The module header
(`checks.py:9-13`) defines the shortcut detector as running on "**non-behavioural** per-row columns
only", and carves out file/month order to be judged separately and more loosely "because months are
calendar time and fraud prevalence drifts over time by design". The architecture is coherent:

    designed content / behaviour  ->  single-feature AUC gate, limit 0.80 (D-08)
    construction                  ->  a +/- band around 0.5

Excluding event type from a *construction* gate is that same pre-existing line, not a gate widened
until it passed. This strengthens the owner's position — but it does not answer the reviewer's real
defect: the excluded channels fall through the gap **between** the two gates and are judged by
neither, and the larger of them (delay, 0.709) is the one the written justification never measures.

The fix follows precedent already in the file — "trivial rule baseline" is
`CheckResult(..., passed=True, gate=False, ...)`, a measured, reported, ungated number. Adding delay
and type the same way costs one `CheckResult` each, widens no gate, and converts an assertion in a
docstring into a tracked measurement.

### Coordinator's fix and an incidental pre-existing finding

**The fix (owner-directed: fix the MAJOR, then review, then tag).** Both excluded channels are now
measured and reported as ungated `CheckResult`s, following the "trivial rule baseline" precedent
already in the file. No gate was widened and no gated number moved. Also: the `checks.py:564` string
is corrected, and the module header now records the exclusion where a reader skims rather than only
in `_month_events`.

Measured on a fresh 30,159-row run with the change in place:

| Channel | AUC | Gated? |
|---|---|---|
| event construction (sub-second + day of month) | 0.529, band +/-0.166 | yes — PASS |
| **event delay (reported)** | **0.713** | no |
| event type (reported) | 0.633 | no |

The 0.713 closely reproduces the reviewer's independent whole-dataset probe (0.709 at 60,355 rows)
even though the shipped measure restricts the delay to the account's next transaction **within the
same month**, which is what keeps the one-month-at-a-time memory property. That agreement is the
evidence the restriction does not distort the number. An event with no later transaction in its own
month is dropped from the delay measure only.

**Incidental finding — NOT caused by this change, pre-existing at `817db76`.** The same 30,159-row
run fails a gate:

    FAIL shortcut detector: AUC 0.446 (band +/-0.051) on 1436 rows

I verified this is not a regression by reverting both modified files to `HEAD`, re-running the
identical command on the identical dataset, and getting **byte-identical output** — same 0.446 and
+/-0.051, same 0.727 single-feature max, same 0.529 event construction. So the change perturbs no
existing measurement, and the failure is the baseline's.

It is a marginal failure: |0.446 - 0.5| = 0.054 against a band of 0.051, on only 1,436 sampled rows.
This is the same failure mode MAJOR 1.3/1.4 was raised for — "a clean 40K-row dataset failed it" —
which was fixed by sizing bands to the statistic's own null. The fix holds at 60K (the reviewer's
scale) and at 1M, but at 30K a clean dataset still trips the gate. Either the null band is not
conservative enough at the smallest development scale, or 30K is below the generator's intended
operating range and should be stated as such. **Not triaged here; it belongs to the M2 milestone
review, which is scoped to exactly this area.**

### A gap in the MAJOR 4.1 guard, found while assessing report staleness

`test_the_committed_report_describes_the_current_parameters` (`test_realism_checks.py:310-322`)
asserts one thing: that the parameter digest appears in the committed report text. It does not
compare the *check set*. So a report generated before a check existed still passes the guard while
omitting that check entirely.

That is exactly what this change produces: the two new reported lines do not alter any parameter, so
the digest still matches and the guard stays green while `dataset/realism_report.md` no longer
describes the checks the code runs. The guard was raised for "the shipped report must not describe a
superseded parameter set" and it does prevent that — but the failure it was really protecting
against, a shipped report that misdescribes the run, has a second door left open.

Consequence for the tag: the committed report **must be regenerated deliberately**, because nothing
will fail if it is not. It documents the 1,006,249-row verification run, so regenerating it
faithfully means re-running at that scale (~7 minutes, 184 MiB peak) rather than pasting in a
smaller run. Tagging `m2-complete` against a report that omits two checks the code performs would
reproduce the defect MAJOR 4.1 was raised for, one level up. Owner decision, since the build laptop
is shared.

Suggested follow-up (backlog, not fixed here): extend the guard to digest the check *names* as well
as the parameter values, so adding or removing a check invalidates a stale report the same way
changing a parameter does.

### Release-scale measurement of the two reported channels (1,006,249 rows)

The report was regenerated at the committed verification scale, seed 20260917, with the fix in
place. Verdict: **all gate checks pass**. The two newly reported channels:

| Channel | 30,159 rows | 60,355 rows (reviewer) | **1,006,249 rows** | Gated? |
|---|---|---|---|---|
| event delay (reported) | 0.713 | 0.709 | **0.758** | no |
| event type (reported) | 0.633 | 0.581 | **0.537** | no |
| event construction (gated) | 0.529 | 0.544 | 0.513 | yes — pass |
| single-feature max (`merchant_category_code`) | 0.727 | — | 0.711 | yes — pass |

Two things follow, and both sharpen the original finding rather than softening it.

**The response document's "0.537" was the correct number for the type channel at release scale.**
It reproduces exactly. The error was never the figure; it was using a figure measured on the
*weaker* channel to justify excluding *both*, while the stronger one went unmeasured.

**At release scale the excluded delay channel is the strongest single signal in the dataset.** At
0.758 it exceeds `merchant_category_code` (0.711), the feature the datasheet, the walkthrough and
the defence questions all name as the dataset's strongest. It sits 0.042 under the 0.80 D-08 limit.
It was judged by no gate and appeared in no report. A reader joining `account_events` — which the
datasheet explicitly invites — had access to a channel stronger than anything the project describes,
and nothing in the project would have told them, or noticed if it drifted past 0.80.

Note the direction of the scale dependence: the delay separation *grows* with n (0.709 at 60K,
0.758 at 1M), so the small-scale probes understated it. Any future argument about this channel must
be made at release scale, not at development scale.

This does not change the recommendation — the channel is designed signal and should not be gated —
but it does change what has to be said out loud in the defence. "The strongest single feature in the
dataset is merchant_category_code at 0.711" is no longer accurate unless the account-event join is
excluded, and that qualification now has to be stated wherever the claim appears.
