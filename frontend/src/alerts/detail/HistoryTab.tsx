import BlockRounded from '@mui/icons-material/BlockRounded';
import CheckRounded from '@mui/icons-material/CheckRounded';
import HourglassEmptyRounded from '@mui/icons-material/HourglassEmptyRounded';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import type { AccountTransaction } from '../../api/generated/types.gen';
import { ChannelChip } from '../../design-system/components/ChannelChip/ChannelChip';
import { Money } from '../../design-system/components/Money/Money';
import { RiskBadge } from '../../design-system/components/RiskBadge/RiskBadge';
import { useFormatLocale } from '../../region/formatLocale';
import { formatDateTime } from '../../region/region';
import { useRegion } from '../../region/RegionContext';

type Outcome = AccountTransaction['outcome'];

/** The contract's tiers in the design system's risk colours. */
const RISK_TIER = { HIGH: 'high', MEDIUM: 'medium', LOW: 'low' } as const;

/** Each outcome has its own icon and words, so the badge reads without colour. */
const OUTCOME: Record<
  Outcome,
  { icon: typeof CheckRounded; color: 'success' | 'error' | 'default' }
> = {
  APPROVED: { icon: CheckRounded, color: 'success' },
  BLOCKED: { icon: BlockRounded, color: 'error' },
  PENDING: { icon: HourglassEmptyRounded, color: 'default' },
};

function OutcomeBadge({ outcome }: { outcome: Outcome }) {
  const { t } = useTranslation('alerts');
  const { icon: Icon, color } = OUTCOME[outcome];
  return (
    <Chip
      size="small"
      variant="outlined"
      color={color}
      icon={<Icon aria-hidden />}
      label={t(`history.outcome.${outcome}`)}
    />
  );
}

/**
 * The account's history (FR-04-06): its last 30 transactions, each with its amount, channel,
 * time, risk score and tier, and outcome; the alert's own transaction is marked. In the order the
 * server sends them, which listAccountTransactions promises is most recent first: re-sorting here
 * would hide a server that broke that promise rather than show it.
 */
export function HistoryTab({
  items,
  transactionId,
}: {
  items: readonly AccountTransaction[];
  /** The alert's transaction, marked where it appears. */
  transactionId: string;
}) {
  const { t } = useTranslation('alerts');
  const { region } = useRegion();
  const locale = useFormatLocale();
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 });

  if (items.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        {t('history.empty')}
      </Typography>
    );
  }

  return (
    <Box component="ol" aria-label={t('history.label')} sx={{ listStyle: 'none', p: 0, m: 0 }}>
      {items.map((item, index) => {
        const current = item.transaction_id === transactionId;
        return (
          <Box
            component="li"
            key={`${String(index)}:${item.transaction_id}`}
            aria-current={current ? 'true' : undefined}
            sx={(theme) => ({
              py: 1,
              px: current ? 1 : 0,
              borderBottom: `1px solid ${theme.palette.divider}`,
              ...(current
                ? { borderInlineStart: `4px solid ${theme.palette.secondary.main}` }
                : {}),
            })}
          >
            <Stack
              direction="row"
              spacing={1}
              useFlexGap
              sx={{ flexWrap: 'wrap', alignItems: 'center' }}
            >
              <Money amount={item.amount.amount} currency={item.amount.currency} />
              <ChannelChip channel={item.channel} />
              <OutcomeBadge outcome={item.outcome} />
              {current ? (
                <Typography variant="caption" sx={{ fontWeight: 600 }}>
                  {t('history.thisAlert')}
                </Typography>
              ) : null}
            </Stack>
            <Stack
              direction="row"
              spacing={1}
              useFlexGap
              sx={{ flexWrap: 'wrap', alignItems: 'center', mt: 0.5 }}
            >
              <Typography variant="body2" color="text.secondary">
                <time dateTime={item.transaction_timestamp}>
                  {formatDateTime(
                    new Date(item.transaction_timestamp),
                    region.utcOffsetHours,
                    locale,
                  )}
                </time>
              </Typography>
              <Typography variant="body2">
                {t('history.score', { value: percent.format(item.risk_score) })}
              </Typography>
              <RiskBadge tier={RISK_TIER[item.risk_tier]} size="small" />
            </Stack>
          </Box>
        );
      })}
    </Box>
  );
}
