# C-8 structured literature and dataset search record — PASS 2 (gap closure)

> Committed on 2026-10-19 as the search agent wrote it, except for this note and the first-pass reference below. Paths under `scratchpad/` name the session's temporary directory, which is not kept; the raw API responses are not in the repository. The first pass is `c8_dataset_search.md`, the second `c8_dataset_search_pass2.md`.

Claim under test (C-8): "FraudShield-EAC is the first public East African financial fraud benchmark dataset."
First-pass record: `c8_dataset_search.md` (written to scratchpad/c8_search_record.md, not modified by this pass) (2026-10-19T16:07:47Z to 16:19:48Z).

Search start (UTC): 2026-10-19T16:23:08Z (measured with `date -u`)
Searcher: Claude agent (WebSearch, WebFetch, curl against public APIs). No repository files modified.
All API calls went through a helper script that stamped `date -u` right before each request and appended
"ID | UTC | HTTP status | URL" to scratchpad/p2/out/log.txt. Raw responses are in scratchpad/p2/out/.
For WebSearch/WebFetch calls, `date -u` ran in the same tool batch, immediately before the queries. Where several
queries share one timestamp, they were sent in parallel at that instant (stated per row).

## 1. Protocol

### Research question (unchanged)
Which public, labelled financial / payment / mobile-money fraud datasets or benchmarks (real or synthetic) cover East Africa or an East African country?

### Inclusion / exclusion criteria
Same as pass 1 (public; transaction- or account-level fraud/scam/AML labels or a fraud benchmark; real or synthetic; covers or is calibrated to >=1 East African country: Kenya, Uganda, Tanzania, Rwanda, Burundi, South Sudan, DRC, Somalia, Ethiopia). SMS/text, surveys, unlabelled transactions = ADJACENT, not qualifying.
Pass-1 qualifying items (Xente/Zindi 2019, Uganda; MoMTSim/Mendeley 2024 + Data in Brief 2025) are NOT re-litigated. Only NEW qualifying or adjacent hits are recorded in the results table, plus new facts about the pass-1 open items (AFRICOMM paper, arXiv 2606.17555).

### Gaps targeted (from pass-1 limitations)
G1 non-English queries (French, Swahili, Kinyarwanda, Amharic, Somali);
G2 Zenodo dataset-type filtered queries;
G3 Kaggle public API;
G4 Semantic Scholar + OpenAlex scholarly APIs;
G5 AFRICOMM 2023 MoMTSim paper (doi 10.1007/978-3-031-81573-7_16);
G6 arXiv 2606.17555 full text;
G7 Hugging Face API + GitHub searches.

## 2. Query log
(ID | UTC time | source | exact query / URL | results examined | relevant hits)

### G2 — Zenodo (REST API, type=dataset)
Z1 | 16:23:27Z | Zenodo | `https://zenodo.org/api/records?q=fraud%20AND%20Kenya&type=dataset&size=50` | HTTP 400 | FINDING: the 400 is a validation error, not a rate limit. Body: "Page size cannot be greater than 25. Please use authenticated requests to increase the limit to 100." (A `retry-after: 60` header was present, but x-ratelimit-remaining was 29 of 30.) The pass-1 failures F9/F15 used size=50, so they most likely failed for this reason rather than rate limiting (inferred: same error class, size=50 in both).
Z2 | 16:23:35Z | Zenodo | q=`fraud AND Kenya`, type=dataset, size=25 | total 2, both examined | none (Kenya Post Office Savings Bank 1909–1960 historical data; "Intrinsic honesty..." behavioural experiment).
Z3 | 16:23:39Z | Zenodo | q=`fraud AND Uganda` | total 0 | none.
Z4 | 16:23:42Z | Zenodo | q=`fraud AND Tanzania` | total 3, all examined | none (the honesty experiment; legal thesis "Technology-related bank fraud ... legal framework in Tanzania" 2026-10-01; e-tendering thesis).
Z5 | 16:23:47Z | Zenodo | q=`fraud AND Rwanda` | total 0 | none.
Z6 | 16:23:51Z | Zenodo | q=`fraud AND Burundi` | total 0 | none.
Z7 | 16:23:54Z | Zenodo | q=`fraud AND Ethiopia` | total 0 | none.
Z8 | 16:23:58Z | Zenodo | q=`fraud AND "mobile money"` | total 5, all examined | MoMTSim-TZ (22997330, already in pass 1); Mobile Money Phone Scam Survey Dataset (23301466; Cameroon survey — adjacent, non-EAC); "Stop Arnaque 237: Cameroon Scam Reports Dataset" (23287618; Cameroon scam reports — adjacent, non-EAC); 17599466 (an essay PDF on cybercrime in Africa filed as dataset — not data); NEW: AFRIDATACYBERJOHN (22934273) — examined in detail below (Z8a).
Z8a | 16:24:19Z | Zenodo file | downloaded `AFRIDATACYBERJOHN_v1.0.zip` (72 KB) from record 22934273 into its own dir; read README, DATASET_DESCRIPTION, CSVs with a script | Countries in event CSVs: Tanzania, South_Africa, Kenya, Uganda, Nigeria, Ghana. BUT every row has `is_attack = 0` and `ground_truth_state = NORMAL` (1,029 authoritative + 1,060 current events, counted). README: "The released current window population represents hard-negative normal behaviour." → NO positive fraud labels → ADJACENT, does not qualify.
Z9 | 16:24:02Z | Zenodo | q=`fraud AND (mpesa OR "M-Pesa")` | total 0 | none.
Z10 | 16:32:41Z | Zenodo | q=`"mobile money" AND (Kenya OR Uganda OR Tanzania OR Rwanda OR Burundi OR Ethiopia)` | total 3, all examined | none (survey data TZ/RW; mobile money and savings in Uganda; Kenyan betting position paper).
Z11 | 16:32:45Z | Zenodo | q=`scam AND (Kenya OR Uganda OR Tanzania OR Rwanda)` | total 1 | spam record; none.
Z12 | 16:32:49Z | Zenodo | q=`"money laundering" AND Africa` | total 1 | "A Context-Aware Synthetic South African Retail Anti-Money Laundering Dataset" (22803333, 2026-09-17) — South Africa, not East Africa; NOT examined further.
Z13 | 16:32:54Z | Zenodo | q=`fraud AND "East Africa"` | total 0 | none.
Z14 | 16:32:57Z | Zenodo | q=`MoMTSim` | total 1 | MoMTSim-TZ only (already known).

