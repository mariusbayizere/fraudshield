import { readFileSync } from 'node:fs';
import { renderPacks, OUTPUT } from '../../scripts/generate-region-packs';
import { COUNTRY_Z } from '../test/fixtures';
import { minorUnitsByCurrency, PACKS, regionFor } from './packs';
import { formatClock, formatMoney, offsetLabel } from './region';

describe('region data from the country packs (ADR 0023)', () => {
  it('is what the packs produce, so it cannot drift by hand', () => {
    expect(readFileSync(OUTPUT, 'utf8')).toBe(renderPacks());
  });

  it('describes at least one country, each with a currency, minor units and an offset', () => {
    expect(Object.keys(PACKS).length).toBeGreaterThan(0);
    for (const [code, pack] of Object.entries(PACKS)) {
      expect(pack.country).toBe(code);
      expect(pack.currency).toMatch(/^[A-Z]{3}$/);
      expect(Number.isInteger(pack.currencyMinorUnits)).toBe(true);
    }
  });

  it("formats every pack's currency at that pack's minor units", () => {
    for (const pack of Object.values(PACKS)) {
      const text = formatMoney('1000.123456', pack.currency, pack.currencyMinorUnits, 'en');
      const decimals = /\.(\d+)$/.exec(text)?.[1]?.length ?? 0;
      expect(decimals).toBe(pack.currencyMinorUnits);
    }
  });

  it('refuses packs that disagree about a currency', () => {
    const clash = { A: COUNTRY_Z, B: { ...COUNTRY_Z, country: 'B', currencyMinorUnits: 2 } };
    expect(() => minorUnitsByCurrency(clash)).toThrow(/disagree/);
  });

  it('stops on a country no pack describes, and has no default', () => {
    expect(() => regionFor(undefined)).toThrow(/VITE_FS_COUNTRY/);
    expect(() => regionFor('ZZ')).toThrow(/VITE_FS_COUNTRY/);
    expect(regionFor('ZZ', { ZZ: COUNTRY_Z })).toBe(COUNTRY_Z);
  });
});

describe('formatting without a float or a named country', () => {
  it.each([
    ['1250000.5', 3, 'ZZZ 1,250,000.500'],
    ['0', 3, 'ZZZ 0.000'],
    // A float would read this as 12345678901234568: only exact decimal formatting keeps it.
    ['12345678901234567.125', 3, 'ZZZ 12,345,678,901,234,567.125'],
    ['0.0005', 3, 'ZZZ 0.001'],
    ['-0.5', 0, '-ZZZ 1'],
  ])('writes %s at %d minor units as %s', (amount, units, text) => {
    expect(formatMoney(amount, 'ZZZ', units, 'en')).toBe(text);
  });

  it.each(['1e6', '1,000', '', ' 5', 'NaN', '0x10'])('refuses %j', (amount) => {
    expect(() => formatMoney(amount, 'ZZZ', 3, 'en')).toThrow(RangeError);
  });

  it.each([
    [2, 'UTC+2'],
    [0, 'UTC+0'],
    [-3.5, 'UTC-3:30'],
    [5.75, 'UTC+5:45'],
    [7, 'UTC+7'],
  ])('labels an offset of %d hours %s', (hours, label) => {
    expect(offsetLabel(hours)).toBe(label);
  });

  it('writes a time in the region with its zone', () => {
    const at = new Date('2026-09-22T12:02:00Z');
    expect(formatClock(at, 7, 'en')).toBe('19:02 UTC+7');
    expect(formatClock(at, -3.5, 'en')).toBe('08:32 UTC-3:30');
  });
});
