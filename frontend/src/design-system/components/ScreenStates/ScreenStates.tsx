import CloudOffOutlined from '@mui/icons-material/CloudOffOutlined';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { formatClock } from '../../../region/region';
import { useFormatLocale, useRegion } from '../../../region/RegionContext';

// The reusable parts of E.9's screen state contract. Loading skeletons are per layout, so each
// view draws its own, matching its final shape (CLS < 0.1).

export interface EmptyStateProps {
  title: string;
  /** What the emptiness means, e.g. "No alerts need review right now." */
  description: string;
  /** The next action, if there is one. */
  action?: ReactNode;
  titleComponent?: 'h2' | 'h3';
}

/** Empty: what it means and what to do next. */
export function EmptyState({ title, description, action, titleComponent = 'h2' }: EmptyStateProps) {
  return (
    <Box component="section" sx={{ py: 6, px: 2, textAlign: 'center' }}>
      <Typography variant="h6" component={titleComponent}>
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        {description}
      </Typography>
      {action === undefined ? null : <Box sx={{ mt: 2 }}>{action}</Box>}
    </Box>
  );
}

export interface ErrorStateProps {
  /** A plain sentence, never a stack trace or status code alone. */
  message: string;
  /** The request's correlation ID, for the analyst to quote to support. */
  correlationId?: string;
  onRetry?: () => void;
}

/** Error: a plain message, a retry, and the correlation ID support needs. */
export function ErrorState({ message, correlationId, onRetry }: ErrorStateProps) {
  const { t } = useTranslation('designSystem');
  return (
    <Alert
      severity="error"
      action={
        onRetry === undefined ? undefined : (
          <Button color="inherit" size="small" onClick={onRetry} sx={{ minHeight: 48 }}>
            {t('states.retry')}
          </Button>
        )
      }
    >
      {message}
      {correlationId === undefined ? null : (
        <Typography variant="body2" component="p" sx={{ mt: 0.5 }}>
          {t('states.reference')}{' '}
          <Box component="code" sx={{ fontFamily: 'monospace', userSelect: 'all' }}>
            {correlationId}
          </Box>
        </Typography>
      )}
    </Alert>
  );
}

/**
 * Stale or offline: the data shown is a cached copy, and from when, in the region's time with
 * its zone ("Last updated 14:02 UTC+2"; D-43).
 */
export function StaleNotice({ lastUpdated }: { lastUpdated: Date }) {
  const { t } = useTranslation('designSystem');
  const { region } = useRegion();
  const locale = useFormatLocale();
  return (
    <Alert severity="info" icon={<CloudOffOutlined aria-hidden />} role="status">
      {t('states.lastUpdated', { time: formatClock(lastUpdated, region.utcOffsetHours, locale) })}
    </Alert>
  );
}

/** Permission denied: what happened and who can change it, without naming what is hidden. */
export function PermissionDeniedState() {
  const { t } = useTranslation('designSystem');
  return <EmptyState title={t('states.deniedTitle')} description={t('states.deniedDescription')} />;
}
