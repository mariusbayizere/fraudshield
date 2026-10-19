import FormControl from '@mui/material/FormControl';
import FormHelperText from '@mui/material/FormHelperText';
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
  helperText?: string;
  endAdornment?: ReactNode;
}

/**
 * An outlined text field for screens on the first visit's path. MUI's TextField also carries its
 * select mode, and with it the menu, popover and modal: 13 KB gzipped a sign-in form never shows
 * (ADR 0080 §10, MOB-PERF-01). This composes only the input's own parts, with the label and the
 * helper text tied to the input by id, as TextField ties them.
 */
export function TextInput({
  label,
  value,
  onChange,
  type = 'text',
  autoComplete,
  required = false,
  helperText,
  endAdornment,
}: TextInputProps) {
  const id = useId();
  const helperId = `${id}-helper`;
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
        {...(helperText === undefined ? {} : { 'aria-describedby': helperId })}
      />
      {helperText === undefined ? null : (
        <FormHelperText id={helperId}>{helperText}</FormHelperText>
      )}
    </FormControl>
  );
}