### G3 — Kaggle public API (no authentication)
The endpoint `https://www.kaggle.com/api/v1/datasets/list?search=<term>` returned HTTP 200 JSON without authentication (20 results per page). `.../api/v1/datasets/metadata/<owner>/<slug>` also worked without authentication and returns the full description text.
K1 | 16:24:37Z | Kaggle API | search=`mobile money fraud` (page 1) | 20 examined | known: ealaxi/paysim1, calebboen/mpesa-transactions-fraud (Kenya), elenwai/nsmmf (Nigeria), denishazamuke MoMTSim mirror, togbemismadjisosthne phone-scam survey (Cameroon). New to check: sriharshaeedala/financial-fraud-detection-dataset, etiennelandryebel/mobile-money-fraud-detection, asmahangreat/oromo-mobile-fraud-behavioral-dataset. Others are PaySim copies or unrelated (Indian banking, e-commerce, salary).
K2 | 16:24:40Z | Kaggle API | search=`mpesa` | 2 | both already in pass 1 (pauletwanjiru dummy complaints; calebboen Kenya).
K3 | 16:24:43Z | Kaggle API | search=`uganda fraud` | 2 | Techsalerator UBO/KYB company-registry samples — not fraud labels; none.
K4 | 16:24:45Z | Kaggle API | search=`kenya fraud` | 7 | calebboen (known); motor insurance Kenya (insurance, no fraud label stated in title; not examined); Techsalerator samples; none new.
K5 | 16:24:48Z | Kaggle API | search=`tanzania fraud` | 3 | Techsalerator samples only; none.
K6 | 16:24:51Z | Kaggle API | search=`rwanda fraud` | 4 | healthcare provider fraud (US), Techsalerator samples; none.
K7 | 16:24:54Z | Kaggle API | search=`momo fraud` | 0 | none.
K8 | 16:25:11Z | Kaggle API | search=`mobile money fraud` page 2 | 10 | NEW to check: yahayaidrisu/coordinated-mobile-money-dataset. Others: India/US card sets, PaySim copy, Roman Urdu wallet utterances; none East African.
K9 | 16:25:14Z | Kaggle API | search=`ethiopia fraud` | 3 | Techsalerator samples; none.
K10 | 16:25:17Z | Kaggle API | search=`telebirr` | 1 | multilingual function-call dataset; none.
K11 | 16:25:19Z | Kaggle API | search=`airtel money` | 3 | Bangladesh telecom packages etc.; none.
K12 | 16:25:22Z | Kaggle API | search=`east africa fraud` | 1 | Oromo-Mobile-Fraud-Behavioral-Dataset (see KM1).
K13 | 16:25:25Z | Kaggle API | search=`mobile money africa` | 11 | NEW to check: hassankanju/fintech-transactions. Also FinAccess 2024 Kenya survey and Tanzania SACCOS data (no fraud labels; adjacent). 
K14 | 16:25:27Z | Kaggle API | search=`burundi` | 20 | none fraud-related.
KM1 | 16:25:38Z | Kaggle metadata | asmahangreat/oromo-mobile-fraud-behavioral-dataset | description read | Text dataset: "Original Oromo messages paired with English summaries", fraud types/signals/intent, channels "SMS, USSD, and app-based chats". Description does not name a country (Afaan Oromo is spoken mainly in Ethiopia — inferred, not stated). → ADJACENT (message text, not transactions).
KM2 | 16:25:46Z | Kaggle metadata | yahayaidrisu/coordinated-mobile-money-dataset | read | Synthetic, 450,053 events, fraud_label; description: "must therefore not be described as real telecom, banking, fintech, or Ghanaian mobile money customer data" → Ghana framing, not East Africa. NO.
KM3 | 16:25:48Z | Kaggle metadata | etiennelandryebel/mobile-money-fraud-detection | read | "more than 6 million lines ... 11 variables, from Mobile Money transactions in an African country" (PaySim-like size/wording; country unnamed). NO for East Africa.
KM4 | 16:25:50Z | Kaggle metadata | hassankanju/fintech-transactions | read | synthetic mobile-money retail transactions "popular in several African countries, including Tanzania, Kenya, South Africa, and Nigeria"; built for customer segmentation; no fraud label described → ADJACENT (no labels).
KM5 | 16:25:52Z | Kaggle metadata | sriharshaeedala/financial-fraud-detection-dataset | read | "Derived from a simulator named PaySim ... a mobile money service in an African country" — PaySim copy. NO. (Its description also carries the "multinational company ... more than 14 countries" wording that pass 1 could not verify from Kaggle: now seen in this Kaggle description text, a PaySim re-upload — not the original ealaxi page.)
KM6 | 16:25:54Z | Kaggle metadata | calebboen/mpesa-transactions-fraud | read | confirms pass-1 entry: "simulates real-world Kenyan mobile money transactions (M-Pesa style) for machine learning education and research"; 120,000 txns, ~3% fraud, is_fraud; CC0. (Already in pass 1; no change.)

