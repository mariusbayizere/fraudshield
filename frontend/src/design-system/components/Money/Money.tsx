import Typography from '@mui/material/Typography';

const formatters = new Map<string, Intl.NumberFormat>();

/**
 * An amount with its currency code first, "RWF 1,250,000" (SRS 5.4), using the currency's own
 * minor units: none for RWF, two for USD. The locale arrives with i18n (D-43).
 */
export function formatMoney(amount: number, currency = 'RWF', locale = 'en-RW'): string {
  const key = `${locale}|${currency}`;
  let formatter = formatters.get(key);
  if (formatter === undefined) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      currencyDisplay: 'code',
    });
    formatters.set(key, formatter);
  }
  return formatter.format(amount);
}

export interface MoneyProps {
  amount: number;
  currency?: string;
}

/** An amount in tabular numerals, so a column of amounts lines up (E.9). */
export function Money({ amount, currency = 'RWF' }: MoneyProps) {
  return (
    <Typography variant="numeric" component="span">
      {formatMoney(amount, currency)}
    </Typography>
  );
}
