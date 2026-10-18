import {
  createMemoryHistory,
  createRouter,
  defaultStringifySearch,
  RouterProvider,
} from '@tanstack/react-router';
import { QueryClient } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type {
  AlertDetail,
  AlertPage,
  DashboardStats,
  Explanation,
  ScoringResult,
} from '../../api/generated/types.gen';
import { routeTree } from '../../app/router';
import examples from '../../mocks/generated/examples.json';
import { axeViolations } from '../../test/axe';
import { Providers } from '../../test/Providers';

// Every fixture is a contract example; every response is also parsed by the generated schema in
// tests (H.4), so a fixture the contract would reject fails here.
const alert = examples.AlertDetail[0] as AlertDetail;
const page = examples.AlertPage[0] as AlertPage;
const stats = examples.DashboardStats[0] as DashboardStats;
const explanation = examples.Explanation[0] as unknown as Explanation;
const scoring = examples.ScoringResult[0] as ScoringResult;
const PROBLEM = {
  type: 'about:blank',
  title: 'Unexpected error',
  correlation_id: '9f2c4b1e-7d3a-4c5b-8e6f-0a1b2c3d4e5f',
};

interface Served {
  alertStatus?: number;
  alertBody?: AlertDetail;
  scoringStatus?: number;
  scoringBody?: ScoringResult;
}

/** Answers the feed and the drawer's three operations; counts the drawer's requests. */
function serve({
  alertStatus = 200,
  alertBody = alert,
  scoringStatus = 200,
  scoringBody = scoring,
}: Served = {}) {
  const asked = { alert: 0, explanation: 0, scoring: 0 };
  vi.stubGlobal('fetch', (request: Request) => {
    const path = new URL(request.url).pathname;
    const json = (body: unknown, status = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(status === 200 ? body : { ...PROBLEM, status }), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    if (path.endsWith('/dashboard/stats')) return json(stats);
    if (path.endsWith('/explanation')) {
      asked.explanation += 1;
      return json(explanation);
    }
    if (path.endsWith('/scoring-result')) {
      asked.scoring += 1;
      return json(scoringBody, scoringStatus);
    }
    if (/\/alerts\/[^/]+$/.test(path)) {
      asked.alert += 1;
      return json({ ...alertBody, alert_id: path.split('/').at(-1) }, alertStatus);
    }
    if (path.endsWith('/alerts')) return json(page);
    return Promise.reject(new Error(`unexpected request ${path}`));
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

afterEach(() => {
  vi.unstubAllGlobals();
});

async function openAlert(search: Record<string, unknown> = {}, queryClient?: QueryClient) {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({
      initialEntries: [`/alerts/${alert.alert_id}${defaultStringifySearch(search)}`],
    }),
  });
  render(
    <Providers {...(queryClient === undefined ? {} : { queryClient })}>
      <RouterProvider router={router} />
    </Providers>,
  );
  // A full-screen dialog hides the page behind it from the accessibility tree, hence hidden.
  await screen.findByRole(
    'heading',
    { level: 1, name: 'Alerts', hidden: true },
    { timeout: 10_000 },
  );
  return router;
}

const drawerName = 'Investigation of J*** M***';

describe('the investigation drawer (UX-DASH-04, UX-ROLE-AN-02, FR-04-05)', () => {
  it('[UX-DASH-04] opens over the feed, which stays usable, with focus on its heading', async () => {
    serve();
    await openAlert({ tier: ['MEDIUM'] });
    const drawer = await screen.findByRole('region', { name: drawerName });
    const heading = within(drawer).getByRole('heading', { level: 2, name: 'J*** M***' });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    // Not a modal on a large screen: the feed's cards are still in the accessibility tree.
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('article', { name: 'Alert for J*** M***' })).toBeVisible();
  });

  it("[UX-ROLE-AN-02] shows the transaction, the model's scores and every decision", async () => {
    serve();
    await openAlert();
    const drawer = await screen.findByRole('region', { name: drawerName });
    await within(drawer).findByText('93%');
    expect(within(drawer).getByText('REVIEW')).toBeInTheDocument();
    expect(within(drawer).getByText('Pending')).toBeInTheDocument();
    expect(within(drawer).getByText(/RWF\s?15,000/)).toBeInTheDocument();
    expect(within(drawer).getByText('Mobile money')).toBeInTheDocument();
    expect(within(drawer).getByText('No merchant')).toBeInTheDocument();
    const time = within(drawer).getByText('Time').closest('div') as HTMLElement;
    expect(within(time).getByText('Sep 17, 2026, 15:15 UTC+7')).toBeInTheDocument();
    expect(within(drawer).getByText('Anomaly score 42%')).toBeInTheDocument();
    expect(within(drawer).getByText('95%')).toBeInTheDocument();
    expect(within(drawer).getByText('91%')).toBeInTheDocument();
    expect(within(drawer).getByText('fs-ensemble-2026.09.1')).toBeInTheDocument();
    expect(within(drawer).getByText('12 ms')).toBeInTheDocument();
    const decisions = within(drawer).getByRole('region', { name: 'Decisions' });
    expect(within(decisions).getByText('Confirmed fraud')).toBeInTheDocument();
    expect(within(decisions).getByText(/Amani Uwase/)).toBeInTheDocument();
    expect(
      within(decisions).getByText('Velocity spike to a new counterparty after a SIM swap'),
    ).toBeInTheDocument();
    expect(within(drawer).queryByRole('alert')).toBeNull();
  });

  it('says when the model was unavailable and rules decided', async () => {
    serve({ scoringBody: { ...scoring, ml_unavailable_fallback: true } });
    await openAlert();
    expect(
      await screen.findByText('The model was unavailable: rules decided this transaction.'),
    ).toBeInTheDocument();
  });

  it("keeps the alert when the model's scores cannot be read", async () => {
    serve({ scoringStatus: 503 });
    await openAlert();
    expect(await screen.findByText("The model's scores could not be loaded.")).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Decisions' })).toBeInTheDocument();
  });

  it('closes back to the feed with its filters, by the button or Escape', async () => {
    serve();
    const router = await openAlert({ tier: ['MEDIUM'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Close the investigation' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/alerts');
    });
    expect(router.state.location.search).toEqual({ tier: ['MEDIUM'] });
    expect(screen.queryByRole('region', { name: drawerName })).toBeNull();

    await router.navigate({ to: '/alerts/$alertId', params: { alertId: alert.alert_id } });
    const heading = await screen.findByRole('heading', { level: 2, name: 'J*** M***' });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    await userEvent.keyboard('{Escape}');
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/alerts');
    });
  });

  it('[MOB-COMP-02] opens as a full-screen dialog below the large breakpoint', async () => {
    screenWidth(false);
    serve();
    await openAlert();
    const dialog = await screen.findByRole('dialog', { name: drawerName });
    expect(within(dialog).getByRole('heading', { level: 2, name: 'J*** M***' })).toBeVisible();
  });

  it('says plainly when the alert is not the analyst’s to see, and does not ask again', async () => {
    const asked = serve({ alertStatus: 404 });
    // A client that does retry, as production's does: a 404 must still be asked only once.
    const retrying = new QueryClient({
      defaultOptions: { queries: { retry: 2, retryDelay: 0 } },
    });
    await openAlert({}, retrying);
    expect(
      await screen.findByRole('heading', { name: 'This alert is not available' }),
    ).toBeInTheDocument();
    expect(asked.alert).toBe(1);
    expect(asked.scoring).toBe(0);
  });

  it('says plainly when the alert cannot be read, with the reference, and retries', async () => {
    const asked = serve({ alertStatus: 503 });
    await openAlert();
    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('The alert could not be loaded.');
    expect(within(error).getByText(PROBLEM.correlation_id)).toBeInTheDocument();
    await userEvent.click(within(error).getByRole('button', { name: 'Try again' }));
    await waitFor(() => {
      expect(asked.alert).toBe(2);
    });
  });

  it.each([
    ['wide', true],
    ['phone', false],
  ])('[FR-04-12] has no axe violations on a %s screen', async (_name, wide) => {
    screenWidth(wide);
    serve();
    await openAlert();
    await screen.findByText('93%');
    expect(await axeViolations(document.body)).toEqual([]);
  });
});

