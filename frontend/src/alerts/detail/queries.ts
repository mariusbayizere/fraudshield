import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getAlert, getAlertExplanation, getScoringResult } from '../../api/generated/sdk.gen';
import type { AlertDetail, Explanation, ScoringResult } from '../../api/generated/types.gen';
import { checked } from '../../api/validate';
import { ApiError, bodyOrThrow } from '../queries';

// As for the feed: production skips the schema check (ADR 0080 §3), so a 2xx that arrives as {}
// must fail here rather than reach the drawer. These are the fields the drawer cannot do without.
function isAlertDetail(body: unknown): body is AlertDetail {
  const alert = body as AlertDetail | null;
  return (
    typeof alert === 'object' &&
    alert !== null &&
    typeof alert.alert_id === 'string' &&
    typeof alert.amount === 'object' &&
    Array.isArray(alert.decisions)
  );
}

function isExplanation(body: unknown): body is Explanation {
  const explanation = body as Explanation | null;
  return (
    typeof explanation === 'object' &&
    explanation !== null &&
    typeof explanation.base_value === 'number' &&
    Array.isArray(explanation.contributions)
  );
}

function isScoringResult(body: unknown): body is ScoringResult {
  const result = body as ScoringResult | null;
  return typeof result === 'object' && result !== null && typeof result.ensemble_score === 'number';
}

export async function fetchAlert(alertId: string): Promise<AlertDetail> {
  const alert = bodyOrThrow(await getAlert({ path: { alert_id: alertId } }));
  if (!isAlertDetail(alert)) throw new ApiError(undefined);
  return checked('zAlertDetail', alert);
}

export async function fetchExplanation(alertId: string): Promise<Explanation> {
  const explanation = bodyOrThrow(await getAlertExplanation({ path: { alert_id: alertId } }));
  if (!isExplanation(explanation)) throw new ApiError(undefined);
  return checked('zExplanation', explanation);
}

export async function fetchScoringResult(transactionId: string): Promise<ScoringResult> {
  const result = bodyOrThrow(await getScoringResult({ path: { transaction_id: transactionId } }));
  if (!isScoringResult(result)) throw new ApiError(undefined);
  return checked('zScoringResult', result);
}

/**
 * The alert the drawer shows (getAlert). A 404 is not retried: the alert is not in the caller's
 * institution, and asking again will not change that. Anything else follows the client's default.
 */
export function useAlertDetail(alertId: string) {
  const fallback = useQueryClient().getDefaultOptions().queries?.retry ?? 3;
  return useQuery({
    queryKey: ['alert', alertId],
    queryFn: () => fetchAlert(alertId),
    retry: (count, error) => {
      if (error instanceof ApiError && error.status === 404) return false;
      if (typeof fallback === 'function') return fallback(count, error);
      return typeof fallback === 'number' ? count < fallback : fallback;
    },
  });
}

/** The model's full result for the alert's transaction; asked for once the alert has loaded. */
export function useScoringResult(transactionId: string | undefined) {
  return useQuery({
    queryKey: ['scoring-result', transactionId],
    queryFn: () => fetchScoringResult(transactionId ?? ''),
    enabled: transactionId !== undefined,
  });
}

/** All 44 contributions (getAlertExplanation); asked for only when the SHAP tab is opened. */
export function useExplanation(alertId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['explanation', alertId],
    queryFn: () => fetchExplanation(alertId),
    enabled,
  });
}
