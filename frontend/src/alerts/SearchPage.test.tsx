import {
  createMemoryHistory,
  createRouter,
  defaultParseSearch,
  defaultStringifySearch,
  RouterProvider,
} from '@tanstack/react-router';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type {
  AlertDecisionAccepted,
  AlertDetail,
  AlertPage,
  AlertSummary,
  ScoringResult,
} from '../api/generated/types.gen';
import { routeTree } from '../app/router';
import examples from '../mocks/generated/examples.json';
import { axeViolations } from '../test/axe';
import { Providers } from '../test/Providers';
import { alertSearch } from '../app/alertSearch';
import { judge, toInstant, toLocal } from './SearchForm';

// Fixtures start from the contract's examples, and every response is parsed by the generated
// schema in tests (H.4), so a fixture the contract would reject fails here.
function contractAlert(): AlertSummary {
  const [first] = (examples.AlertPage[0] as AlertPage).items;
  if (first === undefined) throw new Error('the contract has no AlertPage example with an alert');
  return first;
}

const result: AlertSummary = {
  ...contractAlert(),
  alert_id: '5b2c9d1e-7f3a-4b6c-9d0e-1f2a3b4c5d6e',
  masked_account_name: 'U*** N***',
};
const another: AlertSummary = {
  ...result,
  alert_id: '8e4f0a2b-1c3d-4e5f-a6b7-c8d9e0f1a2b3',
  masked_account_name: 'R*** T***',
};
const detail = examples.AlertDetail[0] as AlertDetail;
const scoring = examples.ScoringResult[0] as ScoringResult;

/** Answers listAlerts and the drawer's reads; records every listAlerts query and stats request. */
function serve(items: AlertSummary[] = [result, another]) {
  const asked = { lists: [] as URLSearchParams[], stats: 0, decisions: [] as string[] };
  vi.stubGlobal('fetch', (request: Request) => {
    const url = new URL(request.url);
    const json = (body: unknown) =>
      Promise.resolve(
        new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } }),
      );
    if (url.pathname.endsWith('/dashboard/stats')) {
      asked.stats += 1;
      return json(examples.DashboardStats[0]);
    }
    if (url.pathname.endsWith('/alerts')) {
      asked.lists.push(url.searchParams);
      return json({ items, next_cursor: null });
    }
    if (url.pathname.endsWith('/scoring-result')) return json(scoring);
    if (request.method === 'POST' && url.pathname.endsWith('/decisions')) {
      asked.decisions.push(url.pathname);
      const accepted = examples.AlertDecisionAccepted[0] as AlertDecisionAccepted;
      return Promise.resolve(
        new Response(
          JSON.stringify({ ...accepted, undo_until: new Date(Date.now() + 5_000).toISOString() }),
          { status: 202, headers: { 'Content-Type': 'application/json' } },
        ),
      );
    }
    const id = /\/alerts\/([^/]+)$/.exec(url.pathname)?.[1];
    if (id !== undefined) {
      const listed = items.find((item) => item.alert_id === id) ?? result;
      return json({ ...detail, ...listed, alert_id: id });
    }
    return Promise.reject(new Error(`unexpected request ${url.pathname}`));
  });
  return asked;
}

