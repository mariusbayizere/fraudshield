# Reference audit

Every entry in `references.bib`, checked against the source itself on **2026-09-22** by the M11
agent. PDFs were downloaded from the URL in the entry and their text extracted (pypdf 5.9.0, run in
a throwaway environment outside the repository); web pages were fetched directly. "Page" means the
number printed on the page; where it differs from the PDF page index, both are given. Each citing
sentence (Section 4, "What the parameters rest on") was compared with what the source says.

The first ten entries (section 1) are web sources and documents without DOIs; each carries its URL
and an access date. The scholarly entries (section 2) carry a DOI wherever one is registered; the
five without one (three NeurIPS papers, one PMLR paper, one USENIX paper) and PaySim carry the
publisher's URL and an access date instead.

## 1. Sources of the generator parameters

| Key | Checked | Where | Result |
|---|---|---|---|
| `botPSAR2024` | Cover title "Payment Systems Annual Report for 2024"; publisher Bank of Tanzania, Dodoma; 76 pages; no publication date printed (entry says n.d.). Table H1 "Mobile Payments" (60,745,698 active users; 6,414 million transactions in 2024; 5,061.20 million in 2023), Tables H2 "Person-to-Person Transfer (P2P)" (479,107,425), H3 and H5 "Personal to Business (P2B)" (1,736.15 million) on **page 48**; Table H8 on **page 49**; Table G2 "Cross-border fund transfers by MMO" (2,211,374 outflows) on **page 47**; Annex I local POS figures on page 50 | PDF at the entry's URL, PDF pages 63-66; printed page number read from each page's running header | Verified. The citing sentence matches. **Page numbers in `dataset/params_provenance.md` and `docs/research/sourcing_pass.md` are off by one for Annex H**: they give page 47 for H1, H2, H3 and H5 (printed page 48) and page 48 for H8 (printed page 49). The paper cites the verified pages |
| `bnrAR2425` | Title "NBR Annual Report 2024 - 2025" (running header); 222 pages; no publication date printed. Table 11 "Payment Access Points" on **page 86** (240,049 mobile agents; 625,489 modern POS devices, June 2025). Active mobile payment subscribers 7,457,114 (June 2025) on **page 87**. Figure 21 "Value of retail e-payment to GDP" on page 89 | PDF at the entry's URL | Verified. `params_provenance.md` puts the subscriber count on page 86; it is on page 87. The paper cites page 87 |
| `nisrCensus2022` | Cover: "Fifth Rwanda Population and Housing Census, 2022, Main Indicators Report, February, 2023", National Institute of Statistics of Rwanda with the Ministry of Finance and Economic Planning; 176 pages. Table 2 "Resident population by residence, Province, and District": Rwanda 27.9% urban, 72.1% rural | PDF at the entry's URL; Table 2 is on PDF page 30, **printed page 5** | Verified. The sourcing pass cites "page 30", which is the PDF index; the paper gives both |
| `afrFinscope2024` | Cover title "Rwanda FinScope Survey 2024"; "FinScope 2024 is produced by Access to Finance Rwanda (AFR)"; the report's own recommended citation: "Access to Finance Rwanda, FinScope 2024 report, June 2024"; 77 pages. Figure 26 "Mobile money uptake/use by demographics (%)" on page 40 (urban 87, rural 72) | PDF at the entry's URL | Verified. The sourcing pass lists the publisher as "NISR / Access to Finance Rwanda"; the report names AFR as producer and NISR as a sponsor. The entry follows the report's own citation |
| `wbFCRF` | Indicator name "Official exchange rate (LCU per US$, period average)", code PA.NUS.FCRF, values for 2023 and 2024 for the five countries (DRC 2024 has no value; 2023 is used, as the sourcing pass says); API "lastupdated" 2026-07-13 | World Bank API at the entry's URL (JSON) | Verified |
| `ianaTZ2026c` | Release 2026c exists (`tzdata2026c.tar.gz`, HTTP 200); the IANA page names the Time Zone Database and lists 2026d as the current release at access | https://www.iana.org/time-zones; https://data.iana.org/time-zones/releases/tzdata2026c.tar.gz | Verified. The entry names release 2026c, the one the generator's offsets were read from, and notes that 2026d is current |
| `ngaCalendar2425` | PDF title "SCHOOL CALENDAR 2024-2025", 3 pages; Term 2 begins in the week of 6-10 January, Term 3 in the week of 22-25 April. **The PDF names no school**. The host site, nga.ac.rw, titles itself "New Generation Academy" | PDF at the entry's URL; home page https://www.nga.ac.rw/ | Verified as a single school's calendar. **The sourcing pass and `params_provenance.md` attribute it to "Nu Vision Academy"; the site is New Generation Academy.** The sourcing pass also describes it as implementing Rwanda's national calendar, which nothing read confirms; the paper says only "one Rwandan school's calendar" |
| `iso4217list` | Root attribute `Pblshd="2026-09-17"`; entries: RWANDA RWF 0, KENYA KES 2, UGANDA UGX 0, TANZANIA, UNITED REPUBLIC OF TZS 2, CONGO (THE DEMOCRATIC REPUBLIC OF THE) CDF 2 | XML at the entry's URL | Verified |
| `eacOverview` | Page title "Overview of EAC"; "eight (8) Partner States" including the DRC, Kenya, Rwanda, Uganda and Tanzania; Somalia a full member on 4 March 2024 | https://www.eac.int/overview-of-eac (fetched with a browser-equivalent client; the site refuses plain scripted requests) | Verified |
| `comesaMembers` | Page title "COMESA Members States" (as published); 21 member states including the DRC, Kenya, Rwanda and Uganda | https://www.comesa.int/comesa-members-states/ | Verified |

