import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DECIMAL_AMOUNT, TOKEN, type AlertSearch } from '../app/alertSearch';
import { useRegion } from '../region/RegionContext';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The criteria this form edits: the listAlerts parameters the feed's filters have no control for. */
type Field =
  'transaction_id' | 'account_id' | 'amount_min' | 'amount_max' | 'from' | 'to' | 'analyst_id';
type Draft = Record<Field, string>;

/** A datetime-local value ("2026-10-19T08:00") read in the region's zone, as an ISO instant. */
export function toInstant(local: string, utcOffsetHours: number): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local);
  if (match === null) return undefined;
  const [, y, mo, d, h, mi] = match.map(Number) as [number, number, number, number, number, number];
  return new Date(Date.UTC(y, mo - 1, d, h, mi) - utcOffsetHours * 3_600_000).toISOString();
}

/** The inverse: an ISO instant as a datetime-local value in the region's zone. */
export function toLocal(instant: string | undefined, utcOffsetHours: number): string {
  if (instant === undefined) return '';
  const shifted = new Date(Date.parse(instant) + utcOffsetHours * 3_600_000).toISOString();
  return shifted.slice(0, 16);
}

/** Whether each field's text is what the contract accepts for it; an empty field is no criterion. */
function problems(draft: Draft): Partial<Record<Field, true>> {
  const bad: Partial<Record<Field, true>> = {};
  const check = (field: Field, ok: (value: string) => boolean) => {
    const value = draft[field].trim();
    if (value !== '' && !ok(value)) bad[field] = true;
  };
  check('transaction_id', (v) => UUID.test(v));
  check('analyst_id', (v) => UUID.test(v));
  check('account_id', (v) => TOKEN.test(v));
  check('amount_min', (v) => DECIMAL_AMOUNT.test(v));
  check('amount_max', (v) => DECIMAL_AMOUNT.test(v));
  const min = draft.amount_min.trim();
  const max = draft.amount_max.trim();
  if (bad.amount_min === undefined && bad.amount_max === undefined && min !== '' && max !== '') {
    if (Number(min) > Number(max)) bad.amount_max = true;
  }
  if (draft.from !== '' && draft.to !== '' && draft.from > draft.to) bad.to = true;
  return bad;
}

/**
 * FR-04-09's search criteria: a transaction ID, an account token, an amount range, a date range
 * and an analyst, each a listAlerts parameter under the contract's name and format. Dates are
 * entered in the region's time zone, as the console shows them. The form writes the URL on Search,
 * so a search can be shared; a field the contract would reject is marked and not sent.
 */
export function SearchForm({
  search,
  onSearch,
}: {
  search: AlertSearch;
  onSearch: (next: AlertSearch) => void;
}) {
  const { t } = useTranslation('alerts');
  const { region } = useRegion();
  const offset = region.utcOffsetHours;
  const initial = (): Draft => ({
    transaction_id: search.transaction_id ?? '',
    account_id: search.account_id ?? '',
    amount_min: search.amount_min ?? '',
    amount_max: search.amount_max ?? '',
    from: toLocal(search.from, offset),
    to: toLocal(search.to, offset),
    analyst_id: search.analyst_id ?? '',
  });
  const [draft, setDraft] = useState<Draft>(initial);
  const [tried, setTried] = useState(false);
  const bad = tried ? problems(draft) : {};

  const submit = () => {
    setTried(true);
    if (Object.keys(problems(draft)).length > 0) return;
    const text = (field: Field) => {
      const value = draft[field].trim();
      return value === '' ? undefined : value;
    };
    onSearch({
      ...search,
      transaction_id: text('transaction_id')?.toLowerCase(),
      account_id: text('account_id'),
      amount_min: text('amount_min'),
      amount_max: text('amount_max'),
      from: draft.from === '' ? undefined : toInstant(draft.from, offset),
      to: draft.to === '' ? undefined : toInstant(draft.to, offset),
      analyst_id: text('analyst_id')?.toLowerCase(),
    });
  };

  const field = (name: Field, type: 'text' | 'datetime-local' = 'text') => (
    <TextField
      key={name}
      label={t(`search.field.${name}`)}
      value={draft[name]}
      type={type}
      onChange={(event) => {
        setDraft({ ...draft, [name]: event.target.value });
      }}
      error={bad[name] === true}
      helperText={bad[name] === true ? t(`search.invalid.${name}`) : undefined}
      slotProps={type === 'datetime-local' ? { inputLabel: { shrink: true } } : {}}
      sx={{ flex: '1 1 16rem' }}
    />
  );

  return (
    <Stack
      component="form"
      role="search"
      aria-label={t('search.label')}
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
      spacing={1.5}
      sx={{ mb: 2 }}
    >
      <Stack direction="row" spacing={1.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
        {field('transaction_id')}
        {field('account_id')}
        {field('analyst_id')}
      </Stack>
      <Stack direction="row" spacing={1.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
        {field('amount_min')}
        {field('amount_max')}
        {field('from', 'datetime-local')}
        {field('to', 'datetime-local')}
      </Stack>
      <Stack direction="row" spacing={1}>
        <Button type="submit" variant="contained" sx={{ minHeight: 48 }}>
          {t('search.submit')}
        </Button>
        <Button
          type="button"
          onClick={() => {
            setTried(false);
            setDraft({
              transaction_id: '',
              account_id: '',
              amount_min: '',
              amount_max: '',
              from: '',
              to: '',
              analyst_id: '',
            });
            onSearch({
              ...search,
              transaction_id: undefined,
              account_id: undefined,
              amount_min: undefined,
              amount_max: undefined,
              from: undefined,
              to: undefined,
              analyst_id: undefined,
            });
          }}
          sx={{ minHeight: 48 }}
        >
          {t('search.clear')}
        </Button>
      </Stack>
    </Stack>
  );
}
