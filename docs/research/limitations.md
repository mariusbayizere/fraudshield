# Limitations of the synthetic benchmark

Material for the paper's limitations section. Every statement here is checkable against the
repository, and none of it is softened for presentation.

## The data is synthetic, and the fraud in it is invented

The benchmark is generated, not collected. It supports comparisons between methods under identical
conditions; it does not support statements about real fraud rates, real detection performance, or
the behaviour of any institution. A recall figure measured here is a property of this generator.

## No parameter describes fraud behaviour from evidence

All 25 fraud parameters — scenario prevalence, the mix between scenario types, incident length,
burst timing, adaptation behaviour, the mule structure and the novelty variant — are modelling
choices. No central bank in the region publishes fraud incidence by type:

- the National Bank of Rwanda's *Annual Report 2024-2025* describes a Fraud Prevention Forum and
  lists mobile money fraud and scams among consumer complaint categories, without incident counts
  by type;
- the Bank of Tanzania's *Payment Systems Annual Report for 2024* does not break fraud out at all.

Sourcing these figures from a vendor report, a news article, or a global aggregate presented as
regional would make the paper look better and be less honest. They stay assumed, and any result
that depends on the *level* of fraud (rather than on the relative ordering of methods) inherits
that assumption.

## The sourced parameters are structural, and partly from a neighbouring market

Twelve of 81 parameters are sourced from documents read in full. They fix the population and volume
structure: transactions per active customer, agents and merchant acceptance points per customer, the
urban share of customers, per-channel mean amounts, the person-to-person versus merchant split, the
cross-border share, volume growth, exchange rates, currency codes, time zones and school terms.

Tanzania publishes value and volume by transaction category and Rwanda does not, so the
per-transaction levels come from Tanzanian aggregates while the dataset's country mix is
Rwanda-weighted. Those levels are order-of-magnitude anchors, not claims about one market.

## Distribution targets are requirements, not observations

The fraud rate (0.87% overall, 0.91% in the test period), the channel mix and the country mix are
calibrated to SRS section 7.1 and met by construction. They describe what the system was specified
against. They are not evidence about East African payments, and the paper does not present them as
such.

## What the benchmark does establish

- That a stated leakage standard can be enforced mechanically: single-feature AUC ceilings, a
  shortcut detector on non-behavioural columns, identifier-construction and null-pattern checks,
  all as tests that fail the build.
- That the dataset is reproducible byte for byte from a seed, independently of how the work is
  batched.
- That distribution targets can be met by construction rather than by sampling luck, with monthly
  confidence intervals showing which deviations are noise.
- A like-for-like comparison between detection methods, under drift and against an unseen fraud
  variant.
