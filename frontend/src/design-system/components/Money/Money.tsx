import Typography from '@mui/material/Typography';
import { formatMoney, type DecimalString } from '../../../region/region';
import { useFormatLocale, useRegion } from '../../../region/RegionContext';

export interface MoneyProps {
  /** The contract's decimal string (ADR 0011), never a JSON number. */
  amount: DecimalString;
  /** ISO 4217 code, as it arrives with the amount. */
  currency: string;
}

/**
 * An amount with its currency code, at the currency's minor units from the country packs, in
 * tabular numerals so a column of amounts lines up (E.9, D-43, ADR 0023).
 */
export function Money({ amount, currency }: MoneyProps) {
  const { minorUnits } = useRegion();
  const locale = useFormatLocale();
  return (
    <Typography variant="numeric" component="span">
      {formatMoney(amount, currency, minorUnits.get(currency), locale)}
    </Typography>
  );
}
