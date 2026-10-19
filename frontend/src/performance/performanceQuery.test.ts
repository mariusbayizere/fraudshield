import { client } from '../api/generated/client.gen';
import examples from '../mocks/generated/examples.json';
import { ApiError } from '../alerts/queries';
import { fetchMyPerformance } from './MyPerformancePage';

// Production skips the schema check (ADR 0080 §3), so this test turns it off too: what is left
// between a broken body and the page is the shape check in MyPerformancePage.tsx.
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

describe('reading the figures without the schema check, as production does', () => {
  it('fails a body without the figures instead of showing zeros', async () => {
    const example = examples.AnalystPerformance[0];
    answer({});
    await expect(fetchMyPerformance()).rejects.toBeInstanceOf(ApiError);
    answer({ ...example, accuracy_rate: 'high' });
    await expect(fetchMyPerformance()).rejects.toBeInstanceOf(ApiError);
    answer({ ...example, backlog: undefined });
    await expect(fetchMyPerformance()).rejects.toBeInstanceOf(ApiError);
    answer(example);
    await expect(fetchMyPerformance()).resolves.toEqual(example);
  });
});
