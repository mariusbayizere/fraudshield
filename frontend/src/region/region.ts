/**
 * Region: what the console needs to know about the country it serves (ADR 0023, ADR 0080).
 *
 * Every value comes from a country pack in dataset/generator/params/countries/, through the
 * generated packs.json. No country, currency or zone is named in this source; a new pack is a
 * data change.
 */
export interface Region {
  /** ISO 3166-1 alpha-2, the pack's file name. */
  country: string;
  /** ISO 4217 alphabetic code of the local currency. */
  currency: string;
  /** Decimal places the currency is quoted to (ISO 4217 minor unit). */
  currencyMinorUnits: number;
  /** Hours ahead of UTC; the packs' zones observe no daylight saving. */
  utcOffsetHours: number;
}

/** A decimal amount as the contract sends it: a string, never a JSON number. */
export type DecimalString = string;

const DECIMAL = /^-?\d+(\.\d+)?$/;
const formatters = new Map<string, Intl.NumberFormat>();

/**
 * Money as "<code> 1,250,000": the currency code, then the amount at the currency's minor units.
 * The decimal string is formatted as a decimal, so no float ever touches the amount.
 * `minorUnits` is undefined only for a currency no pack describes, where ISO 4217 as known to
 * the browser decides.
 */
export function formatMoney(
  amount: DecimalString,
  currency: string,
  minorUnits: number | undefined,
  locale: string,
): string {
  if (!DECIMAL.test(amount)) {
    throw new RangeError(`an amount is a decimal string, got ${JSON.stringify(amount)}`);
  }
  const key = `${locale}|${currency}|${String(minorUnits)}`;
  let formatter = formatters.get(key);
  if (formatter === undefined) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      currencyDisplay: 'code',
      ...(minorUnits === undefined
        ? {}
        : { minimumFractionDigits: minorUnits, maximumFractionDigits: minorUnits }),
    });
    formatters.set(key, formatter);
  }
  // Intl formats a numeric string exactly (ECMA-402 Intl.NumberFormat v3); the cast only
  // satisfies a lib.d.ts that still declares number | bigint.
  return formatter.format(amount as unknown as number);
}

/** "UTC+2", "UTC-3:30", "UTC+5:45": the zone written as its offset. */
export function offsetLabel(utcOffsetHours: number): string {
  const sign = utcOffsetHours < 0 ? '-' : '+';
  const minutes = Math.round(Math.abs(utcOffsetHours) * 60);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `UTC${sign}${String(h)}${m === 0 ? '' : `:${String(m).padStart(2, '0')}`}`;
}

function offsetZone(utcOffsetHours: number): string {
  const sign = utcOffsetHours < 0 ? '-' : '+';
  const minutes = Math.round(Math.abs(utcOffsetHours) * 60);
  const hh = String(Math.floor(minutes / 60)).padStart(2, '0');
  const mm = String(minutes % 60).padStart(2, '0');
  return `${sign}${hh}:${mm}`;
}

/** A wall-clock time in the region, with its zone: "14:02 UTC+2" (D-43: the zone is always shown). */
export function formatClock(at: Date, utcOffsetHours: number, locale: string): string {
  const time = new Intl.DateTimeFormat(locale, {
    timeZone: offsetZone(utcOffsetHours),
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(at);
  return `${time} ${offsetLabel(utcOffsetHours)}`;
}