### G7 — Hugging Face API
H1 | 16:26:10Z | HF API | `api/datasets?search=mpesa&limit=100` | 2 | kachielite2810/mpesa-sms-dataset (files mpesa_basic.jsonl, mpesa_instruct.jsonl; no README — H18 404, H23 metadata only; SMS/instruction text, not examined further → ADJACENT at most); smainye/mpesa-loan-repayment-profiles (loans, not fraud).
H2 | 16:26:11Z | HF API | search=`uganda`, limit=100 | 100 (truncated at limit) | none fraud-related in the 100 returned.
H3 | 16:26:13Z | HF API | search=`kenya fraud` | 0 | none.
H4 | 16:26:14Z | HF API | search=`tanzania`, limit=100 | 100 (truncated) | none fraud-related.
H5 | 16:26:16Z | HF API | search=`kenya`, limit=100 | 100 (truncated) | none fraud-related.
H6 | 16:26:17Z | HF API | search=`rwanda`, limit=100 | 100 (truncated) | none fraud-related.
H7 | 16:26:19Z | HF API | search=`ethiopia fraud` | 0 | none.
H8 | 16:26:21Z | HF API | search=`swahili fraud` | 0 | none.
H9 | 16:26:22Z | HF API | search=`airtel` | 5 | none relevant.
H10 | 16:26:23Z | HF API | search=`momo`, limit=100 | 100 | all unrelated (anime, LLM leaderboard, etc.).
H11-H13 | 16:26:34Z-16:26:37Z | HF API | search=`uganda fraud`, `tanzania fraud`, `rwanda fraud` | 0, 0, 0 | none.
H14 | 16:26:38Z | HF API | search=`east africa` | 25 | NEW to check: 1011-Labs/zazupay-east-africa-unbanked-synthetic. Others unrelated.
H15 | 16:26:40Z | HF API | search=`fraud africa` | 5 | electricsheepafrica: africa-synth-insurance-fraud-detection-all, africa-mobile-money-fraud-dataset (pass 1), NEW: africa-crypto-fraud, africa-deepfake-identity-fraud, africa-identity-fraud-national-id.
H16 | 16:26:41Z | HF API | search=`scam africa` | 0 | none.
H17 | 16:26:43Z | HF API | search=`mobile-money` | 10 | Nigeria synthetic telecom/mobile-money records (Nigeria); pass-1 items; none new East African.
H19 | 16:26:52Z | HF raw README zazupay | HTTP 404 (no README).
H20-H22 | 16:26:53Z-16:26:54Z | HF READMEs electricsheepafrica crypto / identity / deepfake | read | each: "Acquisition: SYNTHETIC Rows: 10,000 (balanced 50/50) Synthetic: All records are synthetic (is_synthetic=1), based on real-world research data." license: other.
H24 | 16:27:03Z | HF datasets-server rows, africa-crypto-fraud, offset 0 len 100 | 100 rows | `country` values in these 100 rows include Kenya 11, Tanzania 8, Uganda 3, Rwanda 1 (also Nigeria, South Africa, Ghana, Zambia, ...); `label` = 1 for all 100 rows shown (first 100 only; card says balanced 50/50); `is_east_africa` column present. Records are crypto-scam victim cases (ponzi, fake exchange, pig butchering, SIM-swap wallet theft...), not payment transactions.
H25 | 16:27:06Z | HF API metadata zazupay | files customers_seed.csv, transactions_seed.csv, fidelity_report.png; created 2026-02-19.
H25a | 16:27:15Z | HTTP range read (first 3 KB) of zazupay transactions_seed.csv | header `transaction_id,customer_id,amount_ksh,transaction_type,channel,timestamp,is_reversal`; channels M_PESA etc. → Kenyan shilling M-Pesa-style synthetic transactions with NO fraud label → ADJACENT (no labels).
H26 | 16:27:17Z | HF API metadata africa-crypto-fraud | createdAt 2026-05-11, lastModified 2026-08-12, license "other".

