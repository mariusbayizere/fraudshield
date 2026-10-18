import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { getRouteApi } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { AlertSearch } from '../app/alertSearch';
import { EmptyState, ErrorState } from '../design-system/components/ScreenStates/ScreenStates';
import { AlertCard } from './AlertCard';
import { FeedFilters, isFiltered, toggled } from './FeedFilters';
import { ApiError, distinctAlerts, useAlertFeed } from './queries';
import { StatsHeader } from './StatsHeader';

const route = getRouteApi('/shell/alerts');

/**
 * The analyst's alert feed (FR-04-02, FR-04-03, FR-04-09, UX-DASH-01, UX-ROLE-AN-01): today's
 * figures, the filters, and the standard queue's alerts in the server's priority order, a page at
 * a time. Every filter is in the URL, so a filtered feed can be bookmarked and shared.
 *
 * It reads listAlerts and getDashboardStats, which only M8B implements; until then it runs
 * against mocks built from the contract's examples, which are not evidence the API works.
 */
export default function AlertFeedPage() {
  const { t } = useTranslation('alerts');
  const { t: tCommon } = useTranslation();
  // The route validates the search with alertSearch; the route tree's generic section routes
  // leave the registered type open, so the validated type is named here.
  const search: AlertSearch = route.useSearch();
  const navigate = route.useNavigate();
  const feed = useAlertFeed('standard', search);

  const change = (next: AlertSearch) => {
    void navigate({ to: '/alerts', search: next, replace: true });
  };
  const alerts = distinctAlerts(feed.data?.pages ?? []);
  // One live region, always mounted, so a screen reader hears each new count or state.
  // The error is announced by ErrorState's alert; this region says nothing then.
  const announcement = feed.isPending
    ? t('feed.loading')
    : feed.isError
      ? ''
      : t('feed.shown', { count: alerts.length });

  let body;
  if (feed.isPending) {
    body = (
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', py: 4 }}>
        <CircularProgress size={24} aria-hidden />
        <Typography aria-hidden>{t('feed.loading')}</Typography>
      </Stack>
    );
  } else if (feed.isError) {
    body = (
      <ErrorState
        message={t('feed.error')}
        {...(feed.error instanceof ApiError && feed.error.correlationId !== undefined
          ? { correlationId: feed.error.correlationId }
          : {})}
        onRetry={() => {
          void feed.refetch();
        }}
      />
    );
  } else if (alerts.length === 0) {
    body = isFiltered(search) ? (
      <EmptyState title={t('feed.noMatchTitle')} description={t('feed.noMatchDescription')} />
    ) : (
      <EmptyState title={t('feed.emptyTitle')} description={t('feed.emptyDescription')} />
    );
  } else {
    body = (
      <>
        <Stack component="ol" spacing={1.5} sx={{ p: 0, m: 0, listStyle: 'none' }}>
          {alerts.map((alert) => (
            <li key={alert.alert_id}>
              <AlertCard alert={alert} search={search} />
            </li>
          ))}
        </Stack>
        {feed.hasNextPage ? (
          <Button
            variant="outlined"
            disabled={feed.isFetchingNextPage}
            onClick={() => {
              void feed.fetchNextPage();
            }}
            sx={{ mt: 2, minHeight: 48 }}
          >
            {t('feed.loadMore')}
          </Button>
        ) : null}
      </>
    );
  }

  return (
    <>
      <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
        {tCommon('nav.alerts')}
      </Typography>
      <StatsHeader
        tiers={search.tier}
        onToggleTier={(tier) => {
          change({ ...search, tier: toggled(search.tier, tier) });
        }}
      />
      <FeedFilters search={search} tiers={['HIGH', 'MEDIUM']} onChange={change} />
      <section aria-label={t('feed.list')} aria-busy={feed.isFetching}>
        <Typography role="status" variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {announcement}
        </Typography>
        {body}
      </section>
    </>
  );
}
