import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  PERSON_NAME_MAX_CODE_POINTS,
  PERSON_NAME_MIN_CODE_POINTS,
  validatePersonName,
  type PersonNameRejection,
} from './personName';

interface Vectors {
  readonly min_code_points: number;
  readonly max_code_points: number;
  readonly accept: readonly { input: string; normalised: string; note: string }[];
  readonly reject: readonly { input: string; reason: PersonNameRejection; note: string }[];
}

const vectors = JSON.parse(
  readFileSync(
    resolve(import.meta.dirname, '../../../../contracts/validation/person-name-vectors.json'),
    'utf8',
  ),
) as Vectors;

describe('validatePersonName', () => {
  it('[FR-07-02, UX-REG-01] uses the same length bounds as the shared vectors', () => {
    expect(PERSON_NAME_MIN_CODE_POINTS).toBe(vectors.min_code_points);
    expect(PERSON_NAME_MAX_CODE_POINTS).toBe(vectors.max_code_points);
  });

  it.each(vectors.accept)('[FR-07-02, UX-REG-01, UX-REG-02] accepts $note', (vector) => {
    const result = validatePersonName(vector.input);
    expect(result).toEqual({ valid: true, value: vector.normalised });
  });

  it.each(vectors.reject)('[FR-07-02, UX-REG-01, UX-REG-02] rejects $note', (vector) => {
    expect(validatePersonName(vector.input)).toEqual({ valid: false, reason: vector.reason });
  });

  it('counts code points, not UTF-16 units', () => {
    const supplementaryLetter = String.fromCodePoint(0x1d400);
    expect(validatePersonName(supplementaryLetter)).toEqual({ valid: false, reason: 'LENGTH' });
    expect(validatePersonName(supplementaryLetter.repeat(2)).valid).toBe(true);
  });
});
