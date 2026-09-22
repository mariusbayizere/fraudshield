import { client } from '../api/generated/client.gen';
import type { SystemStatus } from '../api/generated/types.gen';

// The client's base URL is relative ("/api/v1"), which a browser resolves and Node cannot.
beforeAll(() => {
  client.setConfig({ baseUrl: 'http://console.test/api/v1' });
});

import { fetchSystemConditions, SYSTEM_STATUS_POLL_MS } from './systemStatus';

const requested: string[] = [];

function respondWith(body: unknown, status = 200) {
  // The generated client calls fetch with a Request, not a URL.
  vi.stubGlobal('fetch', (request: Request) => {
    requested.push(request.url);
    return Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  requested.length = 0;
});

describe('the degraded modes the banners show (ADR 0081)', () => {
  it('reads the contract order, not the order the server sent', async () => {
    const body: SystemStatus = { degraded_modes: ['REALTIME_PAUSED', 'ML_UNAVAILABLE'] };
    respondWith(body);
    await expect(fetchSystemConditions()).resolves.toEqual(['ML_UNAVAILABLE', 'REALTIME_PAUSED']);
    expect(requested).toEqual(['http://console.test/api/v1/system/status']);
  });

  it('shows no banner when nothing is degraded', async () => {
    respondWith({ degraded_modes: [] });
    await expect(fetchSystemConditions()).resolves.toEqual([]);
  });

  it('rejects a response that breaks the contract, rather than showing it', async () => {
    respondWith({ degraded_modes: ['NOT_A_MODE'] });
    await expect(fetchSystemConditions()).rejects.toThrow();
  });

  it('ignores fields the contract does not define, rather than showing them', async () => {
    // The generated schema drops unknown keys instead of rejecting them: hey-api does not carry
    // additionalProperties: false into Zod. The console reads degraded_modes and nothing else.
    respondWith({ degraded_modes: ['DEGRADED_MODE'], kafka_consumer_lag: [{ topic: 'x' }] });
    await expect(fetchSystemConditions()).resolves.toEqual(['DEGRADED_MODE']);
  });

  it('reports a failed read instead of claiming the system is healthy', async () => {
    respondWith({ title: 'Forbidden' }, 403);
    await expect(fetchSystemConditions()).rejects.toThrow(/could not be read/);
  });

  it('polls on E.9 degraded cadence', () => {
    expect(SYSTEM_STATUS_POLL_MS).toBe(60_000);
  });
});
