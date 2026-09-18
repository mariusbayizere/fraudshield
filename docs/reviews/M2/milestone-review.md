# M2 milestone review

Scope set by the owner: leakage and shortcut detection; determinism across chunk sizes; provenance
honesty; whether realism claims are backed by tests. Fix BLOCKER and MAJOR only, log the rest.

This is not the dataset review (`dataset-response.md`) nor the delta re-check (`delta-recheck.md`).
It does not re-litigate their findings.

Reviewed at `d321e58` on `m2/generator`. Local suite at the time of writing: **94 passed, 97.24%
coverage, over commit `88f73b7` with a clean tree** — the three commits since are documentation.

## Area 4 — are the realism claims backed by tests?

### MAJOR M-1 — the datasheet's measured figures match no run the repository can produce

`docs/ml/datasheet.md` is the Gebru-template document that ships in the release; for an external
user it is the primary description of the data. It states, in *Composition*:

> The verification run documented in `dataset/realism_report.md` holds **1,006,212** transactions,
> generated in **13 minutes** at 184 MiB peak resident memory; **the figures in this datasheet come
> from that run** unless stated otherwise.

They do not. Every figure below is from the datasheet's own temporal-split table, against the
report it names:

| Split | Datasheet | `realism_report.md` | Difference |
|---|---:|---:|---:|
| train rows | 792,339 | 792,162 | +177 |
| validation rows | 100,909 | 101,332 | −423 |
| calibration rows | 40,317 | 40,701 | −384 |
| embargo rows | 10,940 | 10,914 | +26 |
| test rows | 102,024 | 101,841 | +183 |
| train fraud rate | 0.861% | 0.862% | −0.001 pp |
| validation fraud rate | 0.893% | 0.883% | +0.010 pp |
| **embargo fraud rate** | **0.887%** | **1.008%** | **−0.121 pp** |
| test fraud rate | 0.915% | 0.905% | +0.010 pp |
| total rows | 1,006,212 | 1,006,249 | −37 |
| label noise, missed | 1.50% | 1.54% | −0.04 pp |
| generation time | 13 minutes | 5 min 53 s measured | — |

**This is not drift introduced by the recent work.** The report was regenerated at `1e5d441`, and
the pre-regeneration report committed at `817db76` carries an *identical* split table — 792,162 /
101,332 / 40,701 / 10,914 / 101,841 in both. So the datasheet matches neither the current report nor
the previous one. Its numbers come from a generator that no longer exists, and it has been in that
state throughout the dataset review, the reviewer's addendum and the delta re-check. Nobody caught
it because nothing looks.

**Why nothing caught it.** MAJOR 4.1 introduced `parameter_digest`, and the delta re-check found and
closed a second hole with `check_digest` (`1e5d441`). Both guard exactly one file:
`dataset/realism_report.md`. No guard covers the datasheet, the walkthrough, or the defence
questions, although all three quote measured figures and all three ship or are defended. The
guard's reach stops one document short of the one an external user reads.

**Severity.** MAJOR, not BLOCKER: the discrepancies are small in magnitude and none of them changes
a gate outcome or a conclusion. It is MAJOR because it is a *correctness-of-record* failure in the
release's primary description, of exactly the class MAJOR 4.1 was raised for, and because "the
figures come from that run" is a statement a reader is entitled to rely on.