### G7 — GitHub REST search
GH1 | 16:27:24Z | GitHub | q=`momo fraud dataset` | total 1 | valentin396/mobile-money-fraud-detection- (created 2026-09-07): README says data is generated inside a notebook; Rwanda/Africa framing; no data files in tree → NO.
GH2 | 16:27:28Z | GitHub | q=`airtel money fraud` | total 3, all examined | receipt-checker app, reconciliation platform, M-Pesa automation kit — no datasets.
GH3 | 16:27:32Z | GitHub | q=`mobile money fraud uganda` | total 2 | jeydanielawany/MOMO-Scam-Checker---Uganda (app); arikos/uganda-fraud-app (no data files in tree; README does not name a data source) → NO.
GH4 | 16:27:36Z | GitHub | q=`mobile money fraud rwanda` | total 1 | jacques-twizeyimana/momo-fraud-ml: README "Dataset (PaySim)" — Rwandan framing on PaySim; no Rwandan data → NO.
GH5 | 16:27:40Z | GitHub | q=`mobile money fraud tanzania` | total 2 | Mdsoln/SMSGuard (SMS app), rental marketplace → NO.
GH6 | 16:27:44Z | GitHub | q=`fraud detection ethiopia` | total 3 | NBE-Ethiopia-AML-Detection (system code), Telegram analytics, Ethio-Cyber-Shield — none publishes a labelled dataset in description; not examined further.
GH7 | 16:27:48Z | GitHub | q=`telebirr fraud` | total 5 | bisrattesiyat/mobile-money-fraud-detection (no data files in tree); hackathon/backends → NO.
(GitHub tree + README checks for the four repos above: 16:28:02Z–16:28:15Z.)

### G4 — Semantic Scholar API
SS1-SS6 | 16:28:23Z-16:28:52Z | Semantic Scholar `graph/v1/paper/search?query=...&fields=title,year,externalIds,abstract&limit=50` for the six required queries | HTTP 429 (all six) | FAILED.
SSr*/SSrr* | from 16:30:09Z, spaced 60 s apart with an extra 30 s retry each | same six queries | see section 2a (filled in after the retry job finished).

### G4 — OpenAlex API (works search, 50 per query)
NOTE ON CONDUCT: OA1–OA6 were sent with a `mailto=` parameter that contained the user's e-mail address (OpenAlex "polite pool"). That was a mistake: the address should not have been sent to a third-party service. Later OpenAlex calls (OAW1–OAW7) did not include it.
OA1 | 16:29:09Z | OpenAlex | search=`mobile money fraud dataset` | count 14,828; first 50 examined (titles) | known: AFRICOMM MoMTSim paper, MOZ-Smishing, Data in Brief MoMTSim, FAMECSE'22 design paper, MoMTSim-TZ, Cameroon scam survey. New titles: "MobileTransfer: Cross-Dataset Transfer Evaluation for Mobile-Money Fraud Detection" (IEEE IRI 2026, doi 10.1109/iri69576.2026.00040) — no abstract in OpenAlex (OAW1) and not found by web search (Q-MT) → release unknown; "Fraud Detection in Mobile Money Transaction: A Data Mining Approach" (2019 dissertation, no DOI; not examined). Others are PaySim-based or Nigeria/Ghana-focused.
OA2 | 16:29:14Z | OpenAlex | search=`fraud detection Kenya dataset` | count 2,549; first 50 examined | no paper releasing a labelled Kenyan transaction dataset in titles. Adjacent: "AI Against Smishing in Kenya" (IEEE 2025, SMS); "Applying machine learning to enhance fraud detection in Kenyan digital banking" (2025 dissertation; no DOI; release not determinable from title — not examined).
OA3 | 16:29:17Z | OpenAlex | search=`fraud detection Uganda dataset` | count 1,073; first 50 examined | arXiv 2606.17555 (see G6); "Fraudulent Detection Model ... USSD" (2021; OAW4 abstract: bank USSD data, country not stated in abstract, no release stated). None qualifies.
OA4 | 16:29:20Z | OpenAlex | search=`fraud detection Tanzania dataset` | count 989; first 50 examined | MoMTSim-TZ (known); "Machine Learning-based Approach for Scam Message Detection in Kiswahili" (EAJIT 2025, SMS, adjacent); "A Machine-Learning Model for Phishing Detection in Swahili Messages: A Case of Tanzania" (EAJIT 2026, SMS, adjacent); "...Fraud Detection in Synthetic Mobile Financial Transactions" (OAW5: uses PaySim — NO).
OA5 | 16:29:22Z | OpenAlex | search=`fraud detection Rwanda` | count 2,096; first 50 examined | arXiv 2608.15447 (known: no Rwandan data released); "Value added tax fraud detection ... Rwanda 2016-2019" (2020 dissertation; tax, not payments); none qualifies.
OA6 | 16:29:25Z | OpenAlex | search=`synthetic mobile money transactions` | count 11,026; first 50 examined | MoMTSim family: IEEE Access 2024 "MoMTSim: A Multi-Agent-Based Simulation Platform Calibrated for Mobile Money Transactions" (OAW2), "MoMTSimDP" (SEIGS 2025, OAW6); IBM AML (NeurIPS 2023); SAML-D; "A synthetic data set to benchmark anti-money laundering methods" (Sci Data 2023, not East African by title); "Synthetic Data of Transactions for Inmediate Loans' Fraud" (Zenodo 2022, not examined; no East African term in title).
OAW1-OAW7 | 16:29:48Z-16:30:00Z | OpenAlex single-work lookups by DOI (no mailto) | 7 | abstracts read where present. OAW2 (MoMTSim IEEE Access 2024, open access) and OAW6 (MoMTSimDP) describe calibration to "real transaction data" without naming a country in the abstract and do not state a new data release in the abstract. OAW3 (AFRICOMM paper): no abstract in OpenAlex.

