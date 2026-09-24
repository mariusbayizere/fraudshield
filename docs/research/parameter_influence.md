# Which generator parameters matter, and how that was decided

The dataset has 80 generator parameters. Deciding which of them deserve the effort of finding and
reading a primary source is a question about influence, and influence is measurable here rather
than a matter of opinion. This document records the method; `dataset/params_provenance.md` records
what each parameter is and where its value comes from.

Owner direction, 2026-09-18: rank every parameter by influence on the dataset's headline
properties, record the ranking method, then source the top 15.

## Method

Implemented in `dataset/src/fraudshield_dataset/sensitivity.py`, run with:

```console
$ uv run python -m fraudshield_dataset.sensitivity --rows 20000 --output ranking.json
```

1. **Baseline.** Generate a dataset at the given size with the repository parameters and one fixed
   seed (20260917).
2. **One-at-a-time perturbation.** For each parameter, raise its value by 10%: numbers are scaled,
   integers move by at least one, and every entry of a list or mapping is scaled. Booleans and
   strings are reported as *not perturbable* rather than as uninfluential — the ranking never
   claims that a parameter it could not move has no effect.
3. **Regenerate** with that single change, same seed and size.
4. **Compare** against the baseline over the headline properties: row count, mean and median
   amount, the channel mix, the country mix (by currency), the fraud rate, the monthly
   distribution, and the number of distinct accounts, counterparties and agents.
5. **Score.** The score is the largest change any property shows, expressed in units of the
   tolerance that property is held to: 0.5 percentage points for a share (the SRS 7.1 mix
   tolerance) and 1% relative for a count or an amount. Scores from different kinds of property are
   therefore comparable, and a score of 1.0 means "moved this property by exactly its tolerance".
6. **Refusals rank first.** A parameter the generator refuses to run with — because a calibration
   guard rejects the perturbed value — is ranked above every measured score. A value that cannot
   move at all without breaking the run is as influential as the run allows.

## What the method does and does not show

- It measures **local sensitivity at one point** (the repository parameters, +10%). A parameter
  whose effect is flat near that point but strong elsewhere will be ranked low.
- It measures **one parameter at a time**, so an interaction that only appears when two parameters
  move together is invisible to it.
- Perturbing every entry of a mapping by the same factor leaves a *share* mapping unchanged after
  renormalisation, so such parameters score low even when the shares matter enormously. The
  ranking is read with that in mind: `channels.channel_share`, `geography.country_share` and
  `fraud.scenario_share` are structurally decisive and treated as top-rank regardless of score.
- The size used for the ranking (20,000 rows) is far below the release size. Properties that are
  exact by construction (the mixes, the monthly fraud rate) behave the same at any size; the
  amount statistics are noisier.

These limits are the reason the ranking is used to *order the sourcing work*, not to claim that
low-ranked parameters do not matter.

## Result

The measured ranking is in `docs/research/parameter_influence.json`: 81 parameters, 20,000 rows,
seed 20260917, step 10%, with a SHA-256 of the parameter values it ranked so a later reader can tell
whether it still describes the repository. Nine parameters could not be perturbed or made the
generator refuse to run and are ranked first; `geography.country_share` scores highest among the
measured ones (10.3 tolerance units), followed by transactions per active customer (9.3), the
per-channel mean amounts (8.7), the amount dispersion (7.1) and the rows per fraud incident (7.0).

Where the sourcing pass landed against this ranking, including the four parameters in the top
fifteen that remain assumed, is recorded in `docs/research/sourcing_pass.md`.
