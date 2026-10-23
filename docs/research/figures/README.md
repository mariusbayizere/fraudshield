# Figures

Every figure here is produced by a committed script from committed data. Nothing is drawn by hand,
pasted from a notebook, or exported from a session that no longer exists. Each has a caption stating
the scale, the seeds and the run that produced it, so a reader can regenerate it and get the same
picture.

Figures are written as **SVG generated directly by the script**, not through a plotting library.
That keeps the repository free of a plotting dependency (ADR 0009 governs what may be added), makes
the output deterministic, and lets a figure diff as text when the data behind it changes.

## `power_curve.svg`

**Shortcut detector: power, false alarms and negative control.**

Fire rate of the anti-leakage gate against dataset size, on clean data and on data with a shortcut
deliberately planted in the first byte of `transaction_id`. Four series: clean (the false-alarm
rate, nominal 5%), a plant agreeing with the label 60% of the time (the subtle case), a perfect
oracle (liveness only), and a plant agreeing 50% of the time — a negative control carrying no
information at all. The control is what makes the rest interpretable: without it, "the gate fired"
cannot be distinguished from "the gate fires whenever the data is touched".

Two vertical marks: power against the subtle plant saturates at about **60,000 rows**, and all eight
fraud scenarios appear only above about **170,000 rows**. The binding minimum for a usable dataset
is the second, not the first.

- **Scales:** 10,000 / 30,000 / 60,000 / 100,000 / 200,000 / 300,000 / 500,000 / 1,000,000 rows.
- **Seeds:** 15 per scale at 10K–60K, 10 at 300K, 5 elsewhere; seeds 20260917 onward.
- **Runs below 170,000 rows are structurally different** — they lack at least one fraud scenario, and
  each record says which. They measure the detector on the dataset a development run actually
  produces, not on a release dataset shrunk.
- **Bars** are 95% Wilson intervals, which do not misbehave at proportions of 0 or 1 the way a normal
  approximation does.
- **Data:** `docs/reviews/M2/shortcut_diagnosis.jsonl` (273 records).
- **Produced by:** `uv run python docs/research/figures/power_curve.py`, sweep committed at
  `be2d7b0`.