beforeEach(() => {
  // A wide screen: the drawer docks beside the queue.
  vi.stubGlobal('matchMedia', (query: string): Partial<MediaQueryList> => ({
    matches: query.includes('min-width'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function renderQueue(search: Record<string, unknown> = {}) {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({
      initialEntries: [`/search${defaultStringifySearch(search)}`],
    }),
  });
  const { container } = render(
    <Providers>
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Search' }, { timeout: 10_000 });
  return { router, container };
}

const TRANSACTION = '3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11';
const ACCOUNT = 'tok_exampleAccount0000000001';
const ANALYST = '4a1f0f3e-5c27-4a1e-9a56-0e0f0a1b2c3d';

describe('the search (/search, FR-04-09)', () => {
  it('[FR-04-09] asks nothing until there is a criterion', async () => {
    const asked = serve();
    const { container } = await renderQueue();
    expect(screen.getByRole('search', { name: 'Search alerts' })).toBeInTheDocument();
    expect(
      screen.getByText('Enter at least one criterion, or choose a filter, then search.'),
    ).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(asked.lists).toHaveLength(0);
    expect(asked.stats).toBe(0);
    expect(await axeViolations(container)).toEqual([]);
  });

  it("[FR-04-09] searches by transaction, account, amounts and dates in the region's zone", async () => {
    const asked = serve();
    const { router } = await renderQueue();
    const form = screen.getByRole('search', { name: 'Search alerts' });
    await userEvent.type(within(form).getByLabelText('Transaction ID'), TRANSACTION.toUpperCase());
    await userEvent.type(within(form).getByLabelText('Account token'), ACCOUNT);
    await userEvent.type(within(form).getByLabelText('Amount from'), '1000');
    await userEvent.type(within(form).getByLabelText('Amount up to'), '250000.50');
    // Country Z is UTC+7: 08:00 there is 01:00 UTC.
    fireEvent.change(within(form).getByLabelText('From'), {
      target: { value: '2026-10-19T08:00' },
    });
    fireEvent.change(within(form).getByLabelText('To'), { target: { value: '2026-10-19T20:30' } });
    await userEvent.click(within(form).getByRole('button', { name: 'Search' }));

    await screen.findByRole('article', { name: 'Alert for U*** N***' });
    const query = asked.lists[0];
    expect(query?.get('queue')).toBe('standard');
    expect(query?.get('transaction_id')).toBe(TRANSACTION);
    expect(query?.get('account_id')).toBe(ACCOUNT);
    expect(query?.get('amount_min')).toBe('1000');
    expect(query?.get('amount_max')).toBe('250000.50');
    expect(query?.get('from')).toBe('2026-10-19T01:00:00.000Z');
    expect(query?.get('to')).toBe('2026-10-19T13:30:00.000Z');
    expect(router.state.location.search).toMatchObject({
      transaction_id: TRANSACTION,
      account_id: ACCOUNT,
    });
  });

  it('marks what the contract would reject, and sends nothing', async () => {
    const asked = serve();
    await renderQueue();
    const form = screen.getByRole('search', { name: 'Search alerts' });
    await userEvent.type(within(form).getByLabelText('Transaction ID'), 'not-a-uuid');
    await userEvent.type(within(form).getByLabelText('Account token'), 'acct-12');
    await userEvent.type(within(form).getByLabelText('Amount from'), '500');
    await userEvent.type(within(form).getByLabelText('Amount up to'), '100');
    await userEvent.click(within(form).getByRole('button', { name: 'Search' }));
    expect(within(form).getByLabelText('Transaction ID')).toHaveAttribute('aria-invalid', 'true');
    expect(within(form).getByLabelText('Account token')).toHaveAttribute('aria-invalid', 'true');
    expect(within(form).getByLabelText('Amount up to')).toHaveAttribute('aria-invalid', 'true');
    expect(within(form).getByLabelText('Amount from')).toHaveAttribute('aria-invalid', 'false');
    expect(screen.getByText('An amount, at least the amount from.')).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(asked.lists).toHaveLength(0);
  });

  it('[PB-136] marks an ID the route would drop, rather than searching wider without it', async () => {
    const asked = serve();
    const { router } = await renderQueue({ channel: ['CARD'] });
    await screen.findByRole('article', { name: 'Alert for U*** N***' });
    const sent = asked.lists.length;
    const form = screen.getByRole('search', { name: 'Search alerts' });
    // Hex in the UUID's shape, but not a UUID (variant 1; version 0 in the second): the route's
    // validator drops both. The form once took them, and the search ran on the channel alone.
    await userEvent.type(
      within(form).getByLabelText('Transaction ID'),
      '12345678-1234-1234-1234-123456789abc',
    );
    await userEvent.type(
      within(form).getByLabelText('Analyst ID'),
      '4a1f0f3e-5c27-0a1e-9a56-0e0f0a1b2c3d',
    );
    await userEvent.click(within(form).getByRole('button', { name: 'Search' }));
    expect(within(form).getByLabelText('Transaction ID')).toHaveAttribute('aria-invalid', 'true');
    expect(within(form).getByLabelText('Analyst ID')).toHaveAttribute('aria-invalid', 'true');
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(asked.lists).toHaveLength(sent);
    expect(router.state.location.search).toEqual({ channel: ['CARD'] });
  });

  it('[PB-136] sends an analyst ID as typed, in any case, and the route keeps it', async () => {
    const asked = serve();
    const { router } = await renderQueue();
    const form = screen.getByRole('search', { name: 'Search alerts' });
    await userEvent.type(within(form).getByLabelText('Analyst ID'), ANALYST.toUpperCase());
    await userEvent.click(within(form).getByRole('button', { name: 'Search' }));
    await screen.findByRole('article', { name: 'Alert for U*** N***' });
    // The form is drawn afresh from the URL it wrote: the ID is there, lowered, and not marked.
    const refilled = screen.getByRole('search', { name: 'Search alerts' });
    expect(within(refilled).getByLabelText('Analyst ID')).toHaveValue(ANALYST);
    expect(within(refilled).getByLabelText('Analyst ID')).toHaveAttribute('aria-invalid', 'false');
    expect(asked.lists[0]?.get('analyst_id')).toBe(ANALYST);
    expect(router.state.location.search).toEqual({ analyst_id: ANALYST });
  });

  it('refills the form from a shared link, searches at once, and clears back to nothing', async () => {
    const asked = serve();
    const { router } = await renderQueue({ account_id: ACCOUNT, from: '2026-10-19T01:00:00.000Z' });
    const form = screen.getByRole('search', { name: 'Search alerts' });
    expect(within(form).getByLabelText('Account token')).toHaveValue(ACCOUNT);
    expect(within(form).getByLabelText('From')).toHaveValue('2026-10-19T08:00');
    await screen.findByRole('article', { name: 'Alert for U*** N***' });
    expect(asked.lists[0]?.get('account_id')).toBe(ACCOUNT);
    // The form shows the criteria, so they are not repeated as link-only chips.
    expect(screen.queryByRole('list', { name: 'Filters from the link' })).toBeNull();

    await userEvent.click(within(form).getByRole('button', { name: 'Clear search' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({});
    });
    expect(
      await screen.findByText('Enter at least one criterion, or choose a filter, then search.'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('search', { name: 'Search alerts' })).getByLabelText('Account token'),
    ).toHaveValue('');
  });

  it('opens a result in the drawer beside the results', async () => {
    serve();
    const { router } = await renderQueue({ channel: ['CARD'] });
    const card = await screen.findByRole('article', { name: 'Alert for U*** N***' });
    await userEvent.click(within(card).getByRole('link', { name: 'U*** N***' }));
    await screen.findByRole('region', { name: 'Investigation of U*** N***' });
    expect(router.state.location.pathname).toBe(`/search/${result.alert_id}`);
  });
});

describe("dates in the region's zone", () => {
  it('reads a local time as an instant and back, at a fractional offset too', () => {
    expect(toInstant('2026-10-19T08:00', 7)).toBe('2026-10-19T01:00:00.000Z');
    expect(toLocal('2026-10-19T01:00:00.000Z', 7)).toBe('2026-10-19T08:00');
    expect(toInstant('2026-10-19T08:00', 5.5)).toBe('2026-10-19T02:30:00.000Z');
    expect(toLocal(toInstant('2026-01-01T00:15', -3) ?? '', -3)).toBe('2026-01-01T00:15');
    expect(toInstant('19/10/2026 08:00', 7)).toBeUndefined();
  });
});

describe('[PB-136] the form and the route judge a criterion alike', () => {
  const empty = {
    transaction_id: '',
    account_id: '',
    amount_min: '',
    amount_max: '',
    from: '',
    to: '',
    analyst_id: '',
  };
  // Each is a value the form's own patterns once took or a near miss either side of the route's.
  const drafts = [
    { transaction_id: '3F8E0C5A-6B1D-4F5E-9A2B-7C4D1E8F0A11' },
    { transaction_id: '12345678-1234-1234-1234-123456789abc' },
    { transaction_id: ' 3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11 ' },
    { analyst_id: '00000000-0000-0000-0000-000000000000' },
    { analyst_id: 'ffffffff-ffff-ffff-ffff-ffffffffffff' },
    { account_id: 'tok_ABCDEFGHIJKLMNOPQRSTUVWX' },
    { account_id: 'tok_ABCDEFGHIJKLMNOPQRSTUVW' },
    { amount_min: '1000', amount_max: '1000.50' },
    { amount_min: '00012' },
    { amount_max: '99999999999999.9999' },
    { amount_max: '1e3' },
    { from: '2026-10-19T08:00', to: '2026-10-19T08:00' },
    { from: '19/10/2026 08:00' },
  ];
  it.each(drafts)('%o: either marked, or kept by the route exactly as sent', (fields) => {
    const { next, bad } = judge({ ...empty, ...fields }, {}, 7);
    const kept = alertSearch.parse(defaultParseSearch(defaultStringifySearch(next)));
    for (const field of Object.keys(fields) as (keyof typeof empty)[]) {
      if (bad[field] === true) continue;
      expect(kept[field]).toBe(next[field]);
      expect(next[field]).toBeDefined();
    }
  });
  it('sends a bound a link carried to the second as it came, unless it was edited', () => {
    const carried = '2026-10-19T01:00:30.250Z';
    const draft = { ...empty, from: toLocal(carried, 7) };
    expect(judge(draft, { from: carried }, 7).next.from).toBe(carried);
    expect(judge({ ...draft, from: '2026-10-19T09:00' }, { from: carried }, 7).next.from).toBe(
      '2026-10-19T02:00:00.000Z',
    );
  });
  it('marks the values the route would drop', () => {
    expect(
      judge({ ...empty, transaction_id: '12345678-1234-1234-1234-123456789abc' }, {}, 7).bad,
    ).toEqual({ transaction_id: true });
    expect(judge({ ...empty, account_id: 'tok_ABCDEFGHIJKLMNOPQRSTUVW' }, {}, 7).bad).toEqual({
      account_id: true,
    });
    expect(judge({ ...empty, amount_max: '1e3' }, {}, 7).bad).toEqual({ amount_max: true });
    expect(judge({ ...empty, from: '19/10/2026 08:00' }, {}, 7).bad).toEqual({ from: true });
  });
});
