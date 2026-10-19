import type { AlertQueue } from './queries';

/** The console paths that show an alert queue; each has the investigation drawer as its child. */
export type FeedPath = '/alerts' | '/anomalies' | '/escalations' | '/search';

/** How a queue is shown: listAlerts' queue, where it lives, and what of the feed applies to it. */
export interface QueueView {
  queue: AlertQueue;
  path: FeedPath;
  title: 'nav.alerts' | 'nav.anomalies' | 'nav.escalations' | 'nav.search';
  /** The tiers the queue holds that its filter offers; none when every alert has the same tier. */
  tiers: readonly ('HIGH' | 'MEDIUM')[];
  /**
   * Whether today's figures head the queue. They are the standard queue's header: their HIGH and
   * MEDIUM counts toggle that queue's tier filter.
   */
  stats: boolean;
  /** The catalogue section with the queue's intro and empty state; none for the standard feed. */
  copy: 'anomalies' | 'escalations' | 'search' | null;
  /**
   * A search rather than a queue: the criteria form heads it, and nothing is asked of the server
   * until a criterion is set (FR-04-09).
   */
  criteria: boolean;
}

/** The standard queue: HIGH and MEDIUM alerts, in the server's priority order (FR-04-02). */
export const STANDARD_VIEW: QueueView = {
  queue: 'standard',
  path: '/alerts',
  title: 'nav.alerts',
  tiers: ['HIGH', 'MEDIUM'],
  stats: true,
  copy: null,
  criteria: false,
};

/**
 * D-10's anomaly queue: alerts raised by the Isolation Forest alone (the ensemble below 0.60).
 * They hold nothing and have no review timer, so they do not compete with the standard queue's
 * held transactions for an analyst's first minutes.
 */
export const ANOMALY_VIEW: QueueView = {
  queue: 'anomaly',
  path: '/anomalies',
  title: 'nav.anomalies',
  tiers: [],
  stats: false,
  copy: 'anomalies',
  criteria: false,
};

/**
 * Escalated alerts (FR-04-10): the entries escalated to the caller's role or below it, plus those
 * the caller escalated. An escalation keeps its tier, so the tier filter applies; the figures do not.
 */
export const ESCALATED_VIEW: QueueView = {
  queue: 'escalated',
  path: '/escalations',
  title: 'nav.escalations',
  tiers: ['HIGH', 'MEDIUM'],
  stats: false,
  copy: 'escalations',
  criteria: false,
};

/**
 * FR-04-09's search at /search: the feed's filters plus a transaction ID, an account token, an
 * amount range, dates and an analyst. listAlerts searches one queue a call and the standard queue by
 * default, so this searches the standard queue (M8B_updates.md, item 10).
 */
export const SEARCH_VIEW: QueueView = {
  queue: 'standard',
  path: '/search',
  title: 'nav.search',
  tiers: ['HIGH', 'MEDIUM'],
  stats: false,
  copy: 'search',
  criteria: true,
};
