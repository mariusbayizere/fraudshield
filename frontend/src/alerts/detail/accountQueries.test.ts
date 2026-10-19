import { client } from '../../api/generated/client.gen';
import examples from '../../mocks/generated/examples.json';
import { ApiError } from '../queries';
import { fetchBehaviour, fetchHistory, fetchNetwork } from './accountQueries';

// Production skips the schema check (ADR 0080 §3), so these tests turn it off too: what is left
// between a broken body and the tab is the shape check in accountQueries.ts.
vi.mock('../../api/validate', () => ({
  checked: <T>(_schema: string, data: T) => Promise.resolve(data),
}));

client.setConfig({ baseUrl: 'http://console.test/api/v1' });

const ACCOUNT = 'tok_exampleAccount0000000001';
const TRANSACTION = '3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11';

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

describe("reading the account's context without the schema check, as production does", () => {
  it('fails a history with no items', async () => {
    answer({});
    await expect(fetchHistory(ACCOUNT)).rejects.toBeInstanceOf(ApiError);
    answer(examples.AccountTransactionPage[0]);
    await expect(fetchHistory(ACCOUNT)).resolves.toEqual(examples.AccountTransactionPage[0]);
  });

  it('fails a profile without its four panels or with other than 24 hours', async () => {
    const profile = examples.BehaviourProfile[0];
    answer({});
    await expect(fetchBehaviour(ACCOUNT, TRANSACTION)).rejects.toBeInstanceOf(ApiError);
    for (const missing of ['amount_histogram', 'channel_mix', 'comparison']) {
      answer({ ...profile, [missing]: undefined });
      await expect(fetchBehaviour(ACCOUNT, TRANSACTION)).rejects.toBeInstanceOf(ApiError);
    }
    answer({ ...profile, hourly_counts: profile?.hourly_counts.slice(1) });
    await expect(fetchBehaviour(ACCOUNT, TRANSACTION)).rejects.toBeInstanceOf(ApiError);
    answer(profile);
    await expect(fetchBehaviour(ACCOUNT, TRANSACTION)).resolves.toEqual(profile);
  });

  it('fails a network without its nodes or its links', async () => {
    const network = examples.AccountNetwork[0];
    answer({ nodes: network?.nodes });
    await expect(fetchNetwork(ACCOUNT)).rejects.toBeInstanceOf(ApiError);
    answer({ edges: network?.edges });
    await expect(fetchNetwork(ACCOUNT)).rejects.toBeInstanceOf(ApiError);
    answer(network);
    await expect(fetchNetwork(ACCOUNT)).resolves.toEqual(network);
  });
});
