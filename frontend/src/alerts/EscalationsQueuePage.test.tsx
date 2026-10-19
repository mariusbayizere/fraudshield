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

// An escalation entry (FR-04-10): a new alert, ESCALATED, linked to the one it came from.
const escalation: AlertSummary = {
  ...contractAlert(),
  alert_id: '5b2c9d1e-7f3a-4b6c-9d0e-1f2a3b4c5d6e',
  tier: 'HIGH',
  status: 'ESCALATED',
  masked_account_name: 'U*** N***',
  review_deadline_at: null,
  escalated_from_alert_id: '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
};
const another: AlertSummary = {
  ...escalation,
  alert_id: '8e4f0a2b-1c3d-4e5f-a6b7-c8d9e0f1a2b3',
  masked_account_name: 'R*** T***',
};
const detail = examples.AlertDetail[0] as AlertDetail;
const scoring = examples.ScoringResult[0] as ScoringResult;

/** Answers listAlerts and the drawer's reads; records every listAlerts query and stats request. */
function serve(items: AlertSummary[] = [escalation, another]) {
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
      const listed = items.find((item) => item.alert_id === id) ?? escalation;
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
      initialEntries: [`/escalations${defaultStringifySearch(search)}`],
    }),
  });
  const { container } = render(
    <Providers>
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Escalations' }, { timeout: 10_000 });
  return { router, container };
}

describe('the escalations queue (/escalations, FR-04-10)', () => {
  it('[FR-04-10] lists the escalated queue and says what it holds, with the tier filter', async () => {
    const asked = serve();
    const { container } = await renderQueue();
    await screen.findByRole('article', { name: 'Alert for U*** N***' });
    expect(asked.lists[0]?.get('queue')).toBe('escalated');
    expect(
      screen.getByText(
        'Alerts escalated to your role, or to a role below it, and the ones you escalated.',
      ),
    ).toBeInTheDocument();
    expect(asked.stats).toBe(0);
    const filters = screen.getByRole('region', { name: 'Filters' });
    expect(within(filters).getByText('Risk tier')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('opens an escalation in the drawer beside the queue, and closes back to it', async () => {
    serve();
    const { router } = await renderQueue({ tier: ['HIGH'] });
    const card = await screen.findByRole('article', { name: 'Alert for U*** N***' });
    await userEvent.click(within(card).getByRole('link', { name: 'U*** N***' }));
    const drawer = await screen.findByRole('region', { name: 'Investigation of U*** N***' });
    expect(router.state.location.pathname).toBe(`/escalations/${escalation.alert_id}`);
    await userEvent.click(within(drawer).getByRole('button', { name: 'Close the investigation' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/escalations');
    });
    expect(router.state.location.search).toEqual({ tier: ['HIGH'] });
  });

  it('says when there are no escalations', async () => {
    serve([]);
    await renderQueue();
    expect(await screen.findByText('No escalations')).toBeInTheDocument();
  });
});
