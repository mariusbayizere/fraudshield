/**
 * FR-07-07's password policy (ADR 0014), the same rule the server applies. Both sides are tested
 * against contracts/validation/password-vectors.json, so they cannot drift apart.
 */
export const PASSWORD_MIN_CHARACTERS = 8;
export const PASSWORD_MAX_CHARACTERS = 72;
/** bcrypt reads only the first 72 bytes, so a longer password is silently truncated. */
export const PASSWORD_MAX_BYTES = 72;

/** Why a password is refused, in the order the checks run; the first failure is the reason. */
export const PASSWORD_FAILURES = [
  'LENGTH',
  'BYTES',
  'CHARACTERS',
  'UPPER',
  'LOWER',
  'DIGIT',
  'SPECIAL',
] as const;
export type PasswordFailure = (typeof PASSWORD_FAILURES)[number];

// Controls, format characters (U+200B, U+FEFF), surrogates, private use, and separators other
// than a plain space. Unassigned code points are allowed: what is unassigned depends on the
// runtime's Unicode version.
const FORBIDDEN_CATEGORY = /[\p{Cc}\p{Cf}\p{Cs}\p{Co}]/u;
const SEPARATOR = /\p{Z}/u;
const UPPER = /[A-Z]/;
const LOWER = /[a-z]/;
const DIGIT = /[0-9]/;

function characters(password: string): string[] {
  // Code points, not UTF-16 units: the policy counts characters, and the shared vectors include
  // astral ones. That is exactly what spreading a string does.
  // eslint-disable-next-line @typescript-eslint/no-misused-spread
  return [...password];
}

/** The v-flag's set subtraction is too new for some browsers, so the space is excluded here. */
function hasForbidden(password: string): boolean {
  if (FORBIDDEN_CATEGORY.test(password)) return true;
  return characters(password).some((character) => character !== ' ' && SEPARATOR.test(character));
}

/** A special character is any permitted character that is not an ASCII letter, digit or space. */
export function isSpecial(character: string): boolean {
  return character !== ' ' && !/[A-Za-z0-9]/.test(character);
}

/** The first rule a password breaks, or undefined when it is acceptable. */
export function passwordProblem(password: string): PasswordFailure | undefined {
  const length = characters(password).length;
  if (length < PASSWORD_MIN_CHARACTERS || length > PASSWORD_MAX_CHARACTERS) return 'LENGTH';
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) return 'BYTES';
  if (hasForbidden(password)) return 'CHARACTERS';
  if (!UPPER.test(password)) return 'UPPER';
  if (!LOWER.test(password)) return 'LOWER';
  if (!DIGIT.test(password)) return 'DIGIT';
  if (!characters(password).some(isSpecial)) return 'SPECIAL';
  return undefined;
}

/** Which rules a password meets, for the list the form shows while it is typed (SRS 5.3). */
export function passwordRules(password: string): Record<PasswordFailure, boolean> {
  const length = characters(password).length;
  return {
    LENGTH: length >= PASSWORD_MIN_CHARACTERS && length <= PASSWORD_MAX_CHARACTERS,
    BYTES: new TextEncoder().encode(password).length <= PASSWORD_MAX_BYTES,
    CHARACTERS: !hasForbidden(password),
    UPPER: UPPER.test(password),
    LOWER: LOWER.test(password),
    DIGIT: DIGIT.test(password),
    SPECIAL: characters(password).some(isSpecial),
  };
}

/**
 * The four-level strength meter (SRS 5.3). It counts what the policy asks for and adds one level
 * for real length, so a password that only just passes does not look strong. It is a hint about
 * this password, never a promise about how hard it is to guess.
 */
export function passwordStrength(password: string): 0 | 1 | 2 | 3 | 4 {
  if (password === '') return 0;
  const rules = passwordRules(password);
  const classes = [rules.UPPER, rules.LOWER, rules.DIGIT, rules.SPECIAL].filter(Boolean).length;
  if (passwordProblem(password) !== undefined) return classes >= 3 ? 2 : 1;
  const length = characters(password).length;
  if (length >= 16) return 4;
  if (length >= 12) return 3;
  return 2;
}
