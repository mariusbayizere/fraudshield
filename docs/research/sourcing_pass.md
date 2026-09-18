# Sourcing pass over the generator parameters

Owner direction, 2026-09-18: "0 of 79 parameters sourced" is the weakest point of M2. Rank the
parameters by influence, then find and read primary sources for the most influential ones, and say
honestly what could not be sourced.

This document records what was read, what changed, and what remains a modelling choice.
`dataset/params_provenance.md` is the generated per-parameter record;
`docs/research/parameter_influence.md` records how influence was measured.

## Result

| Provenance | Before | After |
|---|---:|---:|
| SOURCED | 0 | 10 |
| ASSUMED | 65 | 57 |
| CALIBRATED_TO_SRS_TARGET | 14 | 14 |
| **Total** | **79** | **81** |

Two parameters were added by the pass itself: `population.urban_share_of_customers` (a sourced
fact that now constrains an assumed split) and `behaviour.amount_mean_rwf` (sourced means, from
which the generator derives the medians it needs). One parameter was replaced:
`behaviour.rural_ussd_channel_share` became `behaviour.segment_channel_preference`, because the
sourced population structure made the old formulation unusable (see "What the sources changed").

## Documents read

Each of these was downloaded and read in full text, not summarised from a search result.

| # | Document | Publisher, year | Used for |
|---|---|---|---|
| S-1 | [Payment Systems Annual Report for 2024](https://www.bot.go.tz/Publications/Regular/Annual%20Report/sw/2025032515311662.pdf) (76 pages) | Bank of Tanzania, 2025 | transactions per active user; per-channel mean amounts; P2P against merchant payments; volume growth; agent and merchant counts |
| S-2 | [NBR Annual Report 2024-2025](https://www.bnr.rw/documents/Annual_Report_2024_25_English_Full_Version_RwYAWN0.pdf) (222 pages) | National Bank of Rwanda, 2025 | agents per customer; merchant acceptance points per customer; active subscribers; exchange-rate context; retail e-payments to GDP |
| S-3 | [Fifth Rwanda Population and Housing Census 2022, Main Indicators Report](https://statistics.gov.rw/sites/default/files/documents/2025-02/RPHC5_MainIndicatorsReport_Final.pdf) (176 pages) | National Institute of Statistics of Rwanda, 2023 | urban and rural population (Table 2, page 30) |
| S-4 | [FinScope Rwanda 2024](https://statistics.gov.rw/sites/default/files/documents/2024-09/Rwanda-Finscope-2024-Report_compressed.pdf) (77 pages) | NISR / Access to Finance Rwanda, 2024 | mobile money use by urban and rural adults (Figure 26, page 40); wallet ownership |
| S-5 | [World Development Indicators, PA.NUS.FCRF](https://api.worldbank.org/v2/country/RWA;KEN;TZA;UGA;COD/indicator/PA.NUS.FCRF?format=json&date=2023:2024) | World Bank / IMF International Financial Statistics | official exchange rates, 2024 (2023 for the DRC) |
| S-6 | [IANA Time Zone Database 2026c](https://www.iana.org/time-zones) (as shipped in tzdata 2026c) | IANA, 2026 | UTC offsets of the five countries |
| S-7 | [School calendar 2024-2025](https://www.nga.ac.rw/storage/nArw8l4yXLQqkb3qi5aHo39WSkW9SH-metaU0NIT09MIENBTEVOREFSLnBkZg==-.pdf) | Nu Vision Academy (implementing Rwanda's national calendar), 2024 | months in which school terms begin |

Searched but not used: the National Bank of Rwanda payment statistics pages (JavaScript-rendered,
no readable figures), the Rwanda Utilities Regulatory Authority telecom statistics (no document
retrieved), GSMA State of the Industry (not retrieved in readable form), World Bank Findex (not
needed once FinScope gave a Rwandan figure). Nothing was cited from a news article or a search
snippet.

## What the sources changed

| Parameter | Was | Now | Source |
|---|---|---|---|
| `population.mean_transactions_per_active_customer_month` | 14 | 8.8 | S-1 Table H1: 6,414M transactions / 60,745,698 active users / 12 |
| `population.customers_per_agent` | 150 | 30 | S-2 Table 11: 251,042 agents against 7,457,114 active subscribers |
| `population.customers_per_merchant` | 40 | 12 | S-2 Table 11: 625,489 modern POS against 7,457,114 subscribers |
| `population.urban_share_of_customers` | — | 0.32 | S-3 (27.9% urban) weighted by S-4 (87% urban and 72% rural adults use mobile money) |
| `population.segment_share` | 35/25/30/10 | 24/26/42/8 | constrained to the urban share above; the split within each side stays assumed |
| `behaviour.amount_mean_rwf` | — | 15,731 / 16,664 / 110,876 / 69,344 RWF | S-1 value ÷ volume for mobile money, agent cash-in, card at POS, bank-to-wallet |
| `behaviour.p2p_share_of_wallet_payments` | 0.6 | 0.216 | S-1 Tables H2 and H5: 479.1M P2P against 1,736.2M merchant payments |
| `behaviour.school_fee_months` | Jan, May, Sep | Jan, Apr, Sep | S-7: term 3 begins 22-25 April, not May |
| `volume.monthly_growth_rate` | 0.03 | 0.0201 | S-1 Table H1: +27% volume in 2024, compounded monthly |
| `currencies.rwf_per_unit` | 10 / 0.5 / 0.35 / 0.45 | 9.7768 / 0.5074 / 0.3508 / 0.4958 | S-5 official rates |
| `currencies.utc_offset_hours` | CD +1 | CD +2 | S-6: the simulated Congolese customers are in the east (Africa/Lubumbashi) |
| `currencies.country_centre` | CD Kinshasa | CD Goma | consistency with the above; the coordinates themselves stay assumed |

Two changes went beyond a value. The sourced population is mostly rural (68%), and the old channel
model gave one segment a fixed channel mix while the others absorbed whatever the SRS mix left
over; with a rural majority that remainder pushed urban customers to a fifth of their payments on
cards and almost nothing through agents. Channel mixes are now *fitted*: each segment has relative
preferences and iterative proportional fitting scales them until the mixture is the SRS mix
exactly, so ML-DATA-03 still holds by construction at any population split while each segment keeps
a plausible profile. Separately, the generator used to rewrite a smartphone owner's USSD payment
into a wallet payment; with every segment now carrying some USSD that rewriting moved the realised
mix 3.8 points off target, and it is gone — whether a row has a device is decided by the channel,
as a USSD session has no app device.

Verified after the changes, at 60,000 rows: fraud rate 0.870% overall and 0.906% in the test
period, channel mix within 0.34 pp, country mix within 0.12 pp. The calibrated targets take
precedence over the sourced values for the fraud rate, the channel mix and the country mix, and
they continue to hold.

## What could not be sourced, and why

**Fraud parameters stay assumed.** All 25 parameters in `fraud.yaml` are ASSUMED or calibrated to
an SRS target. Scenario prevalence, incident length, attack timing, adaptation behaviour and the
mule structure are not published anywhere that could be read: the National Bank of Rwanda's annual
report discusses a Fraud Prevention Forum and consumer complaints about "mobile money fraud and
scams" without publishing incident counts by type, and the Bank of Tanzania report does not break
fraud out at all. Sourcing these weakly — from a vendor report, a news article or a global
aggregate presented as regional — would be worse than leaving them assumed, so they are left
assumed and the paper says so.

**Behavioural timing stays assumed.** Payday windows, market-day weights, hour-of-day
distributions, night activity, travel, round-sum behaviour and label delays have no published
series at transaction level.

**ONLINE and USSD amounts stay assumed.** The internet banking series in S-1 (Annex J, page 50)
averages about RWF 5 million per transaction, which is corporate treasury activity rather than the
consumer online payments the channel represents, so it was deliberately not used. USSD is an
interface rather than a transaction type, so no published series matches it.

**The sourced figures are not all Rwandan.** The per-channel amounts, the transactions per user,
the P2P split and the growth rate come from Tanzanian aggregates, because Tanzania publishes a
per-category breakdown and Rwanda does not. The dataset's country mix is Rwanda-weighted, so those
levels are an order-of-magnitude anchor, not a claim about any one market. Each citation says so.

**Nothing derived from a single operator.** MTN Rwanda's published transaction figures would have
given a Rwandan per-transaction value, but they describe one operator's book rather than the market,
so they were not used.
