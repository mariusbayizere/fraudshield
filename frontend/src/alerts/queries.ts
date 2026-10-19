import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { getDashboardStats, listAlerts } from '../api/generated/sdk.gen';
import type { AlertPage, DashboardStats, ListAlertsData } from '../api/generated/types.gen';
import { checked } from '../api/validate';
import type { AlertSearch } from '../app/alertSearch';

/**
 * How often the feed and its header are re-read. The contract has no real-time channel for
 * alerts yet (FR-04-02's WebSocket is not in it), so the feed refreshes at E.9's degraded
 * cadence, "refreshing every 60 s", until one exists.
 */
export const FEED_REFRESH_MS = 60_000;

export type AlertQueue = NonNullable<NonNullable<ListAlertsData['query']>['queue']>;

/** A failed call, with the correlation ID the contract's Problem carries for support. */
export class ApiError extends Error {
  readonly correlationId: string | undefined;
  /** The Problem's HTTP status, when the server sent one (404: not in the caller's institution). */
  readonly status: number | undefined;
  /** The Problem's type URN, which says which rule refused the action (409, 403, 422). */
  readonly type: string | undefined;
  /** A stale-alert Problem's `current` alert (StaleAlertProblem), as the server now has it. */
  readonly current: unknown;

  constructor(error: unknown) {
    super('the API call failed');
    this.name = 'ApiError';
    const problem = typeof error === 'object' && error !== null ? error : {};
    const id: unknown = 'correlation_id' in problem ? problem.correlation_id : undefined;
    const status: unknown = 'status' in problem ? problem.status : undefined;
    const type: unknown = 'type' in problem ? problem.type : undefined;
    this.correlationId = typeof id === 'string' ? id : undefined;
    this.status = typeof status === 'number' ? status : undefined;
    this.type = typeof type === 'string' ? type : undefined;
    this.current = 'current' in problem ? problem.current : undefined;
  }
}

/** The body of a successful call, or an ApiError carrying the Problem's correlation ID. */
export function bodyOrThrow<T>(result: { data?: T; error?: unknown }): NonNullable<T> {
  if (result.error !== undefined || result.data === undefined) {
    throw new ApiError(result.error);
  }
  return result.data as NonNullable<T>;
}

/** The URL's filters as query parameters: a filter that is not set is not sent at all. */
function parameters(search: AlertSearch): NonNullable<ListAlertsData['query']> {
  return Object.fromEntries(Object.entries(search).filter(([, value]) => value !== undefined));
}

/**
 * A 2xx with no usable body (an empty 200, a proxy's page) reaches here as `{}` from the
 * generated client, and production does not run the schema check (ADR 0080 §3). These are the
 * fields the screens cannot do without; anything else is the contract tests' to hold.
 */
function isAlertPage(body: unknown): body is AlertPage {
  return typeof body === 'object' && body !== null && Array.isArray((body as AlertPage).items);
}

function isDashboardStats(body: unknown): body is DashboardStats {
  const stats = body as DashboardStats | null;
  return (
    typeof stats === 'object' &&
    stats !== null &&
    typeof stats.high_open === 'number' &&
    typeof stats.fraud_prevented_today === 'object'
  );
}

export async function fetchAlertPage(
  queue: AlertQueue,
  search: AlertSearch,
  cursor: string | undefined,
): Promise<AlertPage> {
  const page = bodyOrThrow(
    await listAlerts({
      query: { ...parameters(search), queue, ...(cursor === undefined ? {} : { cursor }) },
    }),
  );
  if (!isAlertPage(page)) throw new ApiError(undefined);
  return checked('zAlertPage', page);
}

export async function fetchDashboardStats(): Promise<DashboardStats> {
  const stats = bodyOrThrow(await getDashboardStats());
  if (!isDashboardStats(stats)) throw new ApiError(undefined);
  return checked('zDashboardStats', stats);
}

/**
 * One queue's alerts under the URL's filters, a page at a time (cursor paging). A new filter
 * starts from its own first page, so stale results are never shown under it. The 60 s refresh
 * re-reads every loaded page in turn, so an alert can move between pages: the feed shows it once.
 */
export function useAlertFeed(queue: AlertQueue, search: AlertSearch) {
  return useInfiniteQuery({
    queryKey: ['alerts', queue, search],
    queryFn: ({ pageParam }) => fetchAlertPage(queue, search, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
    refetchInterval: FEED_REFRESH_MS,
  });
}

/** The loaded pages' alerts in order, each alert once (the first time it appears). */
export function distinctAlerts(pages: readonly AlertPage[]): AlertPage['items'] {
  const seen = new Set<string>();
  return pages
    .flatMap((page) => page.items)
    .filter((alert) => {
      if (seen.has(alert.alert_id)) return false;
      seen.add(alert.alert_id);
      return true;
    });
}

export function useDashboardStats() {
  return useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: fetchDashboardStats,
    refetchInterval: FEED_REFRESH_MS,
  });
}
