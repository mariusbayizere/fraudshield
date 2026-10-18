import ArrowDownwardRounded from '@mui/icons-material/ArrowDownwardRounded';
import ArrowUpwardRounded from '@mui/icons-material/ArrowUpwardRounded';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { createLink } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  AlertStatus,
  AlertSummary,
  AlertTier,
  ShapContribution,
} from '../api/generated/types.gen';
import type { AlertSearch } from '../app/alertSearch';
import { ChannelChip } from '../design-system/components/ChannelChip/ChannelChip';
import { CountdownChip } from '../design-system/components/CountdownChip/CountdownChip';
import { Money } from '../design-system/components/Money/Money';
import { RiskCard } from '../design-system/components/RiskCard/RiskCard';
import { RiskScoreGauge } from '../design-system/components/RiskScoreGauge/RiskScoreGauge';
import type { RiskTier } from '../design-system/tokens';
import { formatClock } from '../region/region';
import { useRegion } from '../region/RegionContext';
import { useFormatLocale } from '../region/formatLocale';

const RouterLink = createLink(Link);

const OPEN: ReadonlySet<AlertStatus> = new Set(['PENDING', 'IN_REVIEW']);

/**
 * The feed's tiers drawn in the risk colours: HIGH and MEDIUM are the risk tiers' high and
 * medium. ANOMALY (Isolation Forest only, D-10) holds nothing and has no risk colour.
 */
export const CARD_TIER: Readonly<Record<AlertTier, RiskTier | null>> = {
  HIGH: 'high',
  MEDIUM: 'medium',
  ANOMALY: null,
};

function Frame({ tier, label, children }: { tier: AlertTier; label: string; children: ReactNode }) {
  const { t } = useTranslation('alerts');
  const riskTier = CARD_TIER[tier];
  if (riskTier !== null) {
    return (
      <RiskCard tier={riskTier} aria-label={label}>
        {children}
      </RiskCard>
    );
  }
  return (
    <Card component="article" variant="outlined" aria-label={label}>
      <CardContent>
        <Chip label={t('tier.ANOMALY')} size="small" variant="outlined" />
        {children}
      </CardContent>
    </Card>
  );
}

/** FR-04-03's SHAP pill: the feature, which way it pushed the score, and by how much. */
function ReasonPill({ reason }: { reason: ShapContribution }) {
  const { t } = useTranslation('alerts');
  const locale = useFormatLocale();
  const increases = reason.direction === 'INCREASES_RISK';
  const digits = { minimumFractionDigits: 2, maximumFractionDigits: 2 };
  const value = new Intl.NumberFormat(locale, { signDisplay: 'always', ...digits }).format(
    reason.shap,
  );
  // The sentence carries the direction, so it states the size without a sign.
  const size = new Intl.NumberFormat(locale, digits).format(Math.abs(reason.shap));
  return (
    <Chip
      component="li"
      size="small"
      variant="outlined"
      icon={increases ? <ArrowUpwardRounded /> : <ArrowDownwardRounded />}
      label={
        <>
          <Box component="span" sx={{ fontFamily: 'monospace' }}>
            {reason.feature}
          </Box>{' '}
          <Typography variant="numeric" component="span">
            {value}
          </Typography>
        </>
      }
      aria-label={t(increases ? 'reason.increases' : 'reason.decreases', {
        feature: reason.feature,
        value: size,
      })}
    />
  );
}

/**
 * One alert in a feed, with FR-04-03's eight elements: risk badge, fraud-probability gauge,
 * amount and currency, channel, masked account name, merchant, time, and the top three SHAP
 * pills. A MEDIUM alert with a review deadline also counts down to it (FR-04-02).
 */
export function AlertCard({ alert, search }: { alert: AlertSummary; search: AlertSearch }) {
  const { t } = useTranslation('alerts');
  const { region } = useRegion();
  const locale = useFormatLocale();
  // Only an alert still waiting for a decision counts down; a decided one keeps its deadline.
  const deadline =
    alert.tier === 'MEDIUM' && OPEN.has(alert.status) && alert.review_deadline_at !== null
      ? Date.parse(alert.review_deadline_at)
      : null;
  return (
    <Frame tier={alert.tier} label={t('card.label', { name: alert.masked_account_name })}>
      <Stack direction="row" spacing={2} sx={{ mt: 1, alignItems: 'flex-start' }}>
        <RiskScoreGauge score={alert.fraud_probability} />
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography variant="h6" component="h2">
            <RouterLink
              to="/alerts/$alertId"
              params={{ alertId: alert.alert_id }}
              search={search}
              underline="hover"
            >
              {alert.masked_account_name}
            </RouterLink>
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {alert.merchant ?? t('card.noMerchant')}
          </Typography>
          <Stack
            direction="row"
            spacing={1}
            useFlexGap
            sx={{ mt: 1, flexWrap: 'wrap', alignItems: 'center' }}
          >
            <Money amount={alert.amount.amount} currency={alert.amount.currency} />
            <ChannelChip channel={alert.channel} />
            <Typography variant="body2" component="time" dateTime={alert.transaction_timestamp}>
              {formatClock(new Date(alert.transaction_timestamp), region.utcOffsetHours, locale)}
            </Typography>
            <Chip size="small" label={t(`status.${alert.status}`)} />
            {deadline === null ? null : <CountdownChip deadlineMs={deadline} />}
          </Stack>
          {alert.top_reasons.length === 0 ? null : (
            <Stack
              component="ul"
              direction="row"
              spacing={1}
              useFlexGap
              aria-label={t('card.reasons')}
              sx={{ mt: 1, p: 0, listStyle: 'none', flexWrap: 'wrap' }}
            >
              {alert.top_reasons.map((reason) => (
                <ReasonPill key={reason.feature} reason={reason} />
              ))}
            </Stack>
          )}
        </Box>
      </Stack>
    </Frame>
  );
}
