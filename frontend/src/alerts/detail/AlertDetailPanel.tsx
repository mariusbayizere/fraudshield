import CloseRounded from '@mui/icons-material/CloseRounded';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { AlertSearch } from '../../app/alertSearch';
import { EmptyState, ErrorState } from '../../design-system/components/ScreenStates/ScreenStates';
import { DRAWER_WIDTH } from '../layout';
import type { FeedPath } from '../queueView';
import { ApiError } from '../queries';
import { useBehaviour, useHistory, useNetwork } from './accountQueries';
import { BehaviourTab } from './BehaviourTab';
import { HistoryTab } from './HistoryTab';
import { NetworkTab } from './NetworkTab';
import { useAlertDetail, useExplanation, useScoringResult } from './queries';
import { DecisionPanel } from './DecisionPanel';
import { ShapChart } from './ShapChart';
import { SummaryTab } from './SummaryTab';

/** A link's path, decoded; a malformed escape in some other link must not break the lookup. */
function pathOf(link: HTMLAnchorElement): string {
  try {
    return decodeURIComponent(new URL(link.href).pathname);
  } catch {
    return '';
  }
}

/**
 * Puts focus back where the drawer was opened from once it has gone: the card's link to this
 * alert, or the page's main region when no card shows it (a shared link). Only when focus was
 * lost with the drawer, never taken from somewhere the user moved it to.
 */
function returnFocus(base: FeedPath, alertId: string) {
  const active = document.activeElement;
  if (active !== null && active !== document.body && active.isConnected) return;
  // Compared as paths, so a link carrying filters matches.
  const opener = [...document.querySelectorAll<HTMLAnchorElement>('main a[href]')].find(
    (link) => pathOf(link) === `${base}/${alertId}`,
  );
  // Without scrolling: when the drawer switches alerts, the next heading takes focus at once.
  (opener ?? document.getElementById('main'))?.focus({ preventScroll: true });
}
const TABS = ['summary', 'shap', 'history', 'behaviour', 'network'] as const;
type TabName = (typeof TABS)[number];

/** A tab's content once its request has answered: a status while it loads, an error with Retry. */
function Loaded<T>({
  query,
  loading,
  error,
  children,
}: {
  query: {
    isPending: boolean;
    isError: boolean;
    data: T | undefined;
    error: unknown;
    refetch: () => unknown;
  };
  loading: string;
  error: string;
  children: (data: T) => ReactNode;
}) {
  if (query.isPending) {
    return (
      <Typography role="status" color="text.secondary">
        {loading}
      </Typography>
    );
  }
  if (query.isError || query.data === undefined) {
    return (
      <ErrorState
        message={error}
        {...(query.error instanceof ApiError && query.error.correlationId !== undefined
          ? { correlationId: query.error.correlationId }
          : {})}
        onRetry={() => {
          void query.refetch();
        }}
      />
    );
  }
  return children(query.data);
}

/**
 * The large-screen drawer is not modal: the feed behind it stays usable (UX-DASH-04). Below the
 * lg breakpoint the same content opens as a full-screen dialog (MOB-COMP-02).
 */
function Shell({
  wide,
  label,
  onClose,
  children,
}: {
  wide: boolean;
  label: string;
  onClose: () => void;
  children: ReactNode;
}) {
  if (!wide) {
    return (
      <Dialog open fullScreen onClose={onClose} slotProps={{ paper: { 'aria-label': label } }}>
        {children}
      </Dialog>
    );
  }
  return (
    <Drawer
      open
      variant="persistent"
      anchor="right"
      slotProps={{
        paper: {
          component: 'section',
          'aria-label': label,
          onKeyDown: (event: React.KeyboardEvent) => {
            if (event.key === 'Escape') onClose();
          },
          sx: { width: DRAWER_WIDTH, maxWidth: '100vw' },
        },
      }}
    >
      <Toolbar />
      {children}
    </Drawer>
  );
}

/**
 * The investigation drawer at `${base}/$alertId` (UX-DASH-04, UX-ROLE-AN-02, FR-04-05 to 08): the
 * alert's summary, its SHAP explanation and its account's history, behaviour and network, over
 * the queue it was opened from, which keeps its filters. Each tab but the summary asks for its data when opened. Built
 * against mocks of getAlert, getScoringResult, getAlertExplanation, listAccountTransactions,
 * getBehaviourProfile and getAccountNetwork, which only M8B implements.
 */