### G5 — AFRICOMM 2023 paper (doi 10.1007/978-3-031-81573-7_16)
F-S1 | 16:30:33Z | WebFetch link.springer.com chapter | 303 redirect to idp.springer.com (cookie auth) — not followed.
F-S2 | 16:30:42Z | curl with cookie jar, following redirects | HTTP 200 but body is a "Client Challenge ... JavaScript is disabled" page — abstract NOT obtained.
CR1 | 16:30:57Z | Crossref API works/10.1007/978-3-031-81573-7_16 | OK | Title, authors Denish Azamuke, Marriette Katarahweire, Engineer Bainomugisha; container "Towards new e-Infrastructure and e-Services for Developing Countries" (LNICST); year 2025; NO abstract deposited; 46 references, including "MoMTSim, Inc: Mobile Money Transaction Simulator (2023). https://github.com/aiinfinancegroup/MoMTSim, version 0.1.0". No Mendeley/Zenodo/Kaggle data reference among the references matched (grep for mendeley, data in brief, zenodo, kaggle, github, paysim, momtsim).
Q-A1 | 16:30:57Z (same batch as CR1) | WebSearch | `"Financial Fraud Detection Using Rich Mobile Money Transaction Datasets" Azamuke` | 10 | EUDL page, researchr, Mendeley set, UNCST repository. No open full text found. The search-engine summary said the paper "uses MoMTSim ... to model known fraud scenarios in Sub-Saharan mobile money" — this is the engine's summary, not a quote I verified from the paper.
F-S3 | 16:31:11Z | WebFetch https://eudl.eu/doi/10.1007/978-3-031-81573-7_16 | HTTP 403 — FAILED.
GHM1-GHM2 | 16:31:11Z-16:31:12Z | GitHub API repo aiinfinancegroup/MoMTSim + search q=MoMTSim | 6 repos | aiinfinancegroup/MoMTSim (created 2023-02-13, GPL-3.0, "A differentially private mobile money transaction simulator"); MoMTSim_JAR; MoMTSim-Library (2026-06-03); three third-party repos using the MoMTSim dataset (French-language descriptions; Dr-MKO-4, blakro).
GHM3 | 16:31:21Z-16:31:30Z | trees + READMEs of aiinfinancegroup/MoMTSim and MoMTSim-Library | README: "MoMTSim is a multi-agent simulation platform tailored to mobile money financial transactions from Sub-Saharan Africa (SSA)". Repo ships `paramFiles/*.csv` (aggregated calibration parameters) and regression-test logs `src/test/resources/test_rawLog.csv.gz` (~7.9 MB) and `seed_100000_win32.csv.gz`. 
GHM4 | 16:31:39Z | partial download (first 20 KB) of test_rawLog.csv.gz | header-less PaySim-style rows (step, type, amount, ids, balances, trailing 0/1 flag columns). These are simulator test fixtures, not a documented dataset; no country named. → ADJACENT only.
Outcome G5: full text and abstract NOT obtained (Springer JS challenge, EUDL 403, no Crossref/OpenAlex abstract, no open copy found). Whether the AFRICOMM paper releases data beyond the Mendeley set is UNDETERMINED; the only public artefacts linked to it that I could verify are the GitHub simulator (code + calibration parameter files + test fixtures) and the Mendeley set already in pass 1.

### G6 — arXiv 2606.17555 (Walusimbi & Ssentongo)
F-A1 | 16:30:09Z | curl https://arxiv.org/pdf/2606.17555 | HTTP 200, 212,639 bytes, v4 (8 Oct 2026); text extracted with pdftotext (443 lines) and read in the relevant sections.
Findings (quotes from the extracted text):
- Affiliations: Soroti University, Soroti, Uganda, and "Arapai Technologies International Limited, Kampala, Uganda".
- Geography: section "D. Banking Fraud in Uganda and East Africa"; "These regional characteristics directly inform the threat model in Section III and the calibration of the simulation". So the synthetic generator is motivated by Uganda / East Africa (and also cites Nigerian FITC data), but no country is named as the source of calibration data.
- Data: "To evaluate the agent in the absence of a publicly labelled bank event log covering all 13 threat categories with ground-truth labels, we follow established practice in fraud ML research of using a synthetic evaluation dataset". 3,470 accounts, 90 simulated days, 237,669 transactions (12,474 attack), 113,508 sessions (1,645 attack).
- Release: no data/code availability statement; grep for github|zenodo|data availability|publicly available|will be released|code is available = 0 matches. Conflict of interest: "The framework described in this paper is intended for future commercialisation through this entity."
- Limitations section: "Retraining the agent on real labelled data from an East African partner institution before production deployment is essential."
Outcome G6: Uganda/East-Africa-motivated synthetic bank-fraud log; NOT released → does NOT qualify (excluded: not public).