describe('the SHAP explanation (FR-04-05, UX-DASH-05, D-45)', () => {
  const sorted = [...explanation.contributions].sort((a, b) => Math.abs(b.shap) - Math.abs(a.shap));

  async function openShap() {
    await userEvent.click(await screen.findByRole('tab', { name: 'SHAP explanation' }));
    return screen.findByRole('table', { name: "Each feature's effect on the score" });
  }

  it('is read only when its tab is opened', async () => {
    const asked = serve();
    await openAlert();
    await screen.findByText('93%');
    expect(asked.explanation).toBe(0);
    await openShap();
    expect(asked.explanation).toBe(1);
  });

  it('[FR-04-05, UX-DASH-05, D-45] ranks by effect, shows the top 10 and nets the rest', async () => {
    serve();
    await openAlert();
    const table = await openShap();
    expect(
      screen.getByText(
        'From the base value -4.20 to +0.90 (log-odds); calibrated probability 71%.',
      ),
    ).toBeInTheDocument();
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(11);
    expect(
      rows.slice(0, 10).map((row) => within(row).getAllByRole('rowheader')[0]?.textContent),
    ).toEqual(sorted.slice(0, 10).map((c) => c.feature));
    expect(rows[10]).toHaveTextContent('34 other features, net');

    // Direction is carried by an arrow and a sign as well as the bar's colour.
    const up = rows.find((row) => row.dataset['direction'] === 'INCREASES_RISK');
    const down = rows.find((row) => row.dataset['direction'] === 'DECREASES_RISK');
    if (up === undefined || down === undefined) throw new Error('both directions are shown');
    expect(within(up).getByTestId('ArrowUpwardRoundedIcon')).toBeInTheDocument();
    expect(up).toHaveTextContent('+0.90');
    expect(within(down).getByTestId('ArrowDownwardRoundedIcon')).toBeInTheDocument();
    expect(down).toHaveTextContent('-0.82');
    expect(within(table).getAllByText('missing').length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole('button', { name: 'Show all 44 features' }));
    expect(within(table).getAllByRole('row').slice(1)).toHaveLength(44);
    expect(screen.getByRole('button', { name: 'Show the top 10 only' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('[MOB-COMP-08] shows the top 5 on a phone', async () => {
    screenWidth(false);
    serve();
    await openAlert();
    const table = await openShap();
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(6);
    expect(rows[5]).toHaveTextContent('39 other features, net');
  });
});
