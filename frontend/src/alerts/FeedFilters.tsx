import DoneRounded from '@mui/icons-material/DoneRounded';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { AlertStatus } from '../api/generated/types.gen';
import { ALERT_SORTS, ALERT_STATUSES, type AlertSearch } from '../app/alertSearch';
import { CHANNELS } from '../design-system/tokens';
import { useFormatLocale } from '../region/formatLocale';
import { formatDateTime } from '../region/region';
import { useRegion } from '../region/RegionContext';

/** Adds the value to a list filter, or removes it; an emptied list is no filter at all. */
export function toggled<T extends string>(
  list: readonly T[] | undefined,
  value: T,
): T[] | undefined {
  const current = list ?? [];
  const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
  return next.length === 0 ? undefined : next;
}

const SEVERAL = 'several';

/** The status select shows one status; a link that names several says so instead. */
function statusValue(outcome: readonly AlertStatus[] | undefined): string {
  if (outcome === undefined) return '';
  return outcome.length === 1 ? (outcome[0] ?? '') : SEVERAL;
}

/** True when any filter narrows the feed (sort orders it, so it does not count). */
export function isFiltered(search: AlertSearch): boolean {
  return Object.entries(search).some(([key, value]) => key !== 'sort' && value !== undefined);
}

/** The filters a link can carry that this screen has no control for (they belong to /search). */
export const LINK_ONLY = [
  'transaction_id',
  'account_id',
  'amount_min',
  'amount_max',
  'from',
  'to',
  'analyst_id',
] as const;

/**
 * A filter that is on or off. Pressed is shown by shape and a check mark as well as colour
 * (WCAG 1.4.1), and the chip is a 48 px target (05B A.7).
 */
function ToggleChip({
  label,
  pressed,
  onToggle,
}: {
  label: string;
  pressed: boolean;
  onToggle: () => void;
}) {
  return (
    <Chip
      label={label}
      aria-pressed={pressed}
      variant={pressed ? 'filled' : 'outlined'}
      color={pressed ? 'primary' : 'default'}
      {...(pressed ? { icon: <DoneRounded aria-hidden /> } : {})}
      onClick={onToggle}
      sx={{ minHeight: 48, borderRadius: 6 }}
    />
  );
}

/** A filter from a link: the whole chip removes it, and its name says so. */
function RemovableChip({ label, onRemove }: { label: string; onRemove: () => void }) {
  const { t } = useTranslation('alerts');
  return (
    <Chip
      label={label}
      aria-label={t('filters.remove', { filter: label })}
      onClick={onRemove}
      onDelete={onRemove}
      sx={{ minHeight: 48, borderRadius: 6 }}
    />
  );
}

/**
 * The feed's filters (FR-04-09, UX-ROLE-AN-01: tier, channel, status), each one a listAlerts
 * parameter carried in the URL. Filters a shared link carries that have no control here (a date,
 * an amount, an identifier, or a tier this queue does not offer) are shown, each removable, so
 * nothing narrows the feed unseen.
 */
