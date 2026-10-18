import { client } from '../api/generated/client.gen';
import { ApiError, fetchAlertPage, fetchDashboardStats } from './queries';

// Production skips the schema check (ADR 0080 §3), so these tests turn it off too: what is left
// between a broken body and the screen is the shape check in queries.ts.
vi.mock('../api/validate', () => ({
  checked: <T>(_schema: string, data: T) => Promise.resolve(data),
}));

client.setConfig({ baseUrl: 'http://console.test/api/v1' });

function answer(body: unknown) {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(
      new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } }),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('reading the feed without the schema check, as production does', () => {
  it('fails a page with no items instead of handing the screen an empty object', async () => {
    answer({});
    await expect(fetchAlertPage('standard', {}, undefined)).rejects.toBeInstanceOf(ApiError);
    answer({ items: 'none', next_cursor: null });
    await expect(fetchAlertPage('standard', {}, undefined)).rejects.toBeInstanceOf(ApiError);
    answer({ items: [], next_cursor: null });
    await expect(fetchAlertPage('standard', {}, undefined)).resolves.toEqual({
      items: [],
      next_cursor: null,
    });
  });

  it('fails figures without the counts or the amount the header reads', async () => {
    answer({});
    await expect(fetchDashboardStats()).rejects.toBeInstanceOf(ApiError);
    answer({ high_open: 3 });
    await expect(fetchDashboardStats()).rejects.toBeInstanceOf(ApiError);
  });
});
