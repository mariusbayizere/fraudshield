import Alert, { type AlertColor } from '@mui/material/Alert';
import Stack from '@mui/material/Stack';
import { useTranslation } from 'react-i18next';

/** The system-wide conditions the API reports (OpenAPI `degraded_modes`), in contract order. */
export const SYSTEM_CONDITIONS = [
  'ML_UNAVAILABLE',
  'DEGRADED_MODE',
  'KAFKA_SPOOLING',
  'REALTIME_PAUSED',
] as const;
export type SystemCondition = (typeof SYSTEM_CONDITIONS)[number];

/** How loudly each condition shows; its words are in the catalogue under `banner.*`. */
export const CONDITION_SEVERITY: Record<SystemCondition, AlertColor> = {
  ML_UNAVAILABLE: 'warning',
  DEGRADED_MODE: 'warning',
  KAFKA_SPOOLING: 'info',
  REALTIME_PAUSED: 'info',
};

/**
 * One banner per active condition, above every screen. They are status messages, not alerts: a
 * screen reader hears them without being interrupted.
 */
export function SystemBanners({ conditions }: { conditions: readonly SystemCondition[] }) {
  const { t } = useTranslation('designSystem');
  const active = SYSTEM_CONDITIONS.filter((c) => conditions.includes(c));
  if (active.length === 0) return null;
  return (
    <Stack spacing={1}>
      {active.map((condition) => (
        <Alert
          key={condition}
          role="status"
          severity={CONDITION_SEVERITY[condition]}
          data-condition={condition}
        >
          {t(`banner.${condition}`)}
        </Alert>
      ))}
    </Stack>
  );
}
