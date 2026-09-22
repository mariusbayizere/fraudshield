# Reference audit

Every entry in `references.bib`, checked against the source itself on **2026-09-22** by the M11
agent. PDFs were downloaded from the URL in the entry and their text extracted (pypdf 5.9.0, run in
a throwaway environment outside the repository); web pages were fetched directly. "Page" means the
number printed on the page; where it differs from the PDF page index, both are given. Each citing
sentence (Section 4, "What the parameters rest on") was compared with what the source says.

No entry has a DOI: none of these sources is a journal or conference publication, and none carries
one. Every entry is a web source and carries its URL and an access date.

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

## Removed or not added

| Source | Why |
|---|---|
| SADC "Member States" page (https://www.sadc.int/member-states), cited by `dataset/params_provenance.md` for Tanzania's and the DRC's bloc memberships | Could not be opened on 2026-09-22 (connection refused, then timed out). Under the rule "verify or remove", it is not cited; the paper cites only the EAC and COMESA pages for bloc membership |
| Every scholarly reference the related-work section needs | None has been read for this paper yet. Each place carries a visible "[reference needed]" marker (`\refneeded`) instead of a citation, so no unverified entry exists in `references.bib` |

## Not checked here

The owner's brief names an entry `star2025mpesa` from the revised draft (v2). That draft was not
available on this machine when this audit was made, so the entry has not been read or added; see
`docs/parallel/M11_updates.md`.
