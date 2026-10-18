/**
 * Person-name rule shared with the Java `PersonName` value object (ADR 0013).
 *
 * After NFC normalisation: no leading or trailing whitespace; 2-100 Unicode code points; parts of
 * Unicode letters and combining marks, each starting with a letter, separated by exactly one space,
 * hyphen, ASCII apostrophe or U+2019. Implemented as a single pass over code points (no
 * backtracking regular expression). Both implementations are tested against
 * `contracts/validation/person-name-vectors.json`.
 */

export const PERSON_NAME_MIN_CODE_POINTS = 2;
export const PERSON_NAME_MAX_CODE_POINTS = 100;

export type PersonNameRejection = 'WHITESPACE' | 'LENGTH' | 'CHARACTERS';

export type PersonNameResult =
  | { readonly valid: true; readonly value: string }
  | { readonly valid: false; readonly reason: PersonNameRejection };

const LETTER = /^\p{L}$/u;
const COMBINING_MARK = /^\p{M}$/u;
const SEPARATORS = new Set([' ', '-', "'", '’']);

function partsAreValid(codePoints: readonly string[]): boolean {
  let expectingLetter = true;
  for (const codePoint of codePoints) {
    if (expectingLetter) {
      if (!LETTER.test(codePoint)) {
        return false;
      }
      expectingLetter = false;
    } else if (SEPARATORS.has(codePoint)) {
      expectingLetter = true;
    } else if (!LETTER.test(codePoint) && !COMBINING_MARK.test(codePoint)) {
      return false;
    }
  }
  return !expectingLetter;
}

/** Normalises `raw` to NFC and validates it. */
export function validatePersonName(raw: string): PersonNameResult {
  const normalised = raw.normalize('NFC');
  if (normalised !== normalised.trim()) {
    return { valid: false, reason: 'WHITESPACE' };
  }
  const codePoints = Array.from(normalised);
  if (
    codePoints.length < PERSON_NAME_MIN_CODE_POINTS ||
    codePoints.length > PERSON_NAME_MAX_CODE_POINTS
  ) {
    return { valid: false, reason: 'LENGTH' };
  }
  if (!partsAreValid(codePoints)) {
    return { valid: false, reason: 'CHARACTERS' };
  }
  return { valid: true, value: normalised };
}
