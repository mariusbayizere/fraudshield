import { useQuery } from '@tanstack/react-query';
import { bodyOf } from '../api/body';
import { getSystemStatus } from '../api/generated/sdk.gen';
import { checked } from '../api/validate';
import {
  SYSTEM_CONDITIONS,
  type SystemCondition,
} from '../design-system/components/SystemBanner/SystemBanner';

/** E.9's degraded cadence: the console re-reads the status every 60 seconds (ADR 0081). */
export const SYSTEM_STATUS_POLL_MS = 60_000;

export async function fetchSystemConditions(): Promise<SystemCondition[]> {
  const status = bodyOf(await getSystemStatus());
  if (status === undefined) {
    throw new Error('the system status could not be read');
  }
  await checked('zSystemStatus', status);
  return SYSTEM_CONDITIONS.filter((condition) => status.degraded_modes.includes(condition));
}

/**
 * The degraded modes in force (ADR 0081). A status that cannot be read shows no banner: the
 * console must not claim the system is degraded because one poll failed, and the screens'
 * own error states already say when their data is missing.
 */
export function useSystemConditions(): readonly SystemCondition[] {
  const { data } = useQuery({
    queryKey: ['system-status'],
    queryFn: fetchSystemConditions,
    refetchInterval: SYSTEM_STATUS_POLL_MS,
    staleTime: SYSTEM_STATUS_POLL_MS,
    retry: 1,
  });
  return data ?? [];
}
