import {
  getCountries,
  getCountryCallingCode,
  isSupportedCountry,
  parsePhoneNumberFromString,
} from 'libphonenumber-js/min';
import type { CountryCode } from 'libphonenumber-js/min';
import { validatePersonName } from '../../lib/validation/personName';
import { passwordProblem } from './password';

/** Why a field is refused. The catalogue holds the sentence for each. */
export type FieldProblem =
  'required' | 'country' | 'name' | 'email' | 'employeeId' | 'phone' | 'password' | 'mismatch';

/** RFC 5322 in the shape browsers and servers agree on: something@something.tld, no spaces. */
const EMAIL = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;
const EMPLOYEE_ID = /^[A-Za-z0-9]{4,20}$/;

export function nameProblem(value: string): FieldProblem | undefined {
  if (value.trim() === '') return 'required';
  return validatePersonName(value).valid ? undefined : 'name';
}

export function emailProblem(value: string): FieldProblem | undefined {
  if (value.trim() === '') return 'required';
  return EMAIL.test(value) && value.length <= 254 ? undefined : 'email';
}

export function employeeIdProblem(value: string): FieldProblem | undefined {
  if (value.trim() === '') return 'required';
  return EMPLOYEE_ID.test(value) ? undefined : 'employeeId';
}

export function passwordFieldProblem(value: string): FieldProblem | undefined {
  if (value === '') return 'required';
  return passwordProblem(value) === undefined ? undefined : 'password';
}

export function confirmationProblem(
  password: string,
  confirmation: string,
): FieldProblem | undefined {
  if (confirmation === '') return 'required';
  return password === confirmation ? undefined : 'mismatch';
}

/**
 * The dialling country to start with: the deployment's own, when the phone metadata knows it.
 * A pack may describe a country the metadata has never heard of (ADR 0023's Country Z), and
 * then the form asks rather than guessing.
 */
export function defaultDialCountry(country: string): CountryCode | '' {
  return isSupportedCountry(country) ? country : '';
}

/** The phone number in E.164, or undefined when it is not a valid number for that country. */
export function toE164(country: CountryCode, localNumber: string): string | undefined {
  if (localNumber.trim() === '') return undefined;
  const parsed = parsePhoneNumberFromString(localNumber, country);
  return parsed?.isValid() === true ? parsed.number : undefined;
}

export interface Country {
  code: CountryCode;
  /** The dial code, without the plus. */
  callingCode: string;
  /** The country's name in the UI language; no country is named in this source (ADR 0023). */
  name: string;
}

/** Every country the phone metadata knows, named in the given language and sorted for it. */
export function countries(locale: string): Country[] {
  const names = new Intl.DisplayNames([locale], { type: 'region' });
  return getCountries()
    .map((code) => ({
      code,
      callingCode: getCountryCallingCode(code),
      name: names.of(code) ?? code,
    }))
    .sort((a, b) => a.name.localeCompare(b.name, locale));
}