function InvestigationDrawer({ base }: { base: FeedPath }) {
  const { t } = useTranslation('alerts');
  // As for the queue's search, the route tree's generic sections leave these types open; both
  // drawer routes are `${base}/$alertId` under a route that validates alertSearch.
  const { alertId }: { alertId: string } = useParams({ strict: false });
  const search: AlertSearch = useSearch({ strict: false });
  const navigate = useNavigate();
  const wide = useMediaQuery((theme) => theme.breakpoints.up('lg'), { noSsr: true });
  // MOB-COMP-08's phone view is below 600 px (xs), not everything below the drawer's breakpoint.
  const phone = useMediaQuery((theme) => theme.breakpoints.down('sm'), { noSsr: true });
  // The tab belongs to the alert it was chosen on: another alert opens on its summary.
  const [chosen, setChosen] = useState<{ alertId: string; tab: TabName }>({
    alertId,
    tab: 'summary',
  });
  const tab = chosen.alertId === alertId ? chosen.tab : 'summary';
  const heading = useRef<HTMLHeadingElement>(null);

  const alert = useAlertDetail(alertId);
  const scoring = useScoringResult(alert.data?.transaction_id);
  const explanation = useExplanation(alertId, tab === 'shap');
  // The account tabs need the alert's account and transaction, so wait for the alert.
  const accountToken = alert.data?.account_token ?? '';
  const transactionId = alert.data?.transaction_id ?? '';
  const loaded = alert.data !== undefined;
  const history = useHistory(accountToken, loaded && tab === 'history');
  const behaviour = useBehaviour(accountToken, transactionId, loaded && tab === 'behaviour');
  const network = useNetwork(accountToken, loaded && tab === 'network');

  // Closing replaces the drawer's history entry, so Back does not reopen it.
  const close = () => {
    void navigate({ to: base, search, replace: true });
  };

  // Focus moves to the alert's heading when it opens, so a keyboard or screen-reader user lands
  // in the drawer rather than behind it.
  // However the drawer goes (close, Escape, the browser's Back), focus returns to its opener.
  useEffect(() => {
    heading.current?.focus();
    return () => {
      returnFocus(base, alertId);
    };
  }, [base, alertId]);

  const title = alert.data?.masked_account_name ?? t('detail.title');

  let body: ReactNode;
  if (alert.isPending) {
    body = (
      <Stack role="status" direction="row" spacing={2} sx={{ alignItems: 'center', p: 3 }}>
        <CircularProgress size={24} aria-hidden />
        <Typography>{t('detail.loading')}</Typography>
      </Stack>
    );
  } else if (alert.isError) {
    const notFound = alert.error instanceof ApiError && alert.error.status === 404;
    body = notFound ? (
      <EmptyState title={t('detail.notFoundTitle')} description={t('detail.notFoundDescription')} />
    ) : (
      <ErrorState
        message={t('detail.error')}
        {...(alert.error instanceof ApiError && alert.error.correlationId !== undefined
          ? { correlationId: alert.error.correlationId }
          : {})}
        onRetry={() => {
          void alert.refetch();
        }}
      />
    );
  } else {
    body = (
      <>
        <Tabs
          value={tab}
          onChange={(_event, next: TabName) => {
            setChosen({ alertId, tab: next });
          }}
          aria-label={t('detail.tabs')}
          variant="scrollable"
        >
          {TABS.map((name) => (
            <Tab
              key={name}
              value={name}
              label={t(`detail.${name}`)}
              id={`fs-tab-${name}`}
              {...(tab === name ? { 'aria-controls': `fs-panel-${name}` } : {})}
            />
          ))}
        </Tabs>
        <Box
          role="tabpanel"
          id={`fs-panel-${tab}`}
          aria-labelledby={`fs-tab-${tab}`}
          sx={{ p: 2, overflowY: 'auto' }}
        >
          {tab === 'summary' ? (
            <SummaryTab alert={alert.data} scoring={scoring.isError ? null : scoring.data} />
          ) : tab === 'shap' ? (
            <Loaded query={explanation} loading={t('shap.loading')} error={t('shap.error')}>
              {(data) => <ShapChart explanation={data} phone={phone} />}
            </Loaded>
          ) : tab === 'history' ? (
            <Loaded query={history} loading={t('history.loading')} error={t('history.error')}>
              {(data) => (
                <HistoryTab items={data.items} transactionId={alert.data.transaction_id} />
              )}
            </Loaded>
          ) : tab === 'behaviour' ? (
            <Loaded query={behaviour} loading={t('behaviour.loading')} error={t('behaviour.error')}>
              {(data) => <BehaviourTab profile={data} />}
            </Loaded>
          ) : (
            <Loaded query={network} loading={t('network.loading')} error={t('network.error')}>
              {(data) => <NetworkTab network={data} />}
            </Loaded>
          )}
        </Box>
        {/* Keyed by alert: another alert starts with an empty comment and its own keys. */}
        <DecisionPanel key={alert.data.alert_id} alert={alert.data} onDone={close} />
      </>
    );
  }

  return (
    <Shell wide={wide} label={t('detail.label', { name: title })} onClose={close}>
      <Stack direction="row" sx={{ alignItems: 'center', px: 2, pt: 2 }}>
        <Typography
          ref={heading}
          tabIndex={-1}
          variant="h6"
          component="h2"
          sx={{ flexGrow: 1, minWidth: 0 }}
        >
          {title}
        </Typography>
        <IconButton
          aria-label={t('detail.close')}
          onClick={close}
          sx={{ minWidth: 48, minHeight: 48 }}
        >
          <CloseRounded />
        </IconButton>
      </Stack>
      {body}
    </Shell>
  );
}

/** The drawer beside the standard feed, at /alerts/$alertId. */
export default function AlertDetailPanel() {
  return <InvestigationDrawer base="/alerts" />;
}

/** The same drawer beside the anomaly queue, at /anomalies/$alertId (D-10). */
export function AnomalyDetailPanel() {
  return <InvestigationDrawer base="/anomalies" />;
}

/** The same drawer beside the search results, at /search/$alertId (FR-04-09). */
export function SearchDetailPanel() {
  return <InvestigationDrawer base="/search" />;
}

/** The same drawer beside the escalations queue, at /escalations/$alertId (FR-04-10). */
export function EscalationDetailPanel() {
  return <InvestigationDrawer base="/escalations" />;
}
