import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { AlertDetail, DecisionRecord, ScoringResult } from '../../api/generated/types.gen';
import { ChannelChip } from '../../design-system/components/ChannelChip/ChannelChip';
import { CountdownChip } from '../../design-system/components/CountdownChip/CountdownChip';
import { Money } from '../../design-system/components/Money/Money';
import { RiskBadge } from '../../design-system/components/RiskBadge/RiskBadge';
import { RiskScoreGauge } from '../../design-system/components/RiskScoreGauge/RiskScoreGauge';
import { useFormatLocale } from '../../region/formatLocale';
import { formatDateTime } from '../../region/region';
import { useRegion } from '../../region/RegionContext';
import { CARD_TIER, OPEN } from '../AlertCard';

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box sx={{ py: 0.75 }}>
      <Typography component="dt" variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography component="dd" variant="body1" sx={{ m: 0 }}>
        {children}
      </Typography>
    </Box>
  );
}

function Decision({ record }: { record: DecisionRecord }) {
  const { t } = useTranslation('alerts');
  const { region } = useRegion();
  const locale = useFormatLocale();
  const who =
    record.analyst === null
      ? t('detail.system')
      : `${record.analyst.first_name} ${record.analyst.last_name}`;
  return (
    <Box component="li" sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Typography variant="body1" component="span" sx={{ fontWeight: 600 }}>
          {t(`decision.${record.decision}`)}
        </Typography>
        {record.state === 'COMMITTED' ? null : (
          <Chip size="small" variant="outlined" label={t(`decisionState.${record.state}`)} />
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        {who} ·{' '}
        <time dateTime={record.decided_at}>
          {formatDateTime(new Date(record.decided_at), region.utcOffsetHours, locale)}
        </time>
      </Typography>
      {record.comment === null || record.comment === undefined ? null : (
        <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>
          {record.comment}
        </Typography>
      )}
    </Box>
  );
}

/**
 * The investigation drawer's summary (UX-DASH-04, UX-ROLE-AN-02): the transaction, the model's
 * scores (getScoringResult, FR-02-01's fields), and every decision taken on the alert.
 */
export function SummaryTab({
  alert,
  scoring,
}: {
  alert: AlertDetail;
  /** Undefined while loading; null when it could not be read (the rest still shows). */
  scoring: ScoringResult | null | undefined;
}) {
  const { t } = useTranslation('alerts');
  const { region } = useRegion();
  const locale = useFormatLocale();
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 });
  const riskTier = CARD_TIER[alert.tier];
  const deadline =
    alert.tier === 'MEDIUM' && OPEN.has(alert.status) && alert.review_deadline_at !== null
      ? Date.parse(alert.review_deadline_at)
      : null;

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        {riskTier === null ? (
          <Chip label={t('tier.ANOMALY')} variant="outlined" />
        ) : (
          <RiskBadge tier={riskTier} />
        )}
        <Chip label={t(`status.${alert.status}`)} />
        {deadline === null ? null : <CountdownChip deadlineMs={deadline} />}
      </Stack>

      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <RiskScoreGauge score={alert.fraud_probability} size={64} />
        <Typography variant="body2" color="text.secondary">
          {t('detail.anomalyScore', { value: percent.format(alert.anomaly_score) })}
        </Typography>
      </Stack>

      <Box component="dl" sx={{ m: 0 }}>
        <Field label={t('detail.amount')}>
          <Money amount={alert.amount.amount} currency={alert.amount.currency} />
        </Field>
        <Field label={t('detail.channel')}>
          <ChannelChip channel={alert.channel} />
        </Field>
        <Field label={t('detail.merchant')}>{alert.merchant ?? t('card.noMerchant')}</Field>
        <Field label={t('detail.time')}>
          <time dateTime={alert.transaction_timestamp}>
            {formatDateTime(new Date(alert.transaction_timestamp), region.utcOffsetHours, locale)}
          </time>
        </Field>
      </Box>

      <Box component="section" aria-labelledby="fs-detail-scores">
        <Typography id="fs-detail-scores" variant="subtitle1" component="h3">
          {t('detail.scores')}
        </Typography>
        {scoring === undefined ? (
          <Typography role="status" variant="body2" color="text.secondary">
            {t('detail.scoresLoading')}
          </Typography>
        ) : scoring === null ? (
          <Typography variant="body2" color="text.secondary">
            {t('detail.scoresError')}
          </Typography>
        ) : (
          <>
            {scoring.ml_unavailable_fallback ? (
              <Alert severity="warning" sx={{ my: 1 }}>
                {t('detail.fallback')}
              </Alert>
            ) : null}
            <Box component="dl" sx={{ m: 0 }}>
              <Field label={t('detail.ensemble')}>{percent.format(scoring.ensemble_score)}</Field>
              <Field label={t('detail.xgboost')}>{percent.format(scoring.xgboost_score)}</Field>
              <Field label={t('detail.lightgbm')}>{percent.format(scoring.lightgbm_score)}</Field>
              <Field label={t('detail.modelVersion')}>{scoring.model_version}</Field>
              <Field label={t('detail.scoringTime')}>
                {t('detail.milliseconds', { value: scoring.scoring_duration_ms })}
              </Field>
            </Box>
          </>
        )}
      </Box>

      <Box component="section" aria-labelledby="fs-detail-decisions">
        <Typography id="fs-detail-decisions" variant="subtitle1" component="h3">
          {t('detail.decisions')}
        </Typography>
        {alert.decisions.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t('detail.noDecisions')}
          </Typography>
        ) : (
          <Box component="ol" sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {alert.decisions.map((record) => (
              <Decision key={record.decision_id} record={record} />
            ))}
          </Box>
        )}
      </Box>
    </Stack>
  );
}