export function FeedFilters({
  search,
  tiers,
  onChange,
}: {
  search: AlertSearch;
  /** The tiers this queue can hold, in the order they are offered. */
  tiers: readonly ('HIGH' | 'MEDIUM')[];
  onChange: (next: AlertSearch) => void;
}) {
  const { t } = useTranslation('alerts');
  const { t: tDesign } = useTranslation('designSystem');
  const region = useRef<HTMLElement>(null);
  // A filter set to undefined is dropped by the route's validation, so it leaves the URL.
  const set = (patch: Partial<AlertSearch>) => {
    onChange({ ...search, ...patch });
  };
  // A link's chip removes itself; focus stays in the filters, not on the page body. ("Clear
  // filters" does the same below.)
  const removeAndKeepFocus = (patch: Partial<AlertSearch>) => {
    set(patch);
    region.current?.focus();
  };
  const offered: readonly string[] = tiers;
  const otherTiers = (search.tier ?? []).filter((tier) => !offered.includes(tier));
  const linkOnly = LINK_ONLY.filter((key) => search[key] !== undefined);
  const { region: where } = useRegion();
  const locale = useFormatLocale();
  const shown = (key: (typeof LINK_ONLY)[number], value: string): string => {
    if (key === 'from' || key === 'to') {
      return formatDateTime(new Date(value), where.utcOffsetHours, locale);
    }
    if (key === 'amount_min' || key === 'amount_max') {
      // A decimal string; Intl formats it exactly (see formatMoney).
      return new Intl.NumberFormat(locale, { maximumFractionDigits: 4 }).format(
        value as unknown as number,
      );
    }
    return value;
  };

  return (
    <Stack
      ref={region}
      component="section"
      tabIndex={-1}
      aria-label={t('filters.label')}
      spacing={1.5}
      sx={{ mb: 2 }}
    >
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Typography component="span" variant="body2" color="text.secondary">
          {t('filters.tier')}
        </Typography>
        {tiers.map((tier) => (
          <ToggleChip
            key={tier}
            label={t(`tier.${tier}`)}
            pressed={search.tier?.includes(tier) ?? false}
            onToggle={() => {
              set({ tier: toggled(search.tier, tier) });
            }}
          />
        ))}
      </Stack>
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Typography component="span" variant="body2" color="text.secondary">
          {t('filters.channel')}
        </Typography>
        {CHANNELS.map((channel) => (
          <ToggleChip
            key={channel}
            label={tDesign(`channel.${channel}`)}
            pressed={search.channel?.includes(channel) ?? false}
            onToggle={() => {
              set({ channel: toggled(search.channel, channel) });
            }}
          />
        ))}
      </Stack>
      {otherTiers.length + linkOnly.length === 0 ? null : (
        <Stack
          component="ul"
          direction="row"
          spacing={1}
          useFlexGap
          aria-label={t('filters.fromLink')}
          sx={{ flexWrap: 'wrap', p: 0, m: 0, listStyle: 'none' }}
        >
          {otherTiers.map((tier) => (
            <li key={`tier-${tier}`}>
              <RemovableChip
                label={`${t('filters.tier')}: ${t(`tier.${tier}`)}`}
                onRemove={() => {
                  removeAndKeepFocus({ tier: toggled(search.tier, tier) });
                }}
              />
            </li>
          ))}
          {linkOnly.map((key) => (
            <li key={key}>
              <RemovableChip
                label={`${t(`filters.param.${key}`)}: ${shown(key, String(search[key]))}`}
                onRemove={() => {
                  removeAndKeepFocus({ [key]: undefined });
                }}
              />
            </li>
          ))}
        </Stack>
      )}
      <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          select
          label={t('filters.status')}
          value={statusValue(search.outcome)}
          onChange={(event) => {
            const value = event.target.value as AlertStatus | '';
            set({ outcome: value === '' ? undefined : [value] });
          }}
          slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
          sx={{ minWidth: 200 }}
        >
          <option value="">{t('filters.anyStatus')}</option>
          {(search.outcome?.length ?? 0) > 1 ? (
            <option value={SEVERAL} disabled>
              {t('filters.severalStatuses', { count: search.outcome?.length ?? 0 })}
            </option>
          ) : null}
          {ALERT_STATUSES.map((status) => (
            <option key={status} value={status}>
              {t(`status.${status}`)}
            </option>
          ))}
        </TextField>
        <TextField
          select
          label={t('filters.sort')}
          value={search.sort ?? 'priority'}
          onChange={(event) => {
            const value = event.target.value as (typeof ALERT_SORTS)[number];
            set({ sort: value === 'priority' ? undefined : value });
          }}
          slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
          sx={{ minWidth: 200 }}
        >
          {ALERT_SORTS.map((sort) => (
            <option key={sort} value={sort}>
              {t(`sort.${sort}`)}
            </option>
          ))}
        </TextField>
        {isFiltered(search) ? (
          <Button
            onClick={() => {
              onChange(search.sort === undefined ? {} : { sort: search.sort });
              // The button disappears with the filters; focus stays in the filters, not on <body>.
              region.current?.focus();
            }}
            sx={{ minHeight: 48 }}
          >
            {t('filters.clear')}
          </Button>
        ) : null}
      </Stack>
    </Stack>
  );
}
