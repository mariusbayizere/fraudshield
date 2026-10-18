import {
  createMemoryHistory,
  createRouter,
  defaultStringifySearch,
  RouterProvider,
} from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type { AlertPage, AlertSummary, DashboardStats } from '../api/generated/types.gen';
import { routeTree } from '../app/router';
import examples from '../mocks/generated/examples.json';
import { axeViolations } from '../test/axe';
import { Providers } from '../test/Providers';

// Fixtures start from the contract's own examples; every page the screen reads is also parsed by
// the generated schema in tests (H.4), so a fixture the contract would reject fails the test.
function contractAlert(): AlertSummary {
  const [first] = (examples.AlertPage[0] as AlertPage).items;
  if (first === undefined) throw new Error('the contract has no AlertPage example with an alert');
  return first;
}
const example = contractAlert();
const stats = examples.DashboardStats[0] as DashboardStats;

function alert(overrides: Partial<AlertSummary>): AlertSummary {
  return { ...example, ...overrides };
}

const high = alert({
  alert_id: '6c1f8a2e-3b4d-4e5f-8a6b-7c8d9e0f1a2b',
  tier: 'HIGH',
  fraud_probability: 0.93,
  masked_account_name: 'A*** K***',
  merchant: 'Kiosk 12',
  channel: 'CARD',
  review_deadline_at: null,
});
const medium = alert({ review_deadline_at: new Date(Date.now() + 25_000).toISOString() });

interface Served {
  pages?: AlertPage[];
  status?: number;
}