### G1 — Non-English web searches
Q-F1..Q-F4 | 16:31:49Z (4 parallel queries) | WebSearch (French) | `jeu de données fraude mobile money transactions étiquetées` (9 results); `détection de fraude transactions mobile money Rwanda jeu de données` (10); `détection de fraude Burundi Lumicash Ecocash données transactions apprentissage automatique` (9); `détection de fraude RDC mobile money M-Pesa Airtel Money Orange Money jeu de données mémoire` (9) | 37 | No French-language dataset found. Results repeated PaySim, MoMTSim (Mendeley), arXiv 2608.15447 (Rwanda AML, no data released), IGI papers on visualisation for mobile-money-transfer fraud (not examined), generic vendor pages. Burundi query: only generic fraud-ML pages. DRC query: regulator/news pages, nothing DRC-specific.
Q-S1..Q-S2, Q-K1, Q-Am1, Q-So1 | 16:32:09Z (5 parallel queries) | WebSearch | Swahili `seti ya data udanganyifu pesa kwa simu` (10); Swahili `M-Pesa ulaghai data utambuzi wa miamala ya udanganyifu` (9); Kinyarwanda `uburiganya mobile money amakuru dataset Rwanda MoMo` (9); Amharic+English `telebirr ማጭበርበር dataset fraud detection Ethiopia mobile money` (9); Somali+English `khiyaano lacag mobile money EVC Plus fraud dataset Somalia` (9) | 46 | Swahili: no Swahili-language dataset; results repeated MoMTSim, MOZ-Smishing, PaySim; Safaricom news; an HF Space demo "Full-Stack M-Pesa Fraud Detection MVP" (stanley03-stano-fraud.static.hf.space; an app, not a dataset; not examined further). Kinyarwanda: IGC report on one month of MoMo Rwanda transaction data used with BNR/NISR (economic measurement; not public, not fraud-labelled). Amharic: AAU theses (Ethiopia mobile banking/mobile money fraud; data described as bank data, not public on available evidence; SIM-box CDR theses, telecom). Somali: no dataset; CEGA project page surfaced (F-C1).
Q-Am2, Q-S3, Q-K2 | 16:32:24Z (3 parallel queries) | WebSearch | pure Amharic `የሞባይል ገንዘብ ማጭበርበር መረጃ ስብስብ` (two result sets, 9+9); pure Swahili `utambuzi wa udanganyifu katika miamala ya pesa kwa simu kwa kutumia ujifunzaji wa mashine Tanzania` (9); pure Kinyarwanda `kumenya uburiganya muri mobile money ubwenge buhangano` (10) | ~37 | Amharic: Ethiopian news on mobile-banking fraud volumes (Shega, The Reporter, Fortune) — statistics, no dataset. Swahili: TCRA quarterly statistics (counts, no fraud labels) and market pages. Kinyarwanda: English vendor blogs; no Kinyarwanda-language results.
F-C1 | 16:32:24Z (same batch) | WebFetch https://cega.berkeley.edu/collection/identifying-fraud-patterns-and-financial-exclusion-with-privacy-preserving-synthetic-data/ | OK | lists "Uganda" under Countries; researchers Bainomugisha, Katarahweire, Azamuke; synthetic data matched to "a real sample dataset obtained from an implementing partner organization"; fraud types "split deposits, refund fraud, and PIN fraud"; no download link and no release statement on the page. (This is the MoMTSim project; it supports the pass-1 Uganda reading of MoMTSim but adds no new dataset.)
Q-MT | 16:33:09Z | WebSearch | `"MobileTransfer" cross-dataset transfer evaluation mobile-money fraud detection` | 10 | paper not found; release status unknown.

## 2a. Semantic Scholar retry outcome
Retry schedule: 60 s wait before each query, plus one extra retry 30 s later if it got 429 (unauthenticated API, shared rate pool).
SSr1/SSrr1 | 16:30:09Z / 16:30:40Z | `mobile money fraud dataset` | 429 / 429 — FAILED.
SSr2/SSrr2 | 16:31:40Z / 16:32:11Z | `fraud detection Kenya dataset` | 429 / 429 — FAILED.
SSr3/SSrr3 | 16:33:11Z / 16:33:43Z | `fraud detection Uganda dataset` | 429 / 429 — FAILED.
SSr4/SSrr4 | 16:34:43Z / 16:35:15Z | `fraud detection Tanzania dataset` | 429 / HTTP 200: total 7,560, first 50 examined (titles) | none East African except "Integrating ML with Electronic Fiscal Devices for Real-Time Underpricing Detection in Tanzania" (tax underpricing, not payment fraud). The rest are credit-card, telecom, identity-document and insurance fraud papers. No paper releasing a labelled East African fraud dataset.
SSr5/SSrr5 | 16:36:16Z / 16:36:52Z | `fraud detection Rwanda` | 429 / 429 — FAILED.
SSr6/SSrr6 | 16:37:53Z / 16:38:25Z | `synthetic mobile money transactions` | 429 / 429 — FAILED.
Net: Semantic Scholar covered 1 of the 6 required queries. OpenAlex (OA1–OA6) ran all six of them and is the scholarly coverage of record for this pass.

