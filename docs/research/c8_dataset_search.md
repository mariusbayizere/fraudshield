# C-8 structured literature and dataset search record

> Committed on 2026-10-19 as the search agent wrote it, except for this note. Paths under `scratchpad/` name the session's temporary directory, which is not kept; the raw API responses are not in the repository. The first pass is `c8_dataset_search.md`, the second `c8_dataset_search_pass2.md`.

Claim under test (C-8): "FraudShield-EAC is the first public East African financial fraud benchmark dataset."

Search start (UTC): 2026-10-19T16:07:47Z
Searcher: Claude agent (WebSearch + WebFetch tools). No repository files modified.

## 1. Protocol (written before searching)

### Research question
Does any publicly available dataset (real or synthetic) of financial / payment / mobile-money fraud — labelled transaction- or account-level records, or a fraud benchmark — exist that covers East Africa or at least one East African country?

### Inclusion criteria
- Publicly downloadable, or available on request through a stated public process.
- Transaction- or account-level records carrying fraud / scam / AML labels, OR a published fraud-detection benchmark.
- Real or synthetic.
- Covers at least one East African country (EAC members: Kenya, Uganda, Tanzania, Rwanda, Burundi, South Sudan, DRC, Somalia; also Ethiopia noted as wider East Africa) OR is explicitly calibrated to one.
- Also recorded (flagged separately): pan-African / Sub-Saharan datasets that include East African countries.

### Exclusion criteria
- Private / proprietary data with no public access route (paper uses data but does not release it).
- Data with no fraud/scam/AML label (e.g. plain financial-inclusion surveys, transaction volumes) — noted as adjacent only.
- Non-transaction modalities (SMS text, call logs, phishing URLs) — recorded as ADJACENT, not qualifying.
- Datasets whose only African link is a general claim with no country coverage/calibration.

### Sources (planned)
Google Scholar / general scholarly web search (via WebSearch); Kaggle; Hugging Face datasets; Zenodo; Papers with Code datasets; OpenML; UCI ML Repository; GitHub; IEEE DataPort; Mendeley Data; Harvard Dataverse; Zindi; openAFRICA; Kenya Open Data; Rwanda NISR microdata; Lacuna Fund; AfricArXiv; Google Dataset Search.
Where a site cannot be queried directly by WebFetch, a site:-restricted WebSearch is used and recorded as such.

### Query strings (planned, combined as needed)
Fraud terms: fraud, scam, money laundering, AML, anomaly
Channel terms: mobile money, M-Pesa, MTN MoMo, Airtel Money, USSD, agent banking, SIM swap, payments, transactions
Geography: Kenya, Rwanda, Uganda, Tanzania, Burundi, South Sudan, DRC, Somalia, Ethiopia, East Africa, Sub-Saharan Africa, Africa
Artifact: dataset, benchmark, synthetic, simulator

### Known neighbours to characterise
PaySim, BankSim, Bank Account Fraud (BAF) suite, IBM AML synthetic (Altman et al. 2023), Zindi/Xente fraud dataset, Kaggle "mobile money fraud" datasets, M-Pesa scam SMS datasets.

## 2. Query log
(ID | UTC time | source | exact query / URL | results examined | relevant hits)
TIMING NOTE: per-query UTC times were NOT captured individually ("n/c"). Only the session start (2026-10-19T16:07:47Z) and end (2026-10-19T16:19:48Z) were measured with `date -u`; every query below ran between those two instants, in the listed order.

