import type { AlertQueue } from './queries';

/** The console paths that show an alert queue; each has the investigation drawer as its child. */
export type FeedPath = '/alerts' | '/anomalies';

/** How a queue is shown: listAlerts' queue, where it lives, and what of the feed applies to it. */
export interface QueueView {
  queue: AlertQueue;
  path: FeedPath;
  title: 'nav.alerts' | 'nav.anomalies';
  /** The tiers the queue holds that its filter offers; none when every alert has the same tier. */
  tiers: readonly ('HIGH' | 'MEDIUM')[];
  /**
   * Whether today's figures head the queue. They are the standard queue's header: their HIGH and
   * MEDIUM counts toggle that queue's tier filter.
   */
  stats: boolean;
}

/** The standard queue: HIGH and MEDIUM alerts, in the server's priority order (FR-04-02). */
export const STANDARD_VIEW: QueueView = {
  queue: 'standard',
  path: '/alerts',
  title: 'nav.alerts',
  tiers: ['HIGH', 'MEDIUM'],
  stats: true,
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
};
