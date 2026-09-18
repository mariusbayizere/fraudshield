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

## Areas 1 and 2 — leakage, and determinism across chunk sizes

Area 1's open item is the shortcut-detector diagnosis, running at the time of writing; its
conclusion and the paragraph for the datasheet and defence questions follow when it completes.

Area 2 is not yet reviewed in its own right. The incidental 1M determinism result above is
supporting evidence, not a substitute for it.