Q1 | n/c | General web (WebSearch; scholarly + web) | `mobile money fraud dataset Kenya Uganda Tanzania Rwanda labelled transactions` | 9 results examined | HITS: PMC12036017 "A labeled synthetic mobile money transaction dataset" (Azamuke et al., Data in Brief 2025, Uganda/East Africa) — QUALIFIES; arXiv 2608.15447 "Detecting Money Laundering in Rwandan Mobile Money" (uses Kaggle SAML-D, no Rwandan data released) — adjacent; CEGA "identifying fraud patterns ... privacy preserving synthetic data" (MoMTSim project page) — adjacent/same project; IGC Rwanda MoMo harmonisation (restricted, no fraud labels) — excluded.
Q2 | n/c | Zindi (via WebSearch) | `Zindi Xente fraud detection challenge dataset Uganda` | 9 results | HIT: Zindi Xente Fraud Detection Challenge (2019, Uganda, real) — QUALIFIES (see fetch F2).
Q3 | n/c | General web | `PaySim synthetic mobile money simulator Lopez-Rojas 2016 African country calibrated` | 10 results | PaySim descriptions (unnamed African country); Azamuke et al. FAMECSE'22 MoMTSim design paper.
F1 | n/c | PMC fetch https://pmc.ncbi.nlm.nih.gov/articles/PMC12036017/ | fetched OK (first URL www.ncbi... 301-redirected).
F2 | n/c | Zindi fetch https://zindi.world/competitions/xente-fraud-detection-challenge/data (zindi.africa 301-redirects to zindi.world) | fetched OK.
F3 | n/c | SWITS 2015 abstract PDF (Lopez-Rojas) https://swits.hotell.kau.se/AnnualSeminars/SWITS-15/SWITS%202015%20Abstract-EdgarLopez.pdf | read; no country named.
F4 | n/c | Kaggle https://www.kaggle.com/datasets/ealaxi/paysim1 | FETCH FAILED to return description (JS-rendered; only page title returned).
Q4 | n/c | General web | `"PaySim: A financial mobile money simulator for fraud detection" "African country"` | 10 results | no copy of the 2016 paper full text found; Kaggle description mirrored on finclip.com.
F5 | n/c | Mendeley Data https://data.mendeley.com/datasets/zhj366m53p/2 | fetched OK.
F6 | n/c | GitHub mirror https://github.com/BBQtime/Synthetic-Financial-Datasets-For-Fraud-Detection | fetched OK (PaySim Kaggle description copy).
Q5 | n/c | General web | `Lopez-Rojas Elmir Axelsson 2016 PaySim EMSS paper pdf mobile money "Africa"` | 10 results | no PDF of 2016 paper found.
F7 | n/c | UNCST repository PDF https://nru.uncst.go.ug/bitstreams/9ba9bbb6-383d-47fa-b5f3-a1d5490da1f1/download | read pp.1-4: Azamuke, Katarahweire, Bainomugisha, "Scenario-based Synthetic Dataset Generation for Mobile Money Transactions", FAMECSE '22, doi 10.1145/3531056.3542774 (design paper, no dataset).
Q6 | n/c | Kaggle (site:-restricted WebSearch; Kaggle search is JS/login-gated) | `site:kaggle.com mobile money fraud dataset Africa` | 9 results (site filter NOT honoured by engine; results from across web) | PaySim (ealaxi/paysim1) and derivatives; MOZ-Smishing (AfricaNLP 2025, Mozambique SMS) adjacent.
Q7 | n/c | Hugging Face (site:-restricted WebSearch) | `site:huggingface.co datasets mobile money fraud Kenya OR Uganda OR M-Pesa OR Africa` | 9 results (site filter NOT honoured) | no HF pages; repeats MoMTSim, MOZ-Smishing.
F8 | n/c | Zenodo REST API https://zenodo.org/api/records?q=%22mobile%20money%22%20fraud&size=25 | 14 records read of truncated page (total not shown in read portion) | all are articles/proceedings, none a labelled transaction dataset; none qualify.
F9 | n/c | Zenodo REST API with type=dataset and boolean country query | HTTP 400 (rate-limit/Retry-After) — FAILED; retried later (see F15).
F10 | n/c | Hugging Face API https://huggingface.co/api/datasets?search=fraud&limit=200 | 200 ids examined (2 pages of text) | Africa-tagged: electricsheepafrica/* (Nigeria financial transactions/fraud; Nigeria telecom fraud; Africa insurance fraud); CiferAI/Cifer-Fraud-Detection-Dataset-AF (PaySim-derived, no stated region); kagikush/Nigerian_telecom_fraud; *Nigerian-Financial-Transactions-and-Fraud-Detection-Dataset copies. None East African except via search F11.
F11 | n/c | Hugging Face API https://huggingface.co/api/datasets?search=mobile%20money&limit=100 | 10 ids | HIT: electricsheepafrica/africa-mobile-money-fraud-dataset (synthetic, includes Kenya/Uganda/Tanzania rows) — QUALIFIES (weak provenance); electricsheepafrica/africa-uganda-mobile-money-transactions-2009-to-2023-* (UBOS aggregates, no labels) — excluded.
F12 | n/c | HF card/API/rows for electricsheepafrica/africa-mobile-money-fraud-dataset (README raw; /api/datasets/...; datasets-server /rows offset 0 len 30) | fetched OK; /statistics endpoint HTTP 500.
F13 | n/c | HF card CiferAI/Cifer-Fraud-Detection-Dataset-AF | fetched OK; PaySim-based, no region stated — does not meet geography criterion beyond PaySim's unnamed African country.
F14 | n/c | HF global-dataset-lab/global-fintech-fraud-transactions README | HTTP 401 (gated) — FAILED, not characterised.
F15 | n/c | Zenodo REST API https://zenodo.org/api/records?q=fraud%20mobile%20money&type=dataset&size=50 | HTTP 400 (Retry-After 60) — FAILED (second attempt). Zenodo dataset-type filtering therefore NOT completed; Zenodo coverage limited to F8 (keyword search over all record types).
F16 | n/c | OpenML API https://www.openml.org/api/v1/json/data/list/data_name/paysim | HTTP 412 — FAILED (see later OpenML attempt).
F17 | n/c | GitHub REST search https://api.github.com/search/repositories?q=mobile+money+fraud+dataset | total_count 88; first 16 examined | all PaySim re-analyses or unlabelled-source student repos; none an East African benchmark.
F18 | n/c | GitHub REST search with boolean country query | HTTP 422 — FAILED (GitHub search rejects that syntax).
Q8 | n/c | General web | `"mobile money" fraud dataset kaggle Kenya OR Uganda OR Tanzania OR Rwanda OR "M-Pesa"` | 9 results | same MoMTSim / PaySim / arXiv Rwanda; World Bank 2017 brief "Fraud in Mobile Financial Services" (CGAP; Ghana, Kenya, Pakistan, Rwanda, Tanzania, Uganda — report, no dataset).
Q9 | n/c | IEEE DataPort (WebSearch) | `IEEE DataPort mobile money fraud dataset Africa` | 9 results | no IEEE DataPort listing returned; AFRICOMM 2023 paper (Springer 2025, doi 10.1007/978-3-031-81573-7_16) MoMTSim.
F19 | n/c | IEEE DataPort direct search https://ieee-dataport.org/search/node?keys=mobile%20money%20fraud | HTTP 404 — FAILED.
F20 | n/c | GitHub REST search q=mpesa+fraud | total_count 37; 18 examined | hobby/student M-Pesa fraud repos; Brian-Mbuya/mpesa-fraud-detection tree checked: no data files.
F21 | n/c | GitHub REST search q=fraud+kenya | total_count 66; 17 examined | AmmonBelyon/fraud-detection-kenya ships data/kenya_fraud_dataset.csv (~9.9 MB) "synthetic Kenyan data", created 2026-06-05, no licence, 0 stars — public but undocumented student project (see table).
F22 | n/c | Kaggle SAML-D page https://www.kaggle.com/datasets/berkanoztas/synthetic-transaction-monitoring-dataset-aml | JS-rendered, only title returned — FAILED to read description.
F23 | n/c | arXiv https://arxiv.org/abs/2608.15447 | fetched OK (Nahimana & Gaba, submitted 15 Aug 2026; uses SAML-D; no Rwandan data released).
Q10 | n/c | General web | `SAML-D synthetic anti money laundering dataset Oztas payment location countries high-risk list` | 9 results | SAML-D paper PDF (Bournemouth eprints 40982); "A Customer Level Fraudulent Activity Detection Benchmark" arXiv 2404.14746 (noted, not East African-specific by title; not examined in detail).
F24 | n/c | SAML-D paper PDF https://eprints.bournemouth.ac.uk/40982/1/Full_IEEE_Dataset_Conference_Paper%20%284%29.pdf | downloaded, text extracted locally with pdftotext; grep for kenya|uganda|tanzania|rwanda|ethiopia|somalia|africa found NO match; named high-risk countries: "Mexico, Turkey, Morocco, and the United Arab Emirates". (Full list of the dataset's location values not in paper text; Kaggle page unreadable.)
Q11 | n/c | General web | `Azamuke Katarahweire Bainomugisha "Financial Fraud Detection Using Rich Mobile Money Transaction Datasets" MoMTSim` | 9 results | AFRICOMM paper; Makerere PhD thesis (Azamuke 2025, MoMTSimDP, makir.mak.ac.ug/handle/10570/16129) — fetched: no dataset release stated.
F25 | n/c | Harvard Dataverse Search API https://dataverse.harvard.edu/api/search?q=fraud%20AND%20(Kenya%20OR%20Uganda%20OR%20Tanzania%20OR%20Rwanda%20OR%20%22mobile%20money%22)&type=dataset&per_page=50 | total_count 13, all 13 examined | no transaction-level fraud labels; adjacent: CGAP-style "Consumer Protection Survey of Digital Finance Users: Uganda" and "...: Kenya" (2021), "Financial Consumer Protection Surveys" (2026; Kenya, Tanzania, Uganda), "Can you spot a scam?" replication (Kenya) — surveys, excluded.
F26 | n/c | openAFRICA CKAN API (africaopendata.org -> open.africa) package_search q=fraud | HTTP 403 — FAILED.
F27 | n/c | Mendeley Data search page https://data.mendeley.com/research-data/?search=mobile%20money%20fraud | page returned generic latest-items list (query not applied; JS) — FAILED as a search; replaced by Q12.
Q12 | n/c | Mendeley Data (site:-restricted WebSearch) | `site:data.mendeley.com mobile money fraud transactions dataset` | 9 results | MoMTSim dataset (zhj366m53p v2); "Synthetic Banking Transaction Dataset with Multi-Pattern Fraud Labels..." (ktbthg777x, Nov 2025; geography not stated in snippet — not examined further).
Q13 | n/c | Zindi (site:-restricted WebSearch) | `site:zindi.africa fraud competition` | 9 results | Xente (Uganda, financial); IndabaX DRC 2022 (fetched: Tunisian STEG electricity/gas billing fraud, competition restricted to Congolese residents — NOT financial, NOT DRC data); SUPCOM Tunisian (tax fraud, Tunisia); Cameroon/InstaDeep electricity fraud; Barbados hackathon. Only Xente is East African financial.
F28 | n/c | Zindi Xente main page https://zindi.world/competitions/xente-fraud-detection-challenge | fetched OK (dates, geography, data rules quoted below).
F29 | n/c | Rwanda NISR microdata catalog https://microdata.statistics.gov.rw/index.php/catalog?sk=fraud | "0 studies out of 76" | none.
Q14 | n/c | Kenya Open Data (site:-restricted WebSearch) | `site:opendata.go.ke fraud` | 10 results (site filter NOT honoured) | none relevant.
Q15 | n/c | Lacuna Fund (WebSearch) | `Lacuna Fund financial fraud mobile money dataset Africa` | 9 results | no Lacuna-funded fraud dataset.
F30 | n/c | Lacuna Fund datasets page https://lacunafund.org/datasets/ | fetched OK | domains Agriculture/Language/Health/Climate; only finance-adjacent item: Ghanaian-language financial-inclusion speech dataset. None.
F31 | n/c | CEGA project page https://cega.berkeley.edu/research/identifying-fraud-patterns-and-financial-exclusion-with-privacy-preserving-synthetic-data | fetch returned generic listing; dataset release not determinable from fetched content.
F32 | n/c | Google Dataset Search https://datasetsearch.research.google.com/search?query=mobile%20money%20fraud | "25 datasets found", 20 visible examined | NEW HITS: MoMTSim-TZ (Zenodo, Tanzania); M-Pesa Transactions Fraud Detection (Kaggle, Kenya); NSMMF_fraud_Dataset (Kaggle, Nigeria); Mobile Money Phone Scam Survey (Cameroon survey); Kaggle mirror of Azamuke MoMTSim dataset; electricsheepafrica HF; PaySim and copies.
F33-F38 | n/c | Google Dataset Search detail queries: `MoMTSim-TZ`, `M-Pesa Transactions Fraud Detection`, `NSMMF_fraud_Dataset`, `Mobile Money Phone Scam Survey Dataset`, `Synthetic mobile money transaction dataset` (10 examined), `Mobile Money Fraud Detection kaggle` (10 examined), `"Mobile Money Fraud Detection"` (0 results) | details recorded in table.
F39 | n/c | Zenodo record API https://zenodo.org/api/records/22997330 | fetched OK (MoMTSim-TZ metadata).
Q16-Q18 | n/c | General web | `"MoMTSim-TZ" localized synthetic transaction dataset fraud detection Tanzania` (10; NOT found by web search — found only via Dataset Search); `"Mobile Money Phone Scam Survey Dataset"` (9; MOZ-Smishing, Zambia survey); `kaggle "M-Pesa Transactions Fraud Detection" dataset` (10; not found by web search).
F40-F44 | n/c | Google Dataset Search `fraud Kenya` (24 found, 20 examined), `fraud Uganda` (8), `fraud Tanzania` (6), `fraud Rwanda` (4), `fraud Ethiopia bank` (3) | Kenya: M-Pesa Transactions Fraud Detection (Kaggle) + "mpesa_fraud_complaints dummy data" (Kaggle, Mar 2024, unverified); Tanzania: "Swahili SMS Detection Dataset" (Kaggle, SMS, adjacent), Zenodo legal article; others none. NOTE: Xente and MoMTSim did NOT surface for `fraud Uganda` — Dataset Search recall is incomplete.
Q19 | n/c | Papers with Code (WebSearch) | `paperswithcode dataset fraud detection mobile money Africa` | 9 results | no PwC page; MOZ-Smishing title confirmed ("MOZ-Smishing: A Benchmark Dataset for Detecting Mobile Money Frauds").
F45 | n/c | Papers with Code direct https://paperswithcode.com/datasets?q=fraud | 302 redirect to huggingface.co/papers/trending — PwC dataset search NO LONGER AVAILABLE; not queryable.
F46 | n/c | OpenML API https://www.openml.org/api/v1/json/data/list/tag/fraud | 1 dataset (id 1597 "creditcard") | none African.
F47 | n/c | UCI ML Repository https://archive.ics.uci.edu/datasets?search=fraud (JS page, "0 of 0") and https://archive.ics.uci.edu/api/datasets/list?search=fraud (empty data array) | UCI search NOT effectively executed (query parameter apparently not applied).
Q20 | n/c | UCI (site:-restricted WebSearch) | `site:archive.ics.uci.edu fraud transactions dataset` | 10 results (site filter NOT honoured) | none from UCI.
Q21 | n/c | AfricArXiv (WebSearch) | `AfricArXiv mobile money fraud detection dataset` | 9 results | no AfricArXiv item; repeats MoMTSim, MOZ-Smishing.
Q22 | n/c | General web | `SIM swap fraud dataset Kenya OR Uganda OR Tanzania labelled publicly available` | 9 results | news/INTERPOL aggregates only; no dataset.
Q23 | n/c | General web | `agent banking fraud dataset East Africa machine learning benchmark` | 9 results | arXiv 2606.17555 (Walusimbi & Ssentongo 2026; synthetic log 237,669 txns; release not stated in abstract; country not stated in abstract); AAU thesis (Manaye 2024, Ethiopia mobile banking fraud; data source/release not stated) — neither qualifies on available evidence.
F48-F49 | n/c | arXiv 2606.17555 abstract; AAU etd handle 123456789/7416 | fetched OK.
Q24 | n/c | General web | `synthetic fraud benchmark dataset "Sub-Saharan Africa" OR "East Africa" payments money laundering simulator 2025 2026` | 10 results | Nigeria NIBSS-calibrated synthetic benchmark (lagjma.unilag.edu.ng/article/view/2874; self-described first public NIBSS-calibrated dataset for Nigeria) — Nigeria, not East Africa.
F50-F52 | n/c | arXiv 2211.13358 (BAF); arXiv 2306.16424 (IBM AML); Kaggle ealaxi/banksim1 (JS — failed) | BAF/IBM abstracts fetched OK.
Q25 | n/c | General web | `BankSim Lopez-Rojas Axelsson 2014 bank payments simulation "Spain" fraud` | 10 results | EMSS 2014 BankSim: "based on a sample of aggregated transactional data provided by a bank in Spain".
Q26 | n/c | General web | `Swahili scam SMS dataset M-Pesa fraud messages Kenya Tanzania public dataset` | 9 results | Kaggle Swahili SMS Detection Dataset (Tanzania, 1,508 SMS) — adjacent (SMS); EAJIT 2025 and IEEE Access 2022 Swahili smishing studies (release unclear).
Q27-Q28 | n/c | General web | `"PaySim" "mobile money" simulator Lopez-Rojas Elmir Axelsson EMSS 2016 diva-portal OR researchgate` (10); `msc-les.org proceedings emss 2016 PaySim financial mobile money simulator for fraud detection` (10) | found official EMSS 2016 proceedings page.
F53 | n/c | https://msc-les.org/proceedings/emss/emss2016/emss2016_249.html and PDF https://msc-les.org/proceedings/emss/2016/EMSS2016_249.pdf | PDF downloaded to scratchpad/dl_paysim, text extracted with pdftotext; quotes below are from that text.
F54 | n/c | Google Dataset Search `mpesa_fraud_complaints dummy data` | Kaggle pauletwanjiru, 1,186-byte zip, no labels described — excluded (not a usable labelled dataset on available evidence).
F55-F57 | n/c | Mendeley zhj366m53p v1; Dataset Search `Swahili SMS Detection Dataset`; ACL Anthology 2025.africanlp-1.23 | fetched OK.
Q29 | n/c | openAFRICA (WebSearch, after API 403) | `open.africa dataset fraud mobile money transactions` | 10 results | nothing on open.africa.
F58-F59 | n/c | Google Dataset Search `money laundering mobile money Africa` (1 result, market report) ; `fraud dataset East Africa` (100+ found, 20 examined, all market reports) | none.

