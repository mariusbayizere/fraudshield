import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { Outlet, useChildMatches, useNavigate, useSearch } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { AlertSearch } from '../app/alertSearch';
import { EmptyState, ErrorState } from '../design-system/components/ScreenStates/ScreenStates';
import { AlertCard } from './AlertCard';
import { FeedFilters, isFiltered, toggled } from './FeedFilters';
import { DRAWER_WIDTH } from './layout';
import { ApiError, distinctAlerts, useAlertFeed } from './queries';
import {
  ANOMALY_VIEW,
  ESCALATED_VIEW,
  SEARCH_VIEW,
  STANDARD_VIEW,
  type QueueView,
} from './queueView';
import { SearchForm } from './SearchForm';
import { StatsHeader } from './StatsHeader';
import { NoticeProvider } from './undo';

/**
 * One alert queue as a page: the filters and the queue's alerts in the server's order, a page at a
 * time, with today's figures above the standard queue. Every filter is in the URL, so a filtered
 * queue can be bookmarked and shared, and an alert opens in the investigation drawer beside it.
 *
 * It reads listAlerts and getDashboardStats, which only M8B implements; until then it runs
 * against mocks built from the contract's examples, which are not evidence the API works.
 */
function QueuePage({ view }: { view: QueueView }) {
  const { t } = useTranslation('alerts');
  const { t: tCommon } = useTranslation();
  // Both queue routes validate their search with alertSearch; the route tree's generic section
  // routes leave the registered type open, so the validated type is named here.
  const search: AlertSearch = useSearch({ strict: false });
  const navigate = useNavigate();
  // A search with no criteria asks nothing of the server.
  const idle = view.criteria && !isFiltered(search);
  const feed = useAlertFeed(view.queue, search, !idle);
  // While the investigation drawer is docked beside the feed (lg and wider), the feed keeps its
  // width clear, so no card, filter or button sits under the panel (UX-DASH-04).
  const drawerOpen = useChildMatches().length > 0;
  const wide = useMediaQuery((theme) => theme.breakpoints.up('lg'), { noSsr: true });

  const change = (next: AlertSearch) => {
    void navigate({ to: view.path, search: next, replace: true });
  };
  const alerts = distinctAlerts(feed.data?.pages ?? []);
  // One live region, always mounted, so a screen reader hears each new count or state.
  // The error is announced by ErrorState's alert; this region says nothing then.
  const announcement = idle
    ? ''
    : feed.isPending
      ? t('feed.loading')
      : feed.isError
        ? ''
        : t('feed.shown', { count: alerts.length });

  let body;
  if (idle && view.copy !== null) {
    body = (
      <EmptyState
        title={t(`${view.copy}.emptyTitle`)}
        description={t(`${view.copy}.emptyDescription`)}
      />
    );
  } else if (feed.isPending) {
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
    ) : view.copy !== null ? (
      <EmptyState
        title={t(`${view.copy}.emptyTitle`)}
        description={t(`${view.copy}.emptyDescription`)}
      />
    ) : (
      <EmptyState title={t('feed.emptyTitle')} description={t('feed.emptyDescription')} />
    );
  } else {
    body = (
      <>
        <Stack component="ol" spacing={1.5} sx={{ p: 0, m: 0, listStyle: 'none' }}>
          {alerts.map((alert) => (
            <li key={alert.alert_id}>
              <AlertCard alert={alert} search={search} base={view.path} />
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
    <NoticeProvider>
      <Box
        data-testid="alert-feed"
        style={drawerOpen && wide ? { marginInlineEnd: DRAWER_WIDTH } : undefined}
      >
        <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
          {tCommon(view.title)}
        </Typography>
        {view.copy !== null ? (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t(`${view.copy}.intro`)}
          </Typography>
        ) : null}
        {view.stats ? (
          <StatsHeader
            tiers={search.tier}
            onToggleTier={(tier) => {
              change({ ...search, tier: toggled(search.tier, tier) });
            }}
          />
        ) : null}
        {view.criteria ? (
          // Keyed by the URL's criteria, so Back or a shared link refills the form.
          <SearchForm key={JSON.stringify(search)} search={search} onSearch={change} />
        ) : null}
        <FeedFilters
          search={search}
          tiers={view.tiers}
          onChange={change}
          showLinkOnly={!view.criteria}
        />
        <section aria-label={t('feed.list')} aria-busy={feed.isFetching}>
          <Typography role="status" variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {announcement}
          </Typography>
          {body}
        </section>
        {/* The investigation drawer, when the URL names an alert (/alerts/$alertId). */}
        <Outlet />
      </Box>
    </NoticeProvider>
  );
}

/**
 * The analyst's alert feed (FR-04-02, FR-04-03, FR-04-09, UX-DASH-01, UX-ROLE-AN-01): today's
 * figures, the filters, and the standard queue's alerts in the server's priority order.
 */
export default function AlertFeedPage() {
  return <QueuePage view={STANDARD_VIEW} />;
}

/**
 * D-10's anomaly queue at /anomalies: Isolation-Forest-only alerts, which hold nothing and have
 * no review timer. The same cards, filters and drawer as the feed; no tier filter (every alert is
 * ANOMALY) and no figures (they head the standard queue, and their counts toggle its tiers).
 */
export function AnomalyQueuePage() {
  return <QueuePage view={ANOMALY_VIEW} />;
}

/**
 * The escalations queue at /escalations (FR-04-10): alerts escalated to the caller's role
 * or below it, and those the caller escalated, as listAlerts(queue=escalated) defines it. The
 * server decides what the caller may see; the console shows what it is sent.
 */
export function EscalationsQueuePage() {
  return <QueuePage view={ESCALATED_VIEW} />;
}

/** FR-04-09's search at /search: criteria, then matching alerts as cards, with the drawer. */
export function SearchPage() {
  return <QueuePage view={SEARCH_VIEW} />;
}
