import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import { defaultParseSearch, defaultStringifySearch } from '@tanstack/react-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { alertSearch, type AlertSearch } from '../app/alertSearch';
import { useRegion } from '../region/RegionContext';

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

const FIELDS: readonly Field[] = [
  'transaction_id',
  'account_id',
  'amount_min',
  'amount_max',
  'from',
  'to',
  'analyst_id',
];

/**
 * The search the form would send, and the fields it cannot send as typed.
 *
 * A field is judged by the route's own validator after the URL's round trip, the path the search
 * actually takes, not by a second set of patterns: the form once accepted IDs the route then
 * dropped, so a search silently widened (PB-136). A field the route would drop or change is
 * marked instead. An empty field is no criterion. The ranges are the form's own checks on top.
 */
export function judge(
  draft: Draft,
  search: AlertSearch,
  utcOffsetHours: number,
): { next: AlertSearch; bad: Partial<Record<Field, true>> } {
  const bad: Partial<Record<Field, true>> = {};
  const text = (field: Field) => {
    const value = draft[field].trim();
    return value === '' ? undefined : value;
  };
  const instant = (field: 'from' | 'to') => {
    if (draft[field] === '') return undefined;
    // The field shows minutes only; a bound a link carried to the second is sent as it came
    // unless it was edited, rather than cut to the minute.
    const carried = search[field];
    if (carried !== undefined && draft[field] === toLocal(carried, utcOffsetHours)) return carried;
    const value = toInstant(draft[field], utcOffsetHours);
    if (value === undefined) bad[field] = true;
    return value;
  };
  const next: AlertSearch = {
    ...search,
    transaction_id: text('transaction_id')?.toLowerCase(),
    account_id: text('account_id'),
    amount_min: text('amount_min'),
    amount_max: text('amount_max'),
    from: instant('from'),
    to: instant('to'),
    analyst_id: text('analyst_id')?.toLowerCase(),
  };
  const kept = alertSearch.parse(defaultParseSearch(defaultStringifySearch(next)));
  for (const field of FIELDS) {
    if (next[field] !== undefined && kept[field] !== next[field]) bad[field] = true;
  }
  const { amount_min: min, amount_max: max, from, to } = next;
  if (bad.amount_min === undefined && bad.amount_max === undefined && min !== undefined) {
    if (max !== undefined && Number(min) > Number(max)) bad.amount_max = true;
  }
  if (bad.from === undefined && bad.to === undefined && from !== undefined && to !== undefined) {
    if (Date.parse(from) > Date.parse(to)) bad.to = true;
  }
  return { next, bad };
}

/**
 * FR-04-09's search criteria: a transaction ID, an account token, an amount range, a date range
 * and an analyst, each a listAlerts parameter under the contract's name and format. Dates are
 * entered in the region's time zone, as the console shows them. The form writes the URL on Search,
 * so a search can be shared. A field the route would drop is marked, and nothing is sent until it
 * is put right: a search never runs wider than the analyst asked.
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
  const judged = judge(draft, search, offset);
  const bad = tried ? judged.bad : {};

  const submit = () => {
    setTried(true);
    if (Object.keys(judged.bad).length > 0) return;
    onSearch(judged.next);
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
