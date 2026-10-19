import { http, HttpResponse } from 'msw';
import type {
  AlertDecisionAccepted,
  AlertDetail,
  AlertPage,
  AlertSummary,
  DashboardStats,
  Explanation,
  ScoringResult,
  StaffUser,
  SystemHealth,
  TokenResponse,
} from '../api/generated/types.gen';
import examples from './generated/examples.json';

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

/**
 * The analyst API's operations (M8B), answered with the contract's own examples, which the
 * contract tests validate against their schemas. They ignore the filters: a mock that filtered
 * would be guessing at the server's behaviour. The example page's cursor leads to an empty last
 * page, so "Load more" can be exercised.
 */
const alertPage = examples.AlertPage[0] as AlertPage;
const lastAlertPage: AlertPage = { items: [], next_cursor: null };
const dashboardStats = examples.DashboardStats[0] as DashboardStats;
const alertDetail = examples.AlertDetail[0] as AlertDetail;
// The contract's 44 contributions are a 44-element tuple type; JSON imports as an array. The
// example's shape is checked against the generated schema by contractExamples.test.ts.
const explanation = examples.Explanation[0] as unknown as Explanation;
const scoringResult = examples.ScoringResult[0] as ScoringResult;
const accepted = examples.AlertDecisionAccepted[0] as AlertDecisionAccepted;
const escalated = examples.AlertSummary[0] as AlertSummary;

/** Development-only responses, typed by the contract, for operations the console reads. */
export const handlers = [
  http.get('/api/v1/alerts', ({ request }) =>
    HttpResponse.json(new URL(request.url).searchParams.has('cursor') ? lastAlertPage : alertPage, {
      headers: { 'x-mock': MOCK_MARKER },
    }),
  ),
  // Any alert id opens the example alert; the drawer's data comes from the same examples.
  http.get('/api/v1/alerts/:alertId', ({ params }) =>
    HttpResponse.json(
      { ...alertDetail, alert_id: String(params['alertId']) },
      { headers: { 'x-mock': MOCK_MARKER } },
    ),
  ),
  // The example's undo_until is a fixed past instant; the mock gives D-44's 5 s from now, so the
  // Undo can be tried in development.
  http.post('/api/v1/alerts/:alertId/decisions', () =>
    HttpResponse.json(
      { ...accepted, undo_until: new Date(Date.now() + 5_000).toISOString() },
      { status: 202, headers: { 'x-mock': MOCK_MARKER } },
    ),
  ),
  http.post(
    '/api/v1/alerts/:alertId/decisions/:decisionId/undo',
    () => new HttpResponse(null, { status: 204, headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.post('/api/v1/alerts/:alertId/escalations', () =>
    HttpResponse.json(
      { ...escalated, alert_id: crypto.randomUUID(), status: 'ESCALATED' },
      { status: 201, headers: { 'x-mock': MOCK_MARKER } },
    ),
  ),
  http.get('/api/v1/alerts/:alertId/explanation', () =>
    HttpResponse.json(explanation, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.get('/api/v1/transactions/:transactionId/scoring-result', () =>
    HttpResponse.json(scoringResult, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.get('/api/v1/dashboard/stats', () =>
    HttpResponse.json(dashboardStats, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.post('/api/v1/auth/login', () =>
    HttpResponse.json(session, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.post('/api/v1/auth/refresh', () =>
    HttpResponse.json(session, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
  http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
  http.post('/api/v1/auth/register', () =>
    HttpResponse.json(
      { status: 'PENDING_APPROVAL', message_key: 'registration.pending' },
      { status: 202, headers: { 'x-mock': MOCK_MARKER } },
    ),
  ),
  http.post('/api/v1/auth/password-reset/request', () => new HttpResponse(null, { status: 202 })),
  http.post('/api/v1/auth/password-reset/verify', async ({ request }) => {
    const body = (await request.json()) as { code?: string };
    // Development only: 000000 is the code that works, anything else is refused.
    return body.code === '000000'
      ? HttpResponse.json({
          reset_token: 'mock-reset-token-abcdefghijklmnopqrstuvwxyz',
          expires_at: new Date(Date.now() + 900_000).toISOString(),
        })
      : HttpResponse.json({ title: 'Invalid code' }, { status: 400 });
  }),
  http.post('/api/v1/auth/password-reset/complete', () => new HttpResponse(null, { status: 204 })),
  http.post('/api/v1/auth/unlock', () => new HttpResponse(null, { status: 204 })),
  // Development only: one taken email and one taken employee ID, to exercise both answers.
  http.get('/api/v1/auth/availability', ({ request }) => {
    const value = new URL(request.url).searchParams.get('value') ?? '';
    return HttpResponse.json({ available: !['taken@example.test', 'EMP-TAKEN'].includes(value) });
  }),
  http.get('/api/v1/admin/health', () =>
    HttpResponse.json(health, { headers: { 'x-mock': MOCK_MARKER } }),
  ),
];