## 2. Scholarly references (added 2026-09-22)

Method: for each DOI, the registration record was fetched from Crossref (`api.crossref.org/works/<doi>`)
and authors, title and subtitle, venue, year, volume, issue, pages and DOI were compared with the
entry. Where Crossref lacked a field, the publisher's page supplied it (Project Euclid, PubMed, PMLR,
NeurIPS proceedings, USENIX). Abstracts were read (OpenAlex or the publisher's page) for every paper
the text cites for a specific finding; standard methods (models, calibration, intervals) are cited for
the method they define. "Result" says what the citing sentence was checked against.

| Key | Checked against | Result |
|---|---|---|
| `dogan2025grift` | Full text read on 2026-09-22 (6 pp., the third author's copy at faculty.washington.edu/kotut/papers/COMPASS-2025-Financial-Grift.pdf, SHA-256 `0d5c5a2d…ecef46`); Crossref 10.1145/3715335.3736315 | **Verified from the source.** Title, authors, venue (COMPASS '25, Toronto), pp. 694-699 and DOI printed on its pages. Section 8 cites it only for what it states: forged M-PESA confirmation messages, the most common scam type its Kenyan respondents reported (§5.1.1, "M-PESA impersonation"); malicious reversal requests (§2, Background); users asking the sender to reverse a mistaken transfer (§5.3.3). It does not narrate the "sent by mistake, please return it" story, and says nothing about whether the victim knows the recipient |
| `star2025mpesa` | The article read in full on 2026-09-22 at https://www.the-star.co.ke/news/2025-03-27-explainer-what-you-need-to-know-about-m-pesa-fraud-schemes: headline, byline Felix Kipkemoi, 27 March 2025 13:58, News | **Verified from the source.** Under "Fake/Old M-PESA messages": a fake M-PESA message showing LOCKED instead of a balance; scammers claim they sent the money by mistake, with an emotional story; victims send it back and find no money was deposited; Safaricom quoted: "Do not refund the money, instead request the sender to forward the message to 456 or call Safaricom for assistance." The article does not say whether the victim knows the claimed sender; the paper states the "new counterparty" reading as its own |
| `lopezrojas2016paysim` | The paper's PDF (msc-les.org), read: title, authors, pages 249-255, ISBN 978-88-97999-76-8, editors | Verified. The paper describes each log record as the client, the action, the recipient, the sum and the change in balances, at an hourly step; no channel, device, geography or corridor field. Agents have a position in a simulated space, which the paper does not put in the record. **"Larnaca" is not printed in the paper and is omitted** |
| `phipps2018thinsim` | Crossref 10.1145/3209811.3209817; abstract | Verified |
| `razaq2021scams` | Crossref 10.1145/3449115 (title and subtitle, PACM HCI 5(CSCW1), pp. 1-30); abstract: SMS and call fraud in Pakistan, 96 interviews | Verified |
| `lee2020simswap` | USENIX SOUPS 2020 page: authors, pp. 61-79, ISBN 978-1-939133-16-8; abstract | Verified (no DOI registered) |
| `suri2016mpesa` | Crossref 10.1126/science.aah5309; abstract | Verified |
| `bolton2002fraud` | Crossref 10.1214/ss/1042727940; Project Euclid for pp. 235-255 | Verified |
| `dalpozzolo2014lessons` | Crossref 10.1016/j.eswa.2014.02.026 | Verified |
| `he2009imbalanced` | Crossref 10.1109/TKDE.2008.239 | Verified |
| `chen2016xgboost` | Crossref 10.1145/2939672.2939785 (title and subtitle, pp. 785-794) | Verified |
| `ke2017lightgbm` | NeurIPS proceedings page (NIPS 2017, vol. 30) | Verified; no DOI; no page range printed, none given |
| `liu2008iforest` | Crossref 10.1109/ICDM.2008.17 | Verified |
| `lundberg2017shap` | NeurIPS proceedings page (NIPS 2017, vol. 30) | Verified; no DOI; no page range printed |
| `lundberg2020trees` | Crossref 10.1038/s42256-019-0138-9 | Verified. Cited for exact TreeSHAP and its polynomial cost |
| `zadrozny2002calibration` | Crossref 10.1145/775047.775151 | Verified |
| `niculescumizil2005calibration` | Crossref 10.1145/1102351.1102430 | Verified. Cited for Platt scaling and isotonic regression, which it compares |
| `naeini2015bbq` | Crossref 10.1609/aaai.v29i1.9602; AAAI page | Verified; neither source prints a page range, so none is given |
| `guo2017calibration` | PMLR page: PMLR 70:1321-1330 | Verified (no DOI) |
| `kumar2019calibration` | NeurIPS proceedings page (NeurIPS 2019, vol. 32) | Verified; no DOI; no page range printed |
| `delong1988auc` | Crossref 10.2307/2531595 (first page only); PubMed 3203132 (pp. 837-845) | Verified |
| `hanley1982auc` | Crossref 10.1148/radiology.143.1.7063747 | Verified |
| `wilson1927interval` | Crossref 10.1080/01621459.1927.10502953 | Verified |
| `efron1979bootstrap` | Crossref 10.1214/aos/1176344552; Project Euclid for pp. 1-26 | Verified |
| `saito2015prc` | Crossref 10.1371/journal.pone.0118432 | Verified |
| `roberts2017cv` | Crossref 10.1111/ecog.02881; abstract | Verified |
| `bergmeir2012cv` | Crossref 10.1016/j.ins.2011.12.028 | Verified |
| `kaufman2012leakage` | Crossref 10.1145/2382577.2382579 (title and subtitle); abstract | Verified |
| `kapoor2023leakage` | Crossref 10.1016/j.patter.2023.100804; abstract | Verified |
| `geirhos2020shortcut` | Crossref 10.1038/s42256-020-00257-z | Verified (no abstract registered; cited for the concept the title names) |
| `sculley2015debt` | NeurIPS proceedings page (NIPS 2015, vol. 28) | Verified; no DOI; no page range printed |
| `breck2017testscore` | Crossref 10.1109/BigData.2017.8258038 | Verified |
| `gebru2021datasheets` | Crossref 10.1145/3458723 | Verified |
| `mitchell2019modelcards` | Crossref 10.1145/3287560.3287596 | Verified |
| `nosek2018preregistration` | Crossref 10.1073/pnas.1708274114 | Verified |

## 3. Removed or not added

| Source | Why |
|---|---|
| SADC "Member States" page (https://www.sadc.int/member-states), cited by `dataset/params_provenance.md` for Tanzania's and the DRC's bloc memberships | Could not be opened on 2026-09-22 (connection refused, then timed out). Under the rule "verify or remove", it is not cited; the paper cites only the EAC and COMESA pages for bloc membership |
| Platt (1999), "Probabilistic outputs for support vector machines and comparisons to regularized likelihood methods", in *Advances in Large Margin Classifiers* | No DOI; not in Crossref; the publisher's page (MIT Press) refused automated access on 2026-09-22. Not confirmed, so not added. Platt scaling is cited through Niculescu-Mizil and Caruana (2005), which describes and evaluates it |

## 4. Added after the third pass

`star2025mpesa` was added on 2026-09-22 once the article had been read (Section 2). Safaricom's
fraud-awareness page, the named fallback, was not needed and is not cited. `dogan2025grift` moved
from metadata-only to verified once its full text was read.