Search end (UTC): 2026-10-19T16:19:48Z

## 3. Known neighbours — characterisation

| Dataset | Geography | Real/synthetic | Public | Labels | Evidence |
|---|---|---|---|---|---|
| PaySim (Lopez-Rojas, Elmir, Axelsson, EMSS 2016; Kaggle ealaxi/paysim1) | Calibrated to an UNNAMED African country. Paper: "PaySim simulates mobile money transactions based on a sample of real transactions extracted from the logs of a mobile money service implemented in an African country." The background section mentions Tanzania as an example of mobile money adoption but does NOT state the data is from Tanzania. Kaggle copy (via GitHub mirror BBQtime): "...from a mobile money service implemented in an African country." | Synthetic (agent-based) | Yes (Kaggle; Kaggle page not readable by fetch, licence not verified) | isFraud, isFlaggedFraud (Kaggle version). The 2016 paper states fraud injection is "outside the scope of this paper". | F53, F6 |
| BankSim (Lopez-Rojas & Axelsson, EMSS 2014) | Spain: "based on a sample of aggregated transactional data provided by a bank in Spain" | Synthetic | Yes (Kaggle ealaxi/banksim1; page not readable) | fraud flag | Q25 |
| BAF suite (Jesus et al., NeurIPS 2022, arXiv 2211.13358) | Source: "anonymized, real-world bank account opening fraud detection dataset"; bank/country NOT named in abstract | Synthetic (generated from real) | Yes | account-opening fraud | F50 |
| IBM AML synthetic (Altman et al., arXiv 2306.16424; NeurIPS 2023 D&B per task brief, venue not shown on arXiv page) | No country named in abstract; generic multi-bank world | Synthetic (agent-based) | Yes | laundering labels ("ground truth labels are complete") | F50 |
| SAML-D (Oztas et al., IEEE ICEBE 2023) | UK-based (Bournemouth); paper names high-risk countries "Mexico, Turkey, Morocco, and the United Arab Emirates"; no East African country in paper text (grep) | Synthetic | Yes (Kaggle) | Is Suspicious + 28 typologies | F24 |
| Zindi Xente Fraud Detection Challenge (2019) | Uganda: "Xente is an e-payments, e-commerce, and financial services company in Uganda"; data page: "Xente is an e-commerce and financial service app serving 10,000+ customers in Uganda." | REAL (sampled transactions, 15 Nov 2018 – 15 Mar 2019, ~140,000) | Downloadable after accepting Zindi terms; "sole property of Zindi and the competition host"; redistribution prohibited | FraudResult (1 fraud / 0 not) on train; test unlabelled | F2, F28 |
| Kaggle "mobile money fraud" datasets | Mostly PaySim copies/derivatives (no new geography). Exceptions found: Caleb Boen "M-Pesa Transactions Fraud Detection" (Kenya, synthetic); denishazamuke mirror of MoMTSim; NSMMF (Nigeria) | Synthetic | Yes | various | F32-F38 |
| M-Pesa / mobile money scam SMS | Swahili SMS Detection Dataset (Tanzania, Kaggle); MOZ-Smishing (Mozambique, Portuguese, AfricaNLP 2025) | Real crowd-sourced text | Yes | scam/legit | ADJACENT — SMS text, not transactions |

