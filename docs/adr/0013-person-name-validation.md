# 0013 — Person-name validation for East African names

- **Status:** Accepted (repository owner finding, 2026-09-17)
- **Date:** 2026-09-17
- **Requirements affected:** FR-06-01, FR-07-02, UX-REG-01, UX-REG-02
- **Defects referenced:** D-43

## Context

SRS 5.3 specifies first and last names as "Min 2, max 50 chars; letters + hyphens + apostrophes only".
The first contract used an explicit Latin/Latin-1/Latin Extended-A class so every regex engine would
agree. The owner found this rejects real names in the region the product serves: it has no spaces for
multiple given names ("Jean Bosco"), a 50-character limit, and only a partial view of Unicode letters and
combining marks, which matters for Kikuyu ("Ngũgĩ"), French ("Hélène") and names with apostrophes
("N'Dri").

## Options considered

1. **Widen the regex character class** — still fails for scripts or combining sequences outside the
   listed blocks, and different engines disagree on class semantics.
2. **`\p{L}\p{M}` in a JSON Schema `pattern`** — Python's `re` does not support Unicode property escapes,
   so the contract would validate differently across languages.
3. **A shared rule implemented in each runtime language, tested against one vector file.**

## Decision

Option 3.

- **Rule.** Normalise to Unicode NFC. Reject leading or trailing whitespace. Require 2–100 Unicode code
  points (not UTF-16 units). The name is one or more parts; each part starts with a letter (`\p{L}`)
  and continues with letters or combining marks (`\p{M}`); parts are separated by exactly one space,
  hyphen, ASCII apostrophe or U+2019. Digits, symbols, emoji, control and format characters (such as
  zero-width space) are rejected, as are consecutive, leading or trailing separators.
- **Where.** The OpenAPI `PersonName` schema keeps only `minLength: 2`, `maxLength: 100` and
  `x-validation: person-name`. Java enforces the rule in `common.identity.PersonName` (the value stored
  is the NFC form); TypeScript in `frontend/src/lib/validation/personName.ts` for immediate form
  feedback. The server remains authoritative.
- **How.** Both implementations use a single pass over code points rather than a regular expression,
  which removes any backtracking risk (FindSecBugs flagged the regex form as a ReDoS candidate).
- **Evidence.** `contracts/validation/person-name-vectors.json` (ASCII with JSON escapes, so no hidden
  characters are committed) holds 14 accepted and 17 rejected cases, including decomposed input that
  must normalise to the precomposed form. `PersonNameTest` (Java) and `personName.test.ts` (TypeScript)
  both run every vector.

## Consequences

- Deviation from SRS 5.3: maximum 100 instead of 50, and spaces and all Unicode letters are allowed. The
  UX-REG-01 and UX-REG-02 rows record this ADR when they close.
- FindSecBugs `IMPROPER_UNICODE` is excluded for `PersonName` only (`backend/spotbugs-exclude.xml`),
  because normalisation happens before validation.
- Names are displayed as stored (NFC); search and duplicate detection, if added, must compare NFC forms.
