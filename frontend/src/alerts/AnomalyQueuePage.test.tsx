import {
  createMemoryHistory,
  createRouter,
  defaultStringifySearch,
  RouterProvider,
} from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
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

// Fixtures start from the contract's examples, and every response is parsed by the generated
// schema in tests (H.4), so a fixture the contract would reject fails here.
function contractAlert(): AlertSummary {
  const [first] = (examples.AlertPage[0] as AlertPage).items;
  if (first === undefined) throw new Error('the contract has no AlertPage example with an alert');
  return first;
}

// An Isolation-Forest-only alert (D-10): ANOMALY, the ensemble below 0.60, no deadline. A deadline
// is set on the second to show the queue never counts down, whatever the server sends.
const anomaly: AlertSummary = {
  ...contractAlert(),
  alert_id: '5b2c9d1e-7f3a-4b6c-9d0e-1f2a3b4c5d6e',
  tier: 'ANOMALY',
  fraud_probability: 0.22,
  masked_account_name: 'U*** N***',
  review_deadline_at: null,
};
const another: AlertSummary = {
  ...anomaly,
  alert_id: '8e4f0a2b-1c3d-4e5f-a6b7-c8d9e0f1a2b3',
  masked_account_name: 'R*** T***',
  review_deadline_at: new Date(Date.now() + 25_000).toISOString(),
};
const detail = examples.AlertDetail[0] as AlertDetail;
const scoring = examples.ScoringResult[0] as ScoringResult;

/** Answers listAlerts and the drawer's reads; records every listAlerts query and stats request. */
function serve(items: AlertSummary[] = [anomaly, another]) {
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
      const listed = items.find((item) => item.alert_id === id) ?? anomaly;
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
      initialEntries: [`/anomalies${defaultStringifySearch(search)}`],
    }),
  });
  const { container } = render(
    <Providers>
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Anomalies' }, { timeout: 10_000 });
  return { router, container };
}

describe("D-10's anomaly queue (/anomalies)", () => {
  it('[D-10] lists the anomaly queue, says what it holds, and never counts down', async () => {
    const asked = serve();
    const { container } = await renderQueue();
    const card = await screen.findByRole('article', { name: 'Alert for U*** N***' });
    expect(asked.lists[0]?.get('queue')).toBe('anomaly');
    expect(
      screen.getByText(
        /raised by the anomaly detector alone\. They are not held and have no review timer\./,
      ),
    ).toBeInTheDocument();
    expect(within(card).getByText('Anomaly')).toBeInTheDocument();
    // Neither card counts down, though the server sent the second a deadline.
    await screen.findByRole('article', { name: 'Alert for R*** T***' });
    expect(screen.queryByRole('timer')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('shows no figures and no tier filter: the figures count the standard queue, and every alert here is ANOMALY', async () => {
    const asked = serve();
    await renderQueue();
    await screen.findByRole('article', { name: 'Alert for U*** N***' });
    expect(asked.stats).toBe(0);
    const filters = screen.getByRole('region', { name: 'Filters' });
    expect(within(filters).queryByText('Risk tier')).toBeNull();
    expect(within(filters).getByText('Channel')).toBeInTheDocument();
  });

  it('keeps a tier a link carried, as a filter that can be removed', async () => {
    const asked = serve();
    await renderQueue({ tier: ['HIGH'] });
    const fromLink = await screen.findByRole('list', { name: 'Filters from the link' });
    expect(within(fromLink).getByText('Risk tier: HIGH')).toBeInTheDocument();
    expect(asked.lists[0]?.getAll('tier')).toEqual(['HIGH']);
  });

  it('opens an alert in the drawer beside the queue, and closes back to it with its filters', async () => {
    serve();
    const { router } = await renderQueue({ channel: ['MOBILE_MONEY'] });
    const card = await screen.findByRole('article', { name: 'Alert for U*** N***' });
    await userEvent.click(within(card).getByRole('link', { name: 'U*** N***' }));
    const drawer = await screen.findByRole('region', { name: 'Investigation of U*** N***' });
    expect(router.state.location.pathname).toBe(`/anomalies/${anomaly.alert_id}`);
    // The queue stays on screen behind the drawer.
    expect(screen.getByRole('heading', { level: 1, name: 'Anomalies' })).toBeVisible();

    await userEvent.click(within(drawer).getByRole('button', { name: 'Close the investigation' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/anomalies');
    });
    expect(router.state.location.search).toEqual({ channel: ['MOBILE_MONEY'] });
    // Focus goes back to the card's link it was opened from.
    await waitFor(() => {
      expect(within(card).getByRole('link', { name: 'U*** N***' })).toHaveFocus();
    });
  });

  it('[FR-04-04, D-10] decides from the drawer: back to the queue, the alert gone, Undo offered', async () => {
    const asked = serve();
    const { router } = await renderQueue();
    const card = await screen.findByRole('article', { name: 'Alert for U*** N***' });
    await userEvent.click(within(card).getByRole('link', { name: 'U*** N***' }));
    const drawer = await screen.findByRole('region', { name: 'Investigation of U*** N***' });
    await userEvent.type(
      within(drawer).getByRole('textbox', { name: 'Comment' }),
      'Matches a known mule pattern',
    );
    await userEvent.click(within(drawer).getByRole('button', { name: 'Mark legitimate' }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/anomalies');
    });
    expect(asked.decisions).toEqual([`/api/v1/alerts/${anomaly.alert_id}/decisions`]);
    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('U*** N*** marked legitimate.');
    expect(within(notice).getByRole('button', { name: 'Undo' })).toBeVisible();
    expect(screen.queryByRole('article', { name: 'Alert for U*** N***' })).toBeNull();
    expect(screen.getByRole('article', { name: 'Alert for R*** T***' })).toBeInTheDocument();
  });

  it('closes on Escape, and Back does not reopen it', async () => {
    serve();
    const { router } = await renderQueue();
    const card = await screen.findByRole('article', { name: 'Alert for U*** N***' });
    await userEvent.click(within(card).getByRole('link', { name: 'U*** N***' }));
    await screen.findByRole('region', { name: 'Investigation of U*** N***' });
    await userEvent.keyboard('{Escape}');
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/anomalies');
    });
    router.history.back();
    // Closing replaced the drawer's entry, so Back does not land on it again.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(router.state.location.pathname).not.toBe(`/anomalies/${anomaly.alert_id}`);
    expect(screen.queryByRole('region', { name: 'Investigation of U*** N***' })).toBeNull();
  });

  it('says when there are no anomalies', async () => {
    serve([]);
    await renderQueue();
    expect(await screen.findByText('No anomalies to review')).toBeInTheDocument();
    expect(screen.queryByText('No alerts match these filters')).toBeNull();
  });

  it('says when no anomaly matches the filters', async () => {
    serve([]);
    await renderQueue({ channel: ['USSD'] });
    expect(await screen.findByText('No alerts match these filters')).toBeInTheDocument();
    expect(screen.queryByText('No anomalies to review')).toBeNull();
  });

  it('leaves the standard feed as it was: its own queue and figures', async () => {
    const asked = serve([]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/alerts'] }),
    });
    render(
      <Providers>
        <RouterProvider router={router} />
      </Providers>,
    );
    await screen.findByRole('heading', { level: 1, name: 'Alerts' }, { timeout: 10_000 });
    await waitFor(() => {
      expect(asked.lists[0]?.get('queue')).toBe('standard');
    });
    await waitFor(() => {
      expect(asked.stats).toBeGreaterThan(0);
    });
    expect(screen.getByText('Risk tier')).toBeInTheDocument();
  });
});