## 3. Results table — NEW hits in pass 2 (qualifying or adjacent)

| # | Title | URL | Host | Date | Real/Synth | Countries (source's own words) | Labels | Licence | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| P2-1 | Cryptocurrency & Digital Asset Fraud (Africa) — electricsheepafrica/africa-crypto-fraud | https://huggingface.co/datasets/electricsheepafrica/africa-crypto-fraud | Hugging Face | created 2026-05-11 | Synthetic ("All records are synthetic (is_synthetic=1), based on real-world research data") | Pan-African; `country` column; in first 100 rows: Kenya, Tanzania, Uganda, Rwanda among others; `is_east_africa` column | `label`, fraud_type (ponzi, pig butchering, fake exchange, SIM-swap wallet theft, ...); card: "10,000 (balanced 50/50)" | "other" | QUALIFIES ON THE LETTER only weakly: labelled, public, includes EAC countries, but these are crypto-scam victim case records rather than payment transactions, and provenance is undocumented. Same publisher and template as the pass-1 electricsheepafrica mobile-money set. |
| P2-2 | AFRIDATACYBERJOHN | https://doi.org/10.5281/zenodo.22934273 | Zenodo | 2026-09-24 | Synthetic | "Africa-centric"; country column: Tanzania, Kenya, Uganda, South_Africa, Nigeria, Ghana | `is_attack` present but all 2,089 events are 0 (NORMAL, hard negatives) | CC BY 4.0 | ADJACENT — no positive labels; not a fraud benchmark as released. |
| P2-3 | Oromo-Mobile-Fraud-Behavioral-Dataset | https://www.kaggle.com/datasets/asmahangreat/oromo-mobile-fraud-behavioral-dataset | Kaggle | 2026-05-03 | not stated | Afaan Oromo language (country not named; Ethiopia inferred) | fraud type / signals / intent on messages | CC0 | ADJACENT — message text (SMS/USSD/chat), not transactions. |
| P2-4 | zazupay-east-africa-unbanked-synthetic | https://huggingface.co/datasets/1011-Labs/zazupay-east-africa-unbanked-synthetic | Hugging Face | 2026-02-19 | Synthetic | KES amounts, M_PESA channel (Kenya inferred from currency/channel; no card) | none (only `is_reversal`) | none stated | ADJACENT — no fraud labels. |
| P2-5 | fintech_transactions (hassankanju) | https://www.kaggle.com/datasets/hassankanju/fintech-transactions | Kaggle | 2026-06-13 | Synthetic | "several African countries, including Tanzania, Kenya, South Africa, and Nigeria" | none described (segmentation) | CC0 | ADJACENT — no fraud labels. |
| P2-6 | MoMTSim simulator repository (code + calibration parameter CSVs + test fixtures) | https://github.com/aiinfinancegroup/MoMTSim | GitHub | created 2023-02-13 | Simulator | "mobile money financial transactions from Sub-Saharan Africa (SSA)" | test logs carry flag columns; undocumented | GPL-3.0 | ADJACENT — a generator, not a released benchmark; relevant as the 2023 public precursor to the Mendeley set. |
| P2-7 | arXiv 2606.17555 synthetic bank log | https://arxiv.org/abs/2606.17555 | arXiv | v4 2026-10-08 | Synthetic | motivated by "Uganda and East Africa"; Ugandan authors | 13 threat categories | not released | EXCLUDED — no data release. |

Non-East-African neighbours seen in pass 2 (not qualifying): yahayaidrisu coordinated mobile money (Ghana framing); Stop Arnaque 237 (Cameroon scam reports); Mobile Money Phone Scam Survey (Cameroon); South African retail AML synthetic (Zenodo 22803333); electricsheepafrica identity/deepfake fraud (identity, not payments; country coverage not checked).

## 4. Totals (pass 2)
- API calls logged by the helper: see scratchpad/p2/out/log.txt (Zenodo 15, Kaggle 21, Hugging Face 26, GitHub 13 search/tree/README + 6 MoMTSim, Semantic Scholar 6 + retries, OpenAlex 13, Crossref 1).
- WebSearch queries: 14 (12 non-English: 4 French, 3 Swahili, 2 Kinyarwanda, 2 Amharic, 1 Somali; plus 2 English follow-ups: AFRICOMM title, MobileTransfer).
- WebFetch calls: 3 (Springer redirect, EUDL 403, CEGA OK). Direct downloads: 4 (AFRIDATACYBERJOHN zip, arXiv PDF, zazupay first 3 KB, MoMTSim test log first 20 KB).