## 4. Results table (relevant hits)

| # | Title | URL | Host | Year | Real/Synth | Countries | Labels | Licence/access | Meets inclusion? |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Xente Fraud Detection Challenge | https://zindi.africa/competitions/xente-fraud-detection-challenge (301 -> zindi.world) | Zindi | 2019 | Real | Uganda | FraudResult per transaction (train) | Free download after accepting competition terms; no redistribution | YES — public-on-terms, real, labelled, East African, and a competition benchmark with leaderboard. Caveat: single e-commerce/payments platform, restrictive terms. |
| 2 | Synthetic Mobile Money Transaction Dataset (MoMTSim) / "A labeled synthetic mobile money transaction dataset" (Azamuke, Katarahweire, Bainomugisha; Data in Brief 60:111534, doi 10.1016/j.dib.2025.111534) | https://data.mendeley.com/datasets/zhj366m53p/2 (v1 7 Oct 2024; v2 29 Oct 2024); Kaggle mirror https://www.kaggle.com/datasets/denishazamuke/synthetic-mobile-money-transaction-dataset | Mendeley Data; Kaggle | 2024 (data) / 2025 (article) | Synthetic (agent-based, MoMTSim) | Article: data source location "Kampala/Eastern Africa region"; purpose "particularly in East Africa". Calibration country NOT named explicitly ("derived from a large real transaction dataset") | isFraud; account takeover (SIM swap, lost credentials), refund fraud, fake credentials; v1 1,720,181 rows (10.20% fraud), v2 4,225,958 rows (52.84% fraud) | CC BY 4.0 | YES — public, labelled, synthetic mobile-money fraud dataset framed for East Africa/Uganda, published in a peer-reviewed data journal. |
| 3 | MoMTSim-TZ: A Localized Synthetic Transaction Dataset for Fraud Detection (Ndegese, Sinde, Mdegela) | https://doi.org/10.5281/zenodo.22997330 | Zenodo | 2026-09-27 | Synthetic (MoMTSim re-parameterised) | Tanzania: "re-parameterised and calibrated to Tanzanian mobile money usage patterns" | 20,679 of 19,614,235 txns fraudulent (0.1054%), 12 typologies | CC-BY-4.0 | YES — but published 2026-09-27; whether it predates FraudShield-EAC's public release must be checked against FraudShield-EAC's own release date. Authors state "not yet been validated against real mobile money operator data". |
| 4 | M-Pesa Transactions Fraud Detection (Caleb Boen) | https://www.kaggle.com/datasets/calebboen/mpesa-transactions-fraud | Kaggle | updated 2026-02-06 | Synthetic | Kenya (regions Nairobi, Mombasa, Kisumu, Nakuru, Eldoret) | is_fraud, ~3% fraud, 120,000 txns | CC0 | YES on the letter of the criteria (public, labelled, Kenya). Educational toy dataset; no paper/documentation of calibration. |
| 5 | Mobile Money Fraud (Africa) — electricsheepafrica/africa-mobile-money-fraud-dataset | https://huggingface.co/datasets/electricsheepafrica/africa-mobile-money-fraud-dataset | Hugging Face | created 2026-05-05 | Synthetic (card says "Synthetic: All records based on real-world research data") | Pan-African; sampled rows include Kenya, Uganda, Tanzania (plus Ghana, Nigeria, Zambia, Côte d'Ivoire, Zimbabwe); is_east_africa column | label, fraud_type (SIM swap, agent collusion, PIN theft, ...); 10,000 rows "balanced 50/50" | licence inconsistent ("other" vs "mit") | YES on the letter (pan-African incl. EAC countries); weak provenance, generation method undocumented. |
| 6 | AmmonBelyon/fraud-detection-kenya (data/kenya_fraud_dataset.csv) | https://github.com/AmmonBelyon/fraud-detection-kenya | GitHub | created 2026-06-05 | Synthetic | Kenya (M-Pesa, bank, KRA) | implied fraud labels (supervised XGBoost); contents not opened | no licence | MARGINAL — public file, student project, no licence/documentation; labels not directly verified. |
| 7 | PaySim (Kaggle ealaxi/paysim1) | https://www.kaggle.com/datasets/ealaxi/paysim1 | Kaggle | 2016/2017 | Synthetic | unnamed "African country" | isFraud | Kaggle (licence not verified) | NO for East Africa specifically (country unnamed); YES as a pan-African/unspecified-African neighbour. |
| 8 | SAML-D | Kaggle berkanoztas/synthetic-transaction-monitoring-dataset-aml | Kaggle | 2023 | Synthetic | UK-centred; no East African country found in paper | Is Suspicious | public | NO |
| 9 | Swahili SMS Detection Dataset (Henry Dioniz) | https://www.kaggle.com/datasets/henrydioniz/swahili-sms-detection-dataset | Kaggle | 2024 | Real SMS | Tanzania | scam patterns (labels not shown on index page) | MIT | ADJACENT (SMS text, not transactions) |
| 10 | MOZ-Smishing | https://aclanthology.org/2025.africanlp-1.23 | ACL Anthology / HF (link placeholder) | 2025 | Real SMS | Mozambique (not EAC) | ham/smishing | "open license" | ADJACENT (SMS; non-EAC) |
| 11 | Consumer Protection Survey of Digital Finance Users: Kenya / Uganda; Financial Consumer Protection Surveys (KE/TZ/UG) | Harvard Dataverse (via search API) | Harvard Dataverse | 2021 / 2026 | Real survey | Kenya, Uganda, Tanzania | self-reported fraud experience, not transactions | public | NO (survey, not transaction labels) |
| 12 | NSMMF_fraud_Dataset (Eleojo N) | https://www.kaggle.com/datasets/elenwai/nsmmf-synthetic-fraud-dataset | Kaggle | 2026 | Synthetic | Nigeria | 7 fraud scenarios | CC0 | NO (not East Africa) — Sub-Saharan neighbour |
| 13 | Nigeria NIBSS-calibrated synthetic fraud benchmark | https://lagjma.unilag.edu.ng/article/view/2874 | UNILAG journal | 2026 | Synthetic | Nigeria | fraud | not examined | NO (not East Africa) |
| 14 | electricsheepafrica Nigerian financial/telecom fraud sets; CiferAI Fraud-Detection-Dataset-AF (PaySim-based, no region) | HF | Hugging Face | 2025-2026 | Synthetic | Nigeria / unstated | fraud | various | NO |

