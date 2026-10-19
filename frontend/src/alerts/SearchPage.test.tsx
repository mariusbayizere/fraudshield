import {
  createMemoryHistory,
  createRouter,
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
import { toInstant, toLocal } from './SearchForm';

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
