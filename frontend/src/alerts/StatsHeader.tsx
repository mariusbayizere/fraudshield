import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { AlertTier } from '../api/generated/types.gen';
import { Money } from '../design-system/components/Money/Money';
import { useFormatLocale } from '../region/formatLocale';
import { useDashboardStats } from './queries';

function Tile({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box component="div" sx={{ px: 1.5, py: 1, minWidth: 120 }}>
      <Typography component="dt" variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography component="dd" variant="h6" sx={{ m: 0 }}>
        {children}
      </Typography>
    </Box>
  );
}

/** A tier's open count that also filters the feed to that tier (UX-DASH-01: click to filter). */
function TierTile({
  tier,
  count,
  pressed,
  onToggle,
}: {
  tier: Extract<AlertTier, 'HIGH' | 'MEDIUM'>;
  count: string;
  pressed: boolean;
  onToggle: () => void;
}) {
  const { t } = useTranslation('alerts');
  const risk = tier === 'HIGH' ? 'high' : 'medium';
  return (
    <Tile label={t(tier === 'HIGH' ? 'stats.high' : 'stats.medium')}>
      <ButtonBase
        aria-pressed={pressed}
        aria-label={t('stats.filterTier', { tier: t(`tier.${tier}`), count })}
        onClick={onToggle}
        sx={(theme) => ({
          minWidth: 48,
          minHeight: 48,
          px: 1,
          borderRadius: 1,
          font: 'inherit',
          border: `2px solid ${pressed ? theme.palette.risk[risk].border : 'transparent'}`,
        })}
      >
        {count}
      </ButtonBase>
    </Tile>
  );
}

/**
 * Today's figures above the feed (UX-DASH-01). The HIGH and MEDIUM counts toggle the feed's tier
 * filter. Figures that cannot be read say so in one line; the feed below still works.
 */
export function StatsHeader({
  tiers,
  onToggleTier,
}: {
  tiers: readonly AlertTier[] | undefined;
  onToggleTier: (tier: 'HIGH' | 'MEDIUM') => void;
}) {
  const { t } = useTranslation('alerts');
  const locale = useFormatLocale();
  const stats = useDashboardStats();
  const integer = new Intl.NumberFormat(locale);
  const seconds = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 });
  const data = stats.data;
  const show = (value: number | undefined) => (value === undefined ? '—' : integer.format(value));

  if (stats.isError) {
    return (
      <Typography role="status" variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('stats.error')}
      </Typography>
    );
  }
  return (
    <Paper variant="outlined" component="section" aria-label={t('stats.label')} sx={{ mb: 2 }}>
      <Box
        component="dl"
        aria-busy={stats.isPending}
        sx={{ display: 'flex', flexWrap: 'wrap', m: 0 }}
      >
        <TierTile
          tier="HIGH"
          count={show(data?.high_open)}
          pressed={tiers?.includes('HIGH') ?? false}
          onToggle={() => {
            onToggleTier('HIGH');
          }}
        />
        <TierTile
          tier="MEDIUM"
          count={show(data?.medium_open)}
          pressed={tiers?.includes('MEDIUM') ?? false}
          onToggle={() => {
            onToggleTier('MEDIUM');
          }}
        />
        <Tile label={t('stats.autoBlocked')}>{show(data?.auto_blocked_today)}</Tile>
        <Tile label={t('stats.reviewed')}>{show(data?.reviewed_today)}</Tile>
        <Tile label={t('stats.fraudPrevented')}>
          {data === undefined ? (
            '—'
          ) : (
            <Money
              amount={data.fraud_prevented_today.amount}
              currency={data.fraud_prevented_today.currency}
            />
          )}
        </Tile>
        <Tile label={t('stats.averageReview')}>
          {data === undefined
            ? '—'
            : t('stats.seconds', { value: seconds.format(data.average_review_seconds) })}
        </Tile>
        <Tile label={t('stats.analystsOnline')}>{show(data?.analysts_online)}</Tile>
      </Box>
    </Paper>
  );
}