/** Answers listAlerts and getDashboardStats; records every listAlerts query string. */
function serve({
  pages = [{ items: [high, medium], next_cursor: null }],
  status = 200,
}: Served = {}) {
  const asked: URLSearchParams[] = [];
  vi.stubGlobal('fetch', (request: Request) => {
    const url = new URL(request.url);
    const json = (body: unknown, code = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status: code,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    if (url.pathname.endsWith('/dashboard/stats')) return json(stats);
    if (url.pathname.endsWith('/alerts')) {
      asked.push(url.searchParams);
      if (status !== 200) {
        return json(
          {
            type: 'about:blank',
            title: 'Unexpected error',
            status,
            correlation_id: '9f2c4b1e-7d3a-4c5b-8e6f-0a1b2c3d4e5f',
          },
          status,
        );
      }
      const page = url.searchParams.has('cursor') ? pages[1] : pages[0];
      return json(page);
    }
    return Promise.reject(new Error(`unexpected request ${url.pathname}`));
  });
  return asked;
}

function screenWidth(wide: boolean) {
  vi.stubGlobal('matchMedia', (query: string): Partial<MediaQueryList> => ({
    matches: wide && query.includes('min-width'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

beforeEach(() => {
  screenWidth(true);
});

async function renderFeed(search: Record<string, unknown> = {}) {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/alerts${defaultStringifySearch(search)}`] }),
  });
  render(
    <Providers>
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Alerts' }, { timeout: 10_000 });
  return router;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the alert feed (FR-04-02, FR-04-03, FR-04-09)', () => {
  it("[FR-04-03, UX-DASH-07] shows every alert card's eight elements, and a MEDIUM alert's countdown", async () => {
    serve();
    await renderFeed();
    const card = await screen.findByRole('article', { name: 'Alert for A*** K***' });
    expect(within(card).getByText('HIGH RISK')).toBeInTheDocument();
    expect(within(card).getByRole('meter')).toBeInTheDocument();
    expect(within(card).getByText(/15,000/)).toBeInTheDocument();
    expect(within(card).getByText('Card')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'A*** K***' })).toHaveAttribute(
      'href',
      `/alerts/${high.alert_id}`,
    );
    expect(within(card).getByText('Kiosk 12')).toBeInTheDocument();
    expect(within(card).getByText(/UTC\+7/)).toBeInTheDocument();
    const reasons = within(card).getByRole('list', { name: 'Top reasons' });
    expect(within(reasons).getAllByRole('listitem')).toHaveLength(3);
    expect(
      within(reasons).getByLabelText('tx_count_60s increases the risk by +0.90'),
    ).toBeInTheDocument();

    const second = screen.getByRole('article', { name: 'Alert for J*** M***' });
    expect(within(second).getByText('REVIEW')).toBeInTheDocument();
    expect(within(second).getByText('No merchant')).toBeInTheDocument();
    expect(
      within(second).getByRole('timer', { name: /seconds? left to review/ }),
    ).toBeInTheDocument();
    expect(within(card).queryByRole('timer')).toBeNull();
    expect(screen.getByText('2 alerts shown')).toBeInTheDocument();
  });

  it("[UX-DASH-01] shows today's figures, and their tier counts filter the feed", async () => {
    const asked = serve();
    const router = await renderFeed();
    const figures = await screen.findByRole('region', { name: 'Today at a glance' });
    await within(figures).findByText('42');
    expect(within(figures).getByText(/8,450,000/)).toBeInTheDocument();
    expect(within(figures).getByText('19.5 s')).toBeInTheDocument();

    await userEvent.click(
      within(figures).getByRole('button', { name: 'Show only HIGH alerts (3 open)' }),
    );
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tier: ['HIGH'] });
    });
    await waitFor(() => {
      expect(asked.at(-1)?.get('tier')).toBe('HIGH');
    });
    expect(
      within(figures).getByRole('button', { name: 'Show only HIGH alerts (3 open)' }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('[FR-04-09] asks listAlerts for the standard queue with the filters in the URL', async () => {
    const asked = serve();
    await renderFeed({ tier: ['HIGH', 'MEDIUM'], channel: ['USSD', 'CARD'], sort: 'newest' });
    await screen.findByRole('article', { name: 'Alert for A*** K***' });
    const query = asked[0];
    expect(query?.get('queue')).toBe('standard');
    expect(query?.get('tier')).toBe('HIGH,MEDIUM');
    expect(query?.get('channel')).toBe('USSD,CARD');
    expect(query?.get('sort')).toBe('newest');
    expect(query?.has('cursor')).toBe(false);
  });

  it('[FR-04-09, UX-ROLE-AN-01] puts each filter in the URL, and clears them all at once', async () => {
    const asked = serve();
    const router = await renderFeed({ sort: 'expected_loss' });
    const filters = screen.getByRole('region', { name: 'Filters' });

    await userEvent.click(within(filters).getByRole('button', { name: 'Mobile money' }));
    await userEvent.click(within(filters).getByRole('button', { name: 'USSD' }));
    await userEvent.selectOptions(within(filters).getByLabelText('Status'), 'ESCALATED');
    await waitFor(() => {
      expect(router.state.location.search).toEqual({
        sort: 'expected_loss',
        channel: ['MOBILE_MONEY', 'USSD'],
        outcome: ['ESCALATED'],
      });
    });
    await waitFor(() => {
      expect(asked.at(-1)?.get('outcome')).toBe('ESCALATED');
    });

    await userEvent.click(within(filters).getByRole('button', { name: 'Mobile money' }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ channel: ['USSD'] });
    });
    await userEvent.click(within(filters).getByRole('button', { name: 'USSD' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({
        sort: 'expected_loss',
        outcome: ['ESCALATED'],
      });
    });

    await userEvent.click(within(filters).getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ sort: 'expected_loss' });
    });
  });

  it('says when nothing is open, and when the filters match nothing', async () => {
    serve({ pages: [{ items: [], next_cursor: null }] });
    await renderFeed();
    expect(await screen.findByRole('heading', { name: 'No open alerts' })).toBeInTheDocument();

    document.body.innerHTML = '';
    await renderFeed({ tier: ['HIGH'] });
    expect(
      await screen.findByRole('heading', { name: 'No alerts match these filters' }),
    ).toBeInTheDocument();
  });

  it('says plainly when the alerts cannot be read, with the reference, and retries', async () => {
    const asked = serve({ status: 503 });
    await renderFeed();
    expect(await screen.findByText('The alerts could not be loaded.')).toBeInTheDocument();
    expect(screen.getByText('9f2c4b1e-7d3a-4c5b-8e6f-0a1b2c3d4e5f')).toBeInTheDocument();
    const before = asked.length;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => {
      expect(asked.length).toBeGreaterThan(before);
    });
  });

  it('loads the next page with the cursor and adds it to the list', async () => {
    const asked = serve({
      pages: [
        { items: [high], next_cursor: 'eyJvZmZzZXQiOjUwfQ' },
        { items: [medium], next_cursor: null },
      ],
    });
    await renderFeed({ tier: ['MEDIUM'] });
    await screen.findByRole('article', { name: 'Alert for A*** K***' });
    expect(screen.queryByRole('article', { name: 'Alert for J*** M***' })).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await screen.findByRole('article', { name: 'Alert for J*** M***' });
    expect(asked.at(-1)?.get('cursor')).toBe('eyJvZmZzZXQiOjUwfQ');
    expect(asked.at(-1)?.get('tier')).toBe('MEDIUM');
    expect(screen.getByText('2 alerts shown')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('draws an Isolation-Forest-only alert without a risk colour or badge', async () => {
    serve({
      pages: [{ items: [alert({ tier: 'ANOMALY', review_deadline_at: null })], next_cursor: null }],
    });
    await renderFeed();
    const card = await screen.findByRole('article', { name: 'Alert for J*** M***' });
    expect(within(card).getByText('Anomaly')).toBeInTheDocument();
    expect(within(card).queryByText('REVIEW')).toBeNull();
    expect(within(card).queryByText('HIGH RISK')).toBeNull();
  });

  it('[FR-04-09] says so when a link filters by several statuses, which the select cannot show', async () => {
    serve();
    await renderFeed({ outcome: ['PENDING', 'ESCALATED'] });
    expect(await screen.findByRole('combobox', { name: 'Status' })).toHaveDisplayValue(
      '2 statuses',
    );
  });

  it('re-reads the feed every 60 s, as the contract has no real-time channel yet', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const asked = serve();
      await renderFeed();
      await screen.findByRole('article', { name: 'Alert for A*** K***' });
      const before = asked.length;
      await vi.advanceTimersByTimeAsync(59_000);
      expect(asked.length).toBe(before);
      await vi.advanceTimersByTimeAsync(2_000);
      await waitFor(() => {
        expect(asked.length).toBe(before + 1);
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it.each([
    ['wide', true],
    ['phone', false],
  ])('[FR-04-12] has no axe violations on a %s screen', async (_name, wide) => {
    screenWidth(wide);
    serve();
    await renderFeed();
    await screen.findByRole('article', { name: 'Alert for A*** K***' });
    expect(await axeViolations(document.body)).toEqual([]);
  });
});
