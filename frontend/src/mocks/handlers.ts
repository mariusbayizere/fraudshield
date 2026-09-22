import { http, HttpResponse } from 'msw';
import type { SystemHealth, SystemStatus } from '../api/generated/types.gen';

/**
 * Searched for by productionBundle.test.ts: if this string is ever in a production build, the
 * mocks shipped (ADR 0080 R1).
 */
export const MOCK_MARKER = 'fraudshield-mock-handlers-must-not-ship';

const health: SystemHealth = {
  kafka_consumer_lag: [],
  scoring_latency_ms: { p50: 11, p95: 21, p99: 34 },
  api_error_rate: 0.001,
  redis_hit_ratio: 0.97,
  db_pool_in_use: 0.2,
  auto_block_rate: 0.004,
  alert_queue_depth: { HIGH: 3, MEDIUM: 12, ANOMALY: 2 },
  review_duration_seconds: { p50: 42, p90: 95 },
  degraded_modes: [],
};

// Set VITE_FS_DEGRADED (e.g. "ML_UNAVAILABLE,REALTIME_PAUSED") to see the banners in development.
const degraded = (import.meta.env['VITE_FS_DEGRADED'] as string | undefined) ?? '';
const status: SystemStatus = {
  degraded_modes: degraded
    .split(',')
    .map((mode) => mode.trim())
    .filter((mode) => mode !== '') as SystemStatus['degraded_modes'],
};

/** Contract-typed responses for services not merged yet (M5–M7), for development and tests. */
export const handlers = [
  http.get('/api/v1/system/status', () =>
    HttpResponse.json(status, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.get('/api/v1/admin/health', () =>
    HttpResponse.json(health, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
];
