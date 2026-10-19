import FormControl from '@mui/material/FormControl';
import InputLabel from '@mui/material/InputLabel';
import OutlinedInput from '@mui/material/OutlinedInput';
import { useId, type ChangeEvent, type ReactNode } from 'react';

export interface TextInputProps {
  label: string;
  value: string;
  onChange: (event: ChangeEvent<HTMLInputElement>) => void;
  type?: 'text' | 'email' | 'password';
  autoComplete?: string;
  required?: boolean;
  endAdornment?: ReactNode;
}

/**
 * An outlined text field for screens on the first visit's path. MUI's TextField also carries its
 * select mode, and with it the menu, popover and modal: replacing it made the sign-in route 18 KB
 * lighter, gzipped (ADR 0080 §10, MOB-PERF-01). This composes only the input's own parts, with the
 * label tied to the input by id, as TextField ties it. It has no helper text yet; add it when a
 * first-visit screen needs one.
 */
export function TextInput({
  label,
  value,
  onChange,
  type = 'text',
  autoComplete,
  required = false,
  endAdornment,
}: TextInputProps) {
  const id = useId();
  return (
    <FormControl variant="outlined" fullWidth required={required}>
      <InputLabel htmlFor={id}>{label}</InputLabel>
      <OutlinedInput
        id={id}
        label={label}
        type={type}
        value={value}
        onChange={onChange}
        {...(autoComplete === undefined ? {} : { autoComplete })}
        {...(endAdornment === undefined ? {} : { endAdornment })}
      />
    </FormControl>
  );
}