**Recommended fix** (not applied; owner's call, and it needs the same 1M run):
1. Regenerate the datasheet's measured figures from the committed report rather than by hand, or
   derive them from `measures` at report time so the two cannot diverge.
2. Extend the digest guard to every document that quotes measured figures. The cheapest version
   that would have caught this: assert the datasheet's split table is a substring of, or is
   generated from, the committed report's.
3. Correct the generation-time claim — 13 minutes is not what the generator does; the measured
   figure at this scale is under 6 minutes, and the walkthrough separately says 7.

### Confirmed sound in area 4

- The **realism report itself** is generated, never hand-written, and now carries both a parameter
  digest and a check-set digest, each with a guard test.
- The **walkthrough's** results table was corrected in `d321e58` and now matches the regenerated
  report, including the two newly reported channels.
- **Determinism at 1M is confirmed incidentally and strongly**: regenerating at seed 20260917 after
  the event-measurement change reproduced the previous report's split table exactly, to the row.

## Area 3 — provenance honesty

Audited by influence rather than exhaustively: the ranking in `parameter_influence.json` was used to
pick the parameters whose correctness matters most, and each `SOURCED` value among them was checked
by recomputing it from the figures its own citation quotes.

| Parameter | Influence rank | Check | Result |
|---|---|---|---|
| `population.mean_transactions_per_active_customer_month` = 8.8 | 2nd | 6,414,000,000 / 60,745,698 / 12 | **8.80** ✓ |
| `behaviour.p2p_share_of_wallet_payments` = 0.216 | — | 479,107,425 / (479,107,425 + 1,736,150,000) | **0.2163** ✓ |
| `population.urban_share_of_customers` = 0.32 | coupled | 0.279×0.87 / (0.279×0.87 + 0.721×0.72) | **0.3186 → 32%** ✓ |
| `volume.monthly_growth_rate` = 0.0199 | — | (6414/5061.20)^(1/12) − 1 | **0.01993** ✓ (re-check) |

No arithmetic error found in the sampled `SOURCED` values, and each rationale states how the value
was derived rather than asserting it. Three specific honesty properties hold:

- **The Tanzanian proxy is disclosed at the point of use**, in all five affected citations, not only
  in the sourcing pass — confirmed by the delta re-check.
- **The most influential sourced parameter states its own limits.** `mean_transactions_per_active_
  customer_month` says plainly that the figure is Tanzanian, that Rwanda publishes no comparable
  series, and that it covers mobile money alone while the SRS mix puts 41% of rows on other
  channels, so it "anchors the order of magnitude rather than measuring this dataset's own mix".
  That is the right register for a proxy.
- **A derived value is labelled as derived.** `urban_share_of_customers` is not the census's 27.9%;
  it is the census weighted by FinScope usage, the rationale shows the arithmetic, and the generator
  refuses to run if the assumed segment split stops summing to it — so the sourced fact constrains
  the assumed one rather than sitting decoratively beside it.

### MINOR M-2 — "SOURCED" conflates a read source with a derived quantity

`urban_share_of_customers` and `p2p_share_of_wallet_payments` are both marked `SOURCED`, but neither
value appears in any source: both are quantities the author computed from figures that do. The
computation is shown and correct in each case, so nothing is hidden — but a reader filtering for
`provenance: SOURCED` to find "numbers a document states" will get numbers no document states.
`cross_border_share` = 0.0046 is the same shape.

Logged, not fixed: a `DERIVED_FROM_SOURCE` provenance would be more honest, but adding a provenance
value touches the loader, its tests, the provenance renderer and 81 parameter blocks, which is more
than a MINOR warrants during a milestone close.

## Area 2 — determinism across chunk sizes

Two tests carry this, and both are honest ones.

`test_same_seed_is_byte_identical_across_chunk_sizes` generates at chunk sizes 1 and 64 against a
fixture built at 8, and compares **every file byte-for-byte** plus `manifest.json`. Byte equality
rather than row-count or checksum equality is the right assertion: it cannot be satisfied by a
coincidence.

`test_...across_a_parquet_row_group_boundary` addresses the reviewer's "could not verify" on area 2
by forcing `ROW_GROUP_SIZE` to 64 so a 6,000-row month spans several groups. It contains the line
that makes it worth having:

    assert groups > 1, "the boundary this test exists for was not reached"

A determinism test that silently stops reaching the condition it was written for is the failure mode
that produced MAJOR 4.3 elsewhere in this milestone; this one refuses to pass vacuously. Good.

**Incidental confirmation at release scale.** Regenerating at 1,006,249 rows and seed 20260917,
after the event-measurement change, reproduced the previously committed report's temporal-split
table exactly — 792,162 / 101,332 / 40,701 / 10,914 / 101,841 rows, and every rate. That is
determinism across a *code change* at release scale, which no test covers.

### MINOR M-3 — determinism across chunk sizes is only ever tested at 6,000 rows

Both tests run at 6,000 rows. The property is asserted where it is cheap and assumed where it is
expensive: at release scale, chunk-size independence is untested. The row-group test mitigates the
specific reason scale was thought to matter, by forcing the boundary rather than waiting for volume
to produce it, which is a sound substitution and is documented as such.

It stays MINOR rather than MAJOR because PB-28 already records that `combine_chunks()` — the call
whose job is chunk-independence — is dead code that no mutation catches, so the remaining risk is
logged rather than unknown. Worth folding into the release-size run (PB-25) when it happens: that
run should be done twice at different chunk sizes and the outputs compared, which costs one extra
run and closes the property at the scale that ships.

## Area 1 — leakage and shortcut detection

The shortcut-detector diagnosis is running at the time of writing; its conclusion and the paragraph
for the datasheet and the defence questions follow when it completes. Three findings are already
established and do not depend on the remaining scales.

### MAJOR M-4 — below about 20,000 rows a dataset silently lacks a fraud scenario

`FraudModel._validate_scenario_capacity` skips a scenario whose role pool is empty:

    if not intended or not capacity:
        continue

Measured directly, building the population and the fraud model at each scale:

| rows | seed | mule pool | intended rows | stageable | outcome |
|---:|---:|---:|---:|---:|---|
| 10,000 | 20260917 | **0** | 10 | 0 | runs; `mule_account` absent |
| 10,000 | 20260922 | **0** | 10 | 0 | runs; `mule_account` absent |
| 20,000 | 20260922 | **0** | 21 | 0 | runs; `mule_account` absent |
| 20,000 | 20260917 | 22 | 21 | 264 | runs, scenario present |
| 30,000 | 20260917 | 27 | 32 | 324 | runs, scenario present |
| 30,000 | 20260922 | ~2 | 32 | **24** | **refused** |

So a development run below roughly 20,000 rows produces a dataset missing one of the eight fraud
scenarios, with no error and no warning. The check that would report it is the eight-scenario gate,
which MAJOR 1.5 correctly made `--full`-only so that it stops blocking development runs — with the
side effect that at development scale **nothing reports the absence at all**. The two changes are
individually right and jointly leave a hole.

This is MAJOR because development runs are what every test and every quick check uses, and a
silently absent scenario is a difference in kind, not degree: such a dataset is not a small release
dataset, it is a different dataset.

**Consequence for this milestone's own evidence.** The diagnosis's 10,000-row tier was measured on
datasets that structurally lack `mule_account`. Those runs still answer "is the detector centred,
and how powerful is it at that n", but they cannot be read as a release dataset shrunk, and the
write-up must say so rather than let a convergence curve imply a uniformity that is not there.

**Recommended fix:** report the absence at development scale instead of staying silent — either a
report-only `CheckResult` naming the missing scenarios (the "trivial rule baseline" pattern), or a
warning from the generator. Do not make it a gate; that is what MAJOR 1.5 removed for good reason.

### MINOR M-5 — a size limitation is reported as a parameter error

When the pool is small but non-zero the guard raises:

    scenario mule_account is asked for 32 rows but its role holders could stage at most 24;
    raise its role fraction or lower its share

At 30,000 rows with seed 20260922 the parameters are correct and the population is simply too small.
The message directs the operator to change a sourced or calibrated share to work around a scale
limitation, which is the wrong repair. The guard's own docstring already draws the right
distinction — "A scenario with no role holder at all is a different matter: the population is simply
too small… That is a size limitation, not a parameter error" — and then applies it only to the
zero case.

### MINOR M-6 — the capacity guard's docstring claims to be scale-free, and is not

> The test is scale-free: the rows the share asks for over the simulation must not exceed the rows
> the eligible customers could stage even if every one of them were a victim of a maximum-length
> incident every month.

The outcome depends on both scale and seed. Seed 20260922 has no mules at 20,000 rows, about two at
30,000, and seed 20260917 has twenty-seven at the same 30,000 — a thirteenfold spread in pool size
at one scale. The ratio the guard tests is scale-free in expectation; the integer pool it tests it
against is not, and at small n the variance dominates.

### Established about the detector itself (interim)

At 10,000 and 30,000 rows, 15 seeds each, clean mean AUC is 0.500 and 0.508 — centred, with no
systematic offset from 0.5. The 0.446 that prompted this diagnosis is 1.77 standard deviations below
the 30,000-row mean: a tail draw, not evidence that the generator leaks.