Excluded after inspection: Zindi IndabaX DRC 2022 (Tunisian STEG electricity billing fraud, not DRC data); Zindi SUPCOM Tunisian (tax fraud); arXiv 2608.15447 Rwanda AML (uses SAML-D, releases no Rwandan data); arXiv 2606.17555 (release/country not stated); AAU Ethiopia thesis (release not stated); Kaggle mpesa_fraud_complaints dummy data (1,186 bytes, no labels described); UBOS mobile-money aggregates on HF (no labels); Brian-Mbuya/mpesa-fraud-detection (no data files).

## 5. Totals
- Sources attempted: 19 distinct sources/endpoints (general web search; Kaggle; Hugging Face; Zenodo; Papers with Code; OpenML; UCI; GitHub; IEEE DataPort; Mendeley Data; Harvard Dataverse; Zindi; openAFRICA; Kenya Open Data; Rwanda NISR microdata; Lacuna Fund; AfricArXiv; Google Dataset Search; CEGA project page). Effectively queried (returned a usable result list for the query): general web, Hugging Face API, Zenodo API (all-types query only), OpenML (tag query), GitHub API, Harvard Dataverse API, Zindi (via web search + direct pages), Rwanda NISR, Lacuna Fund, Google Dataset Search. Only via site:-restricted/keyword web search (filter often not honoured): Kaggle, Mendeley Data, IEEE DataPort, Kenya Open Data, AfricArXiv, openAFRICA, UCI. Not queryable: Papers with Code (shut down, redirects to HF), openAFRICA API (403), IEEE DataPort search (404), Zenodo dataset-type filter (400 x2).
- Queries: 29 WebSearch queries (Q1-Q29) and ~59 WebFetch/API calls (F1-F59, including failures).
- Wall clock: start 2026-10-19T16:07:47Z, end 2026-10-19T16:19:48Z (measured with `date -u`).

