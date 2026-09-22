import CloudOffOutlined from '@mui/icons-material/CloudOffOutlined';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';

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
  return (
    <Alert
      severity="error"
      action={
        onRetry === undefined ? undefined : (
          <Button color="inherit" size="small" onClick={onRetry} sx={{ minHeight: 48 }}>
            Try again
          </Button>
        )
      }
    >
      {message}
      {correlationId === undefined ? null : (
        <Typography variant="body2" component="p" sx={{ mt: 0.5 }}>
          Reference:{' '}
          <Box component="code" sx={{ fontFamily: 'monospace', userSelect: 'all' }}>
            {correlationId}
          </Box>
        </Typography>
      )}
    </Alert>
  );
}

const KIGALI_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Kigali',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** Rwanda keeps Central Africa Time, UTC+2 all year, so the label never changes with DST. */
export function formatLastUpdated(at: Date): string {
  return `Last updated ${KIGALI_TIME.format(at)} CAT`;
}

/** Stale or offline: the data shown is a cached copy, and from when. */
export function StaleNotice({ lastUpdated }: { lastUpdated: Date }) {
  return (
    <Alert severity="info" icon={<CloudOffOutlined aria-hidden />} role="status">
      {formatLastUpdated(lastUpdated)}
    </Alert>
  );
}

/** Permission denied: what happened and who can change it, without naming what is hidden. */
export function PermissionDeniedState() {
  return (
    <EmptyState
      title="You don't have access to this page"
      description="Your role doesn't include it. An administrator can change your access."
    />
  );
}
