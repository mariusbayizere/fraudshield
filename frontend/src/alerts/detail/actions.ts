import { useMutation, useQueryClient } from '@tanstack/react-query';
import { decideAlert, escalateAlert, undoAlertDecision } from '../../api/generated/sdk.gen';
import type {
  AlertDecisionAccepted,
  AlertDecisionRequest,
  AlertPage,
  AlertSummary,
  EscalationRequest,
} from '../../api/generated/types.gen';
import { checked } from '../../api/validate';
import { ApiError, bodyOrThrow } from '../queries';

/** The comment and reason rules the contract sets (AlertDecisionRequest, EscalationRequest). */
export const TEXT_MIN = 10;
export const TEXT_MAX = 2000;

/** Length as the contract counts it: code points, so an emoji or a Kinyarwanda letter is one. */
export function codePoints(text: string): number {
  return Array.from(text.trim()).length;
}

export function isLongEnough(text: string): boolean {
  const length = codePoints(text);
  return length >= TEXT_MIN && length <= TEXT_MAX;
}

// As for the reads: production skips the schema check, so a 2xx without these fields fails here.
function isAccepted(body: unknown): body is AlertDecisionAccepted {
  const accepted = body as AlertDecisionAccepted | null;
  return (
    typeof accepted === 'object' &&
    accepted !== null &&
    typeof accepted.decision_id === 'string' &&
    typeof accepted.undo_until === 'string'
  );
}

function isSummary(body: unknown): body is AlertSummary {
  const summary = body as AlertSummary | null;
  return typeof summary === 'object' && summary !== null && typeof summary.alert_id === 'string';
}

/** Removes an alert from every loaded page of every feed, as FR-04-04's "removes it" asks. */
function dropFromFeeds(client: ReturnType<typeof useQueryClient>, alertId: string) {
  client.setQueriesData<{ pages: AlertPage[]; pageParams: unknown[] }>(
    { queryKey: ['alerts'] },
    (data) =>
      data === undefined
        ? data
        : {
            ...data,
            pages: data.pages.map((page) => ({
              ...page,
              items: page.items.filter((item) => item.alert_id !== alertId),
            })),
          },
  );
}

/**
 * CONFIRM FRAUD or MARK LEGITIMATE (decideAlert, FR-04-04). The Idempotency-Key is made once per
 * logical action by the caller and reused if the same action is sent again (D-29); the alert's
 * version makes a decision on a changed alert fail with 409 instead of landing (D-29).
 */
export function useDecide(alertId: string) {
  const client = useQueryClient();
  return useMutation<
    AlertDecisionAccepted,
    ApiError,
    { body: AlertDecisionRequest; idempotencyKey: string }
  >({
    mutationFn: async ({ body, idempotencyKey }) => {
      const accepted = bodyOrThrow(
        await decideAlert({
          path: { alert_id: alertId },
          headers: { 'Idempotency-Key': idempotencyKey },
          body,
        }),
      );
      if (!isAccepted(accepted)) throw new ApiError(undefined);
      return checked('zAlertDecisionAccepted', accepted);
    },
    onSuccess: () => {
      dropFromFeeds(client, alertId);
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['alerts'] });
      void client.invalidateQueries({ queryKey: ['alert', alertId] });
      void client.invalidateQueries({ queryKey: ['dashboard-stats'] });
    },
  });
}

/** Undo within the grace window (undoAlertDecision, D-44): the alert comes back to the feed. */
export function useUndo() {
  const client = useQueryClient();
  return useMutation<undefined, ApiError, { alertId: string; decisionId: string }>({
    mutationFn: async ({ alertId, decisionId }) => {
      const result = await undoAlertDecision({
        path: { alert_id: alertId, decision_id: decisionId },
      });
      if (result.error !== undefined) throw new ApiError(result.error);
      return undefined;
    },
    onSettled: (_data, _error, { alertId }) => {
      void client.invalidateQueries({ queryKey: ['alerts'] });
      void client.invalidateQueries({ queryKey: ['alert', alertId] });
      void client.invalidateQueries({ queryKey: ['dashboard-stats'] });
    },
  });
}

/**
 * Escalate to a higher role with a reason (escalateAlert, FR-04-10): the server answers with the
 * new alert entry at the target tier, linked to this one.
 */
export function useEscalate(alertId: string) {
  const client = useQueryClient();
  return useMutation<AlertSummary, ApiError, { body: EscalationRequest; idempotencyKey: string }>({
    mutationFn: async ({ body, idempotencyKey }) => {
      const created = bodyOrThrow(
        await escalateAlert({
          path: { alert_id: alertId },
          headers: { 'Idempotency-Key': idempotencyKey },
          body,
        }),
      );
      if (!isSummary(created)) throw new ApiError(undefined);
      return checked('zAlertSummary', created);
    },
    onSuccess: () => {
      dropFromFeeds(client, alertId);
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['alerts'] });
      void client.invalidateQueries({ queryKey: ['alert', alertId] });
    },
  });
}