## 6. Conclusion for C-8

Outcome: FOUND PRIOR DATASETS. The evidence does NOT support C-8 as worded ("the first public East African financial fraud benchmark dataset").

- At least two public, labelled, East-Africa-specific financial fraud datasets predate 2026 regardless of FraudShield-EAC's release date:
  1. Zindi Xente Fraud Detection Challenge (2019): REAL Ugandan payment transactions with a FraudResult label, distributed publicly (after accepting terms) as a competition benchmark with a leaderboard.
  2. MoMTSim Synthetic Mobile Money Transaction Dataset (Mendeley Data, Oct 2024, CC BY 4.0; Data in Brief 2025): a SYNTHETIC, agent-based, labelled mobile-money fraud dataset from Makerere University framed for Uganda / East Africa — the closest methodological neighbour to FraudShield-EAC (also simulator-based).
- Further 2026 items (Kaggle M-Pesa/Kenya synthetic, Feb 2026; HF electricsheepafrica pan-African incl. KE/UG/TZ, May 2026; GitHub Kenya synthetic, Jun 2026; Zenodo MoMTSim-TZ Tanzania, 27 Sep 2026) may or may not predate FraudShield-EAC depending on its public release date, which this search did not establish.
- PaySim is calibrated to an unnamed African country (not stated to be East African); it does not by itself refute C-8 but is a relevant African-calibrated predecessor.

