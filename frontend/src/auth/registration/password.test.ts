import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  PASSWORD_FAILURES,
  passwordProblem,
  passwordRules,
  passwordStrength,
  type PasswordFailure,
} from './password';

interface Vectors {
  min_characters: number;
  max_characters: number;
  max_utf8_bytes: number;
  accept: { input: string; note: string }[];
  reject: { input: string; reason: PasswordFailure; note: string }[];
}

const vectors = JSON.parse(
  readFileSync(
    join(import.meta.dirname, '../../../../contracts/validation/password-vectors.json'),
    'utf8',
  ),
) as Vectors;

describe('the password policy (FR-07-07, ADR 0014)', () => {
  it('uses the shared vectors, which are stored escaped so nothing hides in them', () => {
    expect(vectors.accept.length + vectors.reject.length).toBeGreaterThan(20);
    expect([vectors.min_characters, vectors.max_characters, vectors.max_utf8_bytes]).toEqual([
      8, 72, 72,
    ]);
  });

  it.each(vectors.accept.map((v) => [v.note, v.input] as const))('accepts %s', (_note, input) => {
    expect(passwordProblem(input)).toBeUndefined();
    expect(Object.values(passwordRules(input)).every(Boolean)).toBe(true);
  });

  it.each(vectors.reject.map((v) => [v.note, v.input, v.reason] as const))(
    'rejects %s as %s',
    (_note, input, reason) => {
      expect(passwordProblem(input)).toBe(reason);
      expect(passwordRules(input)[reason]).toBe(false);
    },
  );

  it('reports failures in the order the rule defines', () => {
    expect([...PASSWORD_FAILURES]).toEqual([
      'LENGTH',
      'BYTES',
      'CHARACTERS',
      'UPPER',
      'LOWER',
      'DIGIT',
      'SPECIAL',
    ]);
  });
});

describe('characters are code points, not UTF-16 units', () => {
  // The shared vectors reach outside the BMP only through byte counts, so these cases pin the
  // difference: an emoji is one character, two UTF-16 units and four bytes.
  it.each([
    // Six characters: too short, though it is eight UTF-16 units.
    ['Aa1!\u{1F44D}\u{1F44D}', 'LENGTH'],
    // Forty-four characters, inside the length limit, but far past 72 bytes.
    [`Aa1!${'\u{1F44D}'.repeat(40)}`, 'BYTES'],
  ])('reads %j as %s', (password, reason) => {
    expect(passwordProblem(password)).toBe(reason);
  });

  it('accepts an eight-character password made mostly of emoji', () => {
    expect(passwordProblem('Aa1!\u{1F44D}\u{1F44D}\u{1F44D}\u{1F44D}')).toBeUndefined();
  });
});

describe('the strength meter (SRS 5.3)', () => {
  it.each([
    ['', 0],
    ['aaaaaaaa', 1],
    ['Aa1aaaaa', 2],
    ['Str0ng!pass', 2],
    ['Str0ng!passwo', 3],
    ['Str0ng!passwordphrase', 4],
  ])('rates %j as %d of 4', (password, level) => {
    expect(passwordStrength(password)).toBe(level);
  });

  it('never rates a password the policy refuses above 2', () => {
    for (const { input } of vectors.reject) {
      expect(passwordStrength(input)).toBeLessThanOrEqual(2);
    }
  });
});
