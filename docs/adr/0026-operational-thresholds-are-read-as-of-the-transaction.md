# 0026 — Operational thresholds and rules are read as-of the transaction, never as of today

- **Status:** accepted
- **Date:** 2026-09-19
- **Requirements affected:** FR-02-02 (`just_below_limit_flag`), FR-02-09, ML-DATA-07
- **Defects referenced:** D-03, D-04; extends ADR 0025's parity design

## Context

`just_below_limit_flag` is true when an amount falls within 5% below "any configured channel limit,
KYC-tier limit or active rule threshold" (Part E.2). Every one of those is **mutable operational
configuration**, and Part E.2 does not say *when* it is read.

The two feature paths resolve that silence in opposite directions by default. The batch path joins
the configuration tables and naturally sees **today's** thresholds. The online path reads whatever
is **live at scoring time**. Train on today's threshold against a transaction from eight months ago
and the feature encodes a threshold that did not exist when the transaction happened.

**This is future information arriving through configuration rather than through a window**, which is
why none of the window-contract fields catches it: there is no window to get wrong. It is the same
path asymmetry as `label_basis` — one path structurally cannot see the future value, the other can
and will unless stopped — in a different substrate.

It is also nearly undetectable by the parity suite as designed: the two paths agree for every
transaction newer than the last configuration change, so a fixture set without a configuration
change passes with the bug present.

## Decision

**1. The feature evaluates against the thresholds in force at the transaction's timestamp.**
Never today's, on either path. A transaction is scored against the rules it was actually subject to.

**2. A tenth contract field, `reference_data_basis`, with no default.**

| Value | Meaning |
|---|---|
| `AS_OF_EVENT` | mutable configuration read as of the transaction timestamp |
| `CURRENT` | the value is genuinely time-invariant, or deliberately read live |
| `NOT_REFERENCE_DATA` | the feature reads no mutable operational configuration |

Every feature reading mutable operational config must declare it. `CURRENT` is permitted but must be
a stated choice, because there are legitimate cases — a feature describing the *present* posture of
the system rather than the transaction's history — and silently defaulting either way is what this
ADR exists to stop.

**3. Parity fixtures must contain a configuration change.** A required fixture property, asserted as
an E12 precondition: the replayed history spans at least one threshold change, and the test asserts
`changes > 0` before asserting agreement. A parity suite whose fixtures contain no rule change
**cannot detect this class of divergence at all**, so without the precondition it would pass
vacuously — the exact failure E12 was written for, arriving in a new place.

## What M1's schema can and cannot answer

Checked against the migrations rather than assumed.

| Table | As-of queryable? |
|---|---|
| `risk_threshold_versions` | **Partly.** Carries `effective_at timestamptz NOT NULL` and is append-only, so "the version in force at T" is derivable as the greatest `effective_at <= T`. But nothing constrains `effective_at` to increase with `version`, so a backdated or scheduled insert breaks the correspondence, and there is no end bound. |
| `alert_rule_versions` | **No.** It carries only `created_at` — when the row was *written*, not when the rule became *effective*. A rule authored Monday and activated Wednesday is indistinguishable from one live on Monday. |
| `alert_rules.state` | **No.** `state` (`ENABLED`/`DISABLED`/`DELETED`) and `current_version` are mutable columns with only `updated_at`. There is no history of transitions, so "was this rule enabled at T?" is unanswerable — and a rule disabled after an incident is exactly the case that matters. |

### The migration M6 needs, recorded now so it is not a surprise then

1. `alert_rule_versions.effective_at`, distinct from `created_at`.
2. A state-transition history for `alert_rules` — an append-only event table, or validity intervals
   on the versions — so enablement and the current version are answerable as-of.
3. A monotonicity constraint (or an explicit validity interval) on `risk_threshold_versions`, so
   as-of resolution cannot be broken by a backdated insert.

Until these land, `just_below_limit_flag` can be computed as-of for **channel and KYC-tier limits
only**, and the "active rule threshold" clause is not implementable. The feature ships with that
stated, not with the rule clause silently reading today's rules.

## Consequences

- One more field the registry refuses to leave blank, on the same terms as the other nine.
- The parity suite gains a required fixture property; until the fixtures carry a configuration
  change, the suite's pass on this feature means nothing and says so.
- A partial feature is shipped with its limitation declared rather than a whole feature that is
  quietly wrong for every transaction older than the last rule change.

## Alternatives rejected

- **Read current thresholds on both paths.** Rejected: it makes the two paths agree while both are
  wrong, which is the decision-equality failure ADR 0025 already rejected in another form. Parity
  would pass and the model would be trained on information from the future.
- **The field alone, without the as-of rule.** Rejected: declaring `CURRENT` for this feature would
  record the defect rather than fix it.
- **The as-of rule alone, without the field.** Rejected: the next feature reading mutable config
  would face the same silence, and nothing would ask the question. The rule fixes one feature; the
  field fixes the class.
