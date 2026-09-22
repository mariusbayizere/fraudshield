import type { Region } from '../region/region';

/**
 * Country Z: the dataset's synthetic, entirely assumed pack (dataset/tests/test_generator.py),
 * with the same values. An invented currency with three minor units, unlike every real currency
 * in the packs, and a UTC+7 offset, so an assumption about any real country shows up as a
 * failing test (ADR 0023's behavioural acceptance test, applied to the UI).
 */
export const COUNTRY_Z: Region = {
  country: 'ZZ',
  currency: 'ZZZ',
  currencyMinorUnits: 3,
  utcOffsetHours: 7,
};
