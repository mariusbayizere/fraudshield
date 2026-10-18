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
import { getRouteApi } from '@tanstack/react-router';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { AlertSearch } from '../../app/alertSearch';
import { EmptyState, ErrorState } from '../../design-system/components/ScreenStates/ScreenStates';
import { ApiError } from '../queries';
import { useAlertDetail, useExplanation, useScoringResult } from './queries';
import { ShapChart } from './ShapChart';
import { SummaryTab } from './SummaryTab';

const feedRoute = getRouteApi('/shell/alerts');
const detailRoute = getRouteApi('/shell/alerts/$alertId');

/** UX-DASH-04's drawer width on large screens. */
export const DRAWER_WIDTH = 560;

type TabName = 'summary' | 'shap';

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
 * The investigation drawer at /alerts/$alertId (UX-DASH-04, UX-ROLE-AN-02, FR-04-05): the alert's
 * summary and its SHAP explanation, over the feed, which keeps its filters. Built against mocks
 * of getAlert, getScoringResult and getAlertExplanation, which only M8B implements.
 */
export default function AlertDetailPanel() {
  const { t } = useTranslation('alerts');
  // As for the feed's search, the route tree's generic sections leave these types open.
  const { alertId }: { alertId: string } = detailRoute.useParams();
  const search: AlertSearch = feedRoute.useSearch();
  const navigate = feedRoute.useNavigate();
  const wide = useMediaQuery((theme) => theme.breakpoints.up('lg'), { noSsr: true });
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

  const close = () => {
    void navigate({ to: '/alerts', search, replace: false });
  };

  // Focus moves to the alert's heading when it opens, so a keyboard or screen-reader user lands
  // in the drawer rather than behind it.
  useEffect(() => {
    heading.current?.focus();
  }, [alertId]);

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
          <Tab
            value="summary"
            label={t('detail.summary')}
            id="fs-tab-summary"
            aria-controls="fs-panel-summary"
          />
          <Tab
            value="shap"
            label={t('detail.shap')}
            id="fs-tab-shap"
            aria-controls="fs-panel-shap"
          />
        </Tabs>
        <Box
          role="tabpanel"
          id={`fs-panel-${tab}`}
          aria-labelledby={`fs-tab-${tab}`}
          sx={{ p: 2, overflowY: 'auto' }}
        >
          {tab === 'summary' ? (
            <SummaryTab alert={alert.data} scoring={scoring.isError ? null : scoring.data} />
          ) : explanation.isPending ? (
            <Typography role="status" color="text.secondary">
              {t('shap.loading')}
            </Typography>
          ) : explanation.isError ? (
            <ErrorState
              message={t('shap.error')}
              onRetry={() => {
                void explanation.refetch();
              }}
            />
          ) : (
            <ShapChart explanation={explanation.data} phone={!wide} />
          )}
        </Box>
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
