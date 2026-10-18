import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import type { AlertStatus } from '../api/generated/types.gen';
import { ALERT_SORTS, ALERT_STATUSES, type AlertSearch } from '../app/alertSearch';
import { CHANNELS } from '../design-system/tokens';

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

/**
 * The feed's filters (FR-04-09, UX-ROLE-AN-01: tier, channel, status), each one a listAlerts
 * parameter carried in the URL. Dates, amounts and identifiers are the search screen's.
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
  // A filter set to undefined is dropped by the route's validation, so it leaves the URL.
  const set = (patch: Partial<AlertSearch>) => {
    onChange({ ...search, ...patch });
  };

  return (
    <Stack component="section" aria-label={t('filters.label')} spacing={1.5} sx={{ mb: 2 }}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Typography component="span" variant="body2" color="text.secondary">
          {t('filters.tier')}
        </Typography>
        {tiers.map((tier) => (
          <Chip
            key={tier}
            label={t(`tier.${tier}`)}
            aria-pressed={search.tier?.includes(tier) ?? false}
            color={search.tier?.includes(tier) === true ? 'primary' : 'default'}
            onClick={() => {
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
          <Chip
            key={channel}
            label={tDesign(`channel.${channel}`)}
            aria-pressed={search.channel?.includes(channel) ?? false}
            color={search.channel?.includes(channel) === true ? 'primary' : 'default'}
            onClick={() => {
              set({ channel: toggled(search.channel, channel) });
            }}
          />
        ))}
      </Stack>
      <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          select
          size="small"
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
          size="small"
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
