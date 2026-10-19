import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { getMyPerformance } from '../api/generated/sdk.gen';
import type { AnalystPerformance } from '../api/generated/types.gen';
import { checked } from '../api/validate';
import { ApiError, bodyOrThrow, FEED_REFRESH_MS } from '../alerts/queries';
import { ErrorState } from '../design-system/components/ScreenStates/ScreenStates';
import { useFormatLocale } from '../region/formatLocale';

// As for the feed: production skips the schema check (ADR 0080 §3), so a 2xx that arrives as {}
// must fail here rather than show zeros. These are the fields the page cannot do without.
function isPerformance(body: unknown): body is AnalystPerformance {
  const p = body as AnalystPerformance | null;
  return (
    typeof p === 'object' &&
    p !== null &&
    typeof p.reviewed_today === 'number' &&
    typeof p.average_review_seconds === 'number' &&
    typeof p.backlog === 'number' &&
    typeof p.verified_decisions === 'number' &&
    (p.accuracy_rate === null || typeof p.accuracy_rate === 'number')
  );
}

export async function fetchMyPerformance(): Promise<AnalystPerformance> {
  const performance = bodyOrThrow(await getMyPerformance());
  if (!isPerformance(performance)) throw new ApiError(undefined);
  return checked('zAnalystPerformance', performance);
}

/** The caller's own figures, re-read at the feed's cadence: there is no real-time channel. */
export function useMyPerformance() {
  return useQuery({
    queryKey: ['my-performance'],
    queryFn: fetchMyPerformance,
    refetchInterval: FEED_REFRESH_MS,
  });
}

function Figure({ label, children, note }: { label: string; children: ReactNode; note?: string }) {
  return (
    <Paper component="div" variant="outlined" sx={{ p: 2, flex: '1 1 12rem' }}>
      <Typography component="dt" variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography component="dd" variant="h5" sx={{ m: 0 }}>
        {children}
      </Typography>
      {note === undefined ? null : (
        <Typography component="dd" variant="caption" color="text.secondary" sx={{ m: 0 }}>
          {note}
        </Typography>
      )}
    </Paper>
  );
}

/**
 * The analyst's own performance at /me/performance (FR-04-11, UX-ROLE-AN-04): alerts reviewed
 * today, average review time, backlog, and accuracy from customer verifications, which is shown as
 * not yet available, never as 0, until any decision has been verified. Only the caller's own
 * figures: getMyPerformance has no other analyst's. Re-read every 60 s.
 *
 * Not built: UX-ROLE-AN-04's daily trend, which needs a series the contract does not have. Built
 * against a mock of getMyPerformance, which only M8B implements.
 */
export default function MyPerformancePage() {
  const { t } = useTranslation('alerts');
  const { t: tCommon } = useTranslation();
  const locale = useFormatLocale();
  const performance = useMyPerformance();
  const count = new Intl.NumberFormat(locale);
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 });
  const minutes = new Intl.NumberFormat(locale, { style: 'unit', unit: 'minute' });
  const seconds = new Intl.NumberFormat(locale, { style: 'unit', unit: 'second' });
  const duration = (total: number) => {
    const rounded = Math.round(total);
    const m = Math.floor(rounded / 60);
    const s = rounded % 60;
    return m === 0 ? seconds.format(s) : `${minutes.format(m)} ${seconds.format(s)}`;
  };

  let body: ReactNode;
  if (performance.isPending) {
    body = (
      <Typography role="status" color="text.secondary">
        {t('performance.loading')}
      </Typography>
    );
  } else if (performance.isError) {
    body = (
      <ErrorState
        message={t('performance.error')}
        {...(performance.error instanceof ApiError && performance.error.correlationId !== undefined
          ? { correlationId: performance.error.correlationId }
          : {})}
        onRetry={() => {
          void performance.refetch();
        }}
      />
    );
  } else {
    const p = performance.data;
    body = (
      <Stack component="dl" direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', m: 0 }}>
        <Figure label={t('performance.reviewedToday')}>{count.format(p.reviewed_today)}</Figure>
        <Figure label={t('performance.averageReview')}>{duration(p.average_review_seconds)}</Figure>
        <Figure label={t('performance.backlog')}>{count.format(p.backlog)}</Figure>
        <Figure
          label={t('performance.accuracy')}
          note={
            p.accuracy_rate === null
              ? t('performance.accuracyPending')
              : t('performance.verified', { count: p.verified_decisions })
          }
        >
          {p.accuracy_rate === null ? t('performance.notYet') : percent.format(p.accuracy_rate)}
        </Figure>
      </Stack>
    );
  }

  return (
    <Box>
      <Typography variant="h5" component="h1" sx={{ mb: 1 }}>
        {tCommon('nav.performance')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('performance.intro')}
      </Typography>
      {body}
    </Box>
  );
}
