import Alert, { type AlertColor } from '@mui/material/Alert';
import Stack from '@mui/material/Stack';

/** The system-wide conditions the API reports (OpenAPI `degraded_modes`), in contract order. */
export const SYSTEM_CONDITIONS = [
  'ML_UNAVAILABLE',
  'DEGRADED_MODE',
  'KAFKA_SPOOLING',
  'REALTIME_PAUSED',
] as const;
export type SystemCondition = (typeof SYSTEM_CONDITIONS)[number];

/** What each condition means for the analyst's work (build prompt C.4, E.9). */
export const CONDITION_BANNER: Record<SystemCondition, { severity: AlertColor; text: string }> = {
  ML_UNAVAILABLE: {
    severity: 'warning',
    text: 'The scoring model is unavailable. Rules are deciding for now; these transactions will be re-scored when it recovers.',
  },
  DEGRADED_MODE: {
    severity: 'warning',
    text: 'Running in degraded mode. Decisions continue, more slowly than usual.',
  },
  KAFKA_SPOOLING: {
    severity: 'info',
    text: 'Event delivery is delayed. Decisions are unaffected; alerts and reports may arrive late.',
  },
  REALTIME_PAUSED: {
    severity: 'info',
    text: 'Real-time paused — refreshing every 60 s',
  },
};

/**
 * One banner per active condition, above every screen. They are status messages, not alerts: a
 * screen reader hears them without being interrupted.
 */
export function SystemBanners({ conditions }: { conditions: readonly SystemCondition[] }) {
  const active = SYSTEM_CONDITIONS.filter((c) => conditions.includes(c));
  if (active.length === 0) return null;
  return (
    <Stack spacing={1}>
      {active.map((condition) => (
        <Alert
          key={condition}
          role="status"
          severity={CONDITION_BANNER[condition].severity}
          data-condition={condition}
        >
          {CONDITION_BANNER[condition].text}
        </Alert>
      ))}
    </Stack>
  );
}
