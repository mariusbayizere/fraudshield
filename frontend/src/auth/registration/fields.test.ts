import { getCountries } from 'libphonenumber-js/min';
import {
  confirmationProblem,
  countries,
  defaultDialCountry,
  emailProblem,
  employeeIdProblem,
  nameProblem,
  toE164,
} from './fields';

const SUPPORTED = getCountries()[0] ?? 'GB';

describe('the registration fields (SRS 5.3)', () => {
  it.each([
    ['', 'required'],
    ['ab', 'employeeId'],
    ['abc', 'employeeId'],
    ['EMP-0042', 'employeeId'],
    ['emp 0042', 'employeeId'],
    ['a'.repeat(21), 'employeeId'],
    ['abcd', undefined],
    ['EMP0042', undefined],
    ['a'.repeat(20), undefined],
  ])('reads the employee ID %j as %s', (value, problem) => {
    expect(employeeIdProblem(value)).toBe(problem);
  });

  it.each([
    ['', 'required'],
    ['not-an-email', 'email'],
    ['no@tld', 'email'],
    ['two words@example.test', 'email'],
    ['a@b.test', undefined],
    ['first.last+tag@sub.example.test', undefined],
  ])('reads the email %j as %s', (value, problem) => {
    expect(emailProblem(value)).toBe(problem);
  });

  it.each([
    ['', 'required'],
    ['A', 'name'],
    ['  ', 'required'],
    ['Aline Uwase', undefined],
    ["N'Dour", undefined],
  ])('reads the name %j as %s', (value, problem) => {
    expect(nameProblem(value)).toBe(problem);
  });

  it.each([
    ['secret', '', 'required'],
    ['secret', 'different', 'mismatch'],
    ['secret', 'secret', undefined],
  ])('compares %j with %j as %s', (password, confirmation, problem) => {
    expect(confirmationProblem(password, confirmation)).toBe(problem);
  });

  it('keeps a country the phone metadata does not know out of the dial list (ADR 0023)', () => {
    expect(defaultDialCountry('ZZ')).toBe('');
    expect(defaultDialCountry(SUPPORTED)).toBe(SUPPORTED);
  });

  it('refuses a number that is not valid for its country', () => {
    expect(toE164(SUPPORTED, '1')).toBeUndefined();
    expect(toE164(SUPPORTED, '')).toBeUndefined();
  });

  it('names every country in the UI language, sorted for it, with its dial code', () => {
    const list = countries('en');
    expect(list.length).toBe(getCountries().length);
    expect(list.map((country) => country.name)).toEqual(
      [...list].sort((a, b) => a.name.localeCompare(b.name, 'en')).map((country) => country.name),
    );
    for (const country of list) expect(country.callingCode).toMatch(/^[1-9][0-9]{0,3}$/);
    // The same list, named in another language, is a different order of the same countries.
    expect(new Set(countries('fr').map((c) => c.code))).toEqual(
      new Set(list.map((country) => country.code)),
    );
  });
});
