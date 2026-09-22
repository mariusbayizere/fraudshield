import { http, HttpResponse } from 'msw';
import type {
  StaffUser,
  SystemHealth,
  SystemStatus,
  TokenResponse,
} from '../api/generated/types.gen';

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

const analyst: StaffUser = {
  user_id: '4a1f0f3e-5c27-4a1e-9a56-0e0f0a1b2c3d',
  first_name: 'Test',
  last_name: 'Analyst',
  email: 'analyst@example.test',
  role: 'ANALYST',
  status: 'ACTIVE',
  employee_id: 'EMP-0001',
  department: 'FRAUD_OPERATIONS',
  preferred_locale: 'en',
  created_at: '2026-01-05T08:00:00Z',
};

const session: TokenResponse = {
  access_token: 'mock-access-token',
  token_type: 'Bearer',
  expires_in: 900,
  user: analyst,
};

/** Contract-typed responses for services not merged yet (M5–M7), for development and tests. */
export const handlers = [
  http.post('/api/v1/auth/login', () =>
    HttpResponse.json(session, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.post('/api/v1/auth/refresh', () =>
    HttpResponse.json(session, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
  http.get('/api/v1/system/status', () =>
    HttpResponse.json(status, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.get('/api/v1/admin/health', () =>
    HttpResponse.json(health, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
];
