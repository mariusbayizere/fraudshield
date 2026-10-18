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

  constructor(error: unknown) {
    super('the API call failed');
    this.name = 'ApiError';
    const id: unknown =
      typeof error === 'object' && error !== null && 'correlation_id' in error
        ? error.correlation_id
        : undefined;
    this.correlationId = typeof id === 'string' ? id : undefined;
  }
}

/** The body of a successful call, or an ApiError carrying the Problem's correlation ID. */
function bodyOrThrow<T>(result: { data?: T; error?: unknown }): NonNullable<T> {
  if (result.error !== undefined || result.data === undefined) {
    throw new ApiError(result.error);
  }
  return result.data as NonNullable<T>;
}

/** The URL's filters as query parameters: a filter that is not set is not sent at all. */
function parameters(search: AlertSearch): NonNullable<ListAlertsData['query']> {
  return Object.fromEntries(Object.entries(search).filter(([, value]) => value !== undefined));
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
  return checked('zAlertPage', page);
}

export async function fetchDashboardStats(): Promise<DashboardStats> {
  return checked('zDashboardStats', bodyOrThrow(await getDashboardStats()));
}

/** One queue's alerts under the URL's filters, a page at a time (cursor paging). */
export function useAlertFeed(queue: AlertQueue, search: AlertSearch) {
  return useInfiniteQuery({
    queryKey: ['alerts', queue, search],
    queryFn: ({ pageParam }) => fetchAlertPage(queue, search, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
    refetchInterval: FEED_REFRESH_MS,
  });
}

export function useDashboardStats() {
  return useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: fetchDashboardStats,
    refetchInterval: FEED_REFRESH_MS,
  });
}