What the evidence could still support (narrower claims; each would need its own verification against the hits above): e.g. a claim restricted to multi-country EAC coverage AND multi-channel scope (mobile money + USSD + agent banking + card + online + bank transfer) in one benchmark. No hit in this search combined multi-country EAC coverage with all of those channels; the closest are electricsheepafrica (pan-African, mobile money only, 10k rows, weak provenance) and MoMTSim/MoMTSim-TZ (single country, mobile money only). That is absence of evidence under this search, not proof of absence.

## 7. Limitations
- Language: English-only queries. No French (Burundi, DRC, Rwanda), Swahili, Kinyarwanda, Amharic or Somali queries were run.
- Kaggle's own search is JS/login-gated; Kaggle coverage came only via Google Dataset Search and web search, and individual Kaggle pages could not be read by fetch (PaySim, BankSim, SAML-D pages returned title only). Kaggle has many PaySim-derivative uploads; not all were inspected.
- site: filters were frequently ignored by the WebSearch engine (Kaggle, HF, Kenya Open Data, UCI), so those "site-restricted" queries are effectively general web searches.
- Failed/unavailable: Zenodo dataset-type queries (HTTP 400 x2), openAFRICA API (403), IEEE DataPort search (404), Papers with Code (discontinued), UCI search (no results returned by either endpoint — not a valid negative), OpenML name query (412), HF statistics endpoint (500), HF global-dataset-lab/global-fintech-fraud-transactions (401 gated; not characterised), CEGA page (no dataset info).
- Truncation: several result lists were only partially read (Zenodo F8 first 14; HF fraud list 200 of possibly more; GitHub 16-18 of 37-88 per query; Google Dataset Search 20 of 25/24/100+).
- Paywalls: AFRICOMM 2023 Springer paper (doi 10.1007/978-3-031-81573-7_16) full text not read; Azamuke PhD thesis not read beyond abstract page.
- Google Dataset Search recall is incomplete (Xente and MoMTSim did not surface for "fraud Uganda").
- The 2016 PaySim quote is from the EMSS 2016 PDF text extraction; the "one month" and "multinational company ... 14 countries" wording reported for the Kaggle description was seen only in search summaries/mirror (finclip) and is NOT quoted here as verified.
- Per-query timestamps not captured (see timing note).