## 5. Conclusion (pass 2)
- Pass 2 found NO new dataset that clearly qualifies as a public, labelled East African payment / mobile-money fraud transaction dataset beyond those already recorded in pass 1 (Xente; MoMTSim; and the 2026 items MoMTSim-TZ, Kaggle M-Pesa Kenya, electricsheepafrica mobile-money, GitHub Kenya student set).
- One new weak on-the-letter hit: electricsheepafrica/africa-crypto-fraud (pan-African synthetic crypto-scam cases, includes Kenya/Tanzania/Uganda/Rwanda rows; created 2026-05-11). It is the same publisher and template as the pass-1 electricsheepafrica item and does not change the C-8 verdict.
- The pass-1 verdict stands and is reinforced: C-8 ("first public East African financial fraud benchmark dataset") is contradicted by Xente (2019, real, Uganda) and MoMTSim (2024, synthetic, Uganda/East Africa). The MoMTSim simulator itself has been public on GitHub since 2023-02-13 (GPL-3.0), and CEGA lists the project's country as Uganda.
- arXiv 2606.17555 does not release data, so it does not affect C-8.
- Non-English searches (French, Swahili, Kinyarwanda, Amharic, Somali) found no additional dataset. This is absence of evidence under these queries, not proof of absence.

## 6. Limitations (remaining after pass 2)
- Semantic Scholar: 5 of 6 queries failed with HTTP 429 even after 12 retries spaced 30–60 s (unauthenticated). Only "fraud detection Tanzania dataset" ran. OpenAlex ran all six, but its relevance ranking differs, so papers that only Semantic Scholar would surface may be missed.
- AFRICOMM 2023 paper: abstract and full text still unread (Springer JS challenge, EUDL 403, no abstract in Crossref/OpenAlex, no open copy found). Whether it releases additional data is undetermined.
- "MobileTransfer" (IEEE IRI 2026) and the 2025/2019 dissertations seen in OpenAlex titles were not examined; whether they release data is unknown.
- Non-English: done through a general web search engine that tends to answer in English and mix in English-language results. Somali and the first Amharic query were mixed with English terms; the pure-Amharic query returned news only. No native-language scholarly databases (e.g. African Journals Online in French, Swahili university repositories) were searched directly. Translations of the query terms are my own and were not checked by a native speaker.
- Hugging Face `search=` for single country names hit the limit of 100 for uganda/tanzania/kenya/rwanda (results truncated); combined "country + fraud" searches returned 0, so a fraud dataset whose id lacks both words would be missed (HF search matches dataset ids, not cards).
- Kaggle API returns 20 per page; only page 1 for most terms (page 2 for "mobile money fraud"). Kaggle search matches titles/subtitles/tags; datasets described only in body text may be missed.
- OpenAlex: only the first 50 results per query (by relevance) were examined, by title; abstracts read only for 7 works.
- GitHub search covers repository names/descriptions/READMEs only (not file contents); only the first page was examined.
- electricsheepafrica/africa-crypto-fraud: only the first 100 rows were inspected (all label=1 there); the 50/50 balance and full country distribution come from the card, not verified.
- Conduct: the user's e-mail address was sent to OpenAlex as a `mailto` parameter in six requests (OA1–OA6). It should not have been.
- Timing: per-query UTC times were captured for every call in this pass (API calls stamped by the helper; web queries by a `date -u` call in the same batch).

## 7. Wall-clock
- Pass 2: start 2026-10-19T16:23:08Z, end 2026-10-19T16:38:37Z (both measured with `date -u`), 15 min 29 s. About 6 min of this was spent spacing out the Semantic Scholar retries in the background while other queries ran.
- Pass 1: 2026-10-19T16:07:47Z to 16:19:48Z, 12 min 01 s.
- Combined search time: 27 min 30 s. Elapsed span from the start of pass 1 to the end of pass 2: 30 min 50 s, which includes the 3 min 20 s between the passes.

## 8. Status: closed (owner, 2026-10-19)

The two passes took about 27 minutes of agent searching, not the day of structured search the owner
asked for. The owner closed the search rather than extending it: C-8 is already refuted (Xente and
MoMTSim predate FraudShield-EAC), so more searching could only change how complete this record is,
not the paper's claim. The main gaps left open are below; the full lists are §6 of this record and
§7 of `c8_dataset_search.md`.

- **Semantic Scholar:** five of six queries were refused (HTTP 429) after spaced retries; only
  "fraud detection Tanzania dataset" ran.
- **The AFRICOMM 2023 MoMTSim paper** (doi 10.1007/978-3-031-81573-7_16): neither abstract nor full
  text could be read, so whether it releases data beyond the Mendeley set is undetermined.
- **Partial result lists:** Kaggle (page 1, page 2 only for "mobile money fraud"), OpenAlex and
  GitHub (first page), Hugging Face country searches (cut at 100), Zenodo (the first pass's
  all-types list to its first 14 records) and Google Dataset Search (20 of each list) were read only
  in part.
- Also: the "MobileTransfer" paper (IEEE IRI 2026) and dissertations seen only by title; non-English
  queries translated by the agent, unchecked by native speakers, through an English-leaning engine.

**Conduct, and the rule it produced.** Six OpenAlex requests (OA1–OA6) carried the user's e-mail
address in a `mailto=` parameter. The owner's ruling: nothing to undo, keep this record, and a
standing rule for search agents — no personal identifiers in outbound query parameters, ever, even
where an API documents them as polite.
