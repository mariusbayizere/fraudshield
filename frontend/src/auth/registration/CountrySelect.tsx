import TextField from '@mui/material/TextField';
import type { CountryCode } from 'libphonenumber-js/min';
import { memo, useMemo } from 'react';
import { countries } from './fields';

interface Props {
  label: string;
  value: CountryCode | '';
  language: string;
  /** Shown while no country is chosen, when the deployment's own is not a dialling country. */
  placeholder: string;
  onChange: (country: CountryCode | '') => void;
}

/**
 * The dialling country (SRS 5.3): a native select, so phones show their own picker and the list
 * costs nothing to open. Memoised because it renders one option per country: without this it
 * would rebuild 245 options on every keystroke elsewhere in the form.
 */
export const CountrySelect = memo(function CountrySelect({
  label,
  value,
  language,
  placeholder,
  onChange,
}: Props) {
  const list = useMemo(() => countries(language), [language]);
  return (
    <TextField
      select
      label={label}
      value={value}
      onChange={(event) => {
        onChange(event.target.value as CountryCode | '');
      }}
      fullWidth
      slotProps={{ select: { native: true } }}
    >
      <option value="">{placeholder}</option>
      {list.map((country) => (
        <option key={country.code} value={country.code}>
          {`${country.name} +${country.callingCode}`}
        </option>
      ))}
    </TextField>
  );
});
