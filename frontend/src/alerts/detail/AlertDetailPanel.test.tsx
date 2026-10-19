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
function firstDecision() {
  const [first] = alert.decisions;
  if (first === undefined) throw new Error('the contract example has a decision');
  return first;
}
const decided = firstDecision();
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
  explanationStatus?: number;
  accountStatus?: number;
}

/** Answers the feed and the drawer's three operations; counts the drawer's requests. */
function serve({
  alertStatus = 200,
  alertBody = alert,
  scoringStatus = 200,
  scoringBody = scoring,
  explanationStatus = 200,
  accountStatus = 200,
}: Served = {}) {
  const asked = {
    alert: 0,
    explanation: 0,
    scoring: 0,
    history: [] as URL[],
    behaviour: [] as URL[],
    network: [] as URL[],
  };
  vi.stubGlobal('fetch', (request: Request) => {
    const url = new URL(request.url);
    const path = url.pathname;
    const json = (body: unknown, status = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(status === 200 ? body : { ...PROBLEM, status }), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    if (path.endsWith('/dashboard/stats')) return json(stats);
    if (path.endsWith('/transactions')) {
      asked.history.push(url);
      return json(examples.AccountTransactionPage[0], accountStatus);
    }
    if (path.endsWith('/behaviour-profile')) {
      asked.behaviour.push(url);
      return json(examples.BehaviourProfile[0], accountStatus);
    }
    if (path.endsWith('/network')) {
      asked.network.push(url);
      return json(examples.AccountNetwork[0], accountStatus);
    }
    if (path.endsWith('/explanation')) {
      asked.explanation += 1;
      return json(explanation, explanationStatus);
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
    // A wide screen meets every min-width query; a phone meets every max-width one.
    matches: query.includes('min-width') ? wide : !wide,
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

async function openAlert(
  search: Record<string, unknown> = {},
  queryClient?: QueryClient,
  path = `/alerts/${alert.alert_id}`,
) {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`${path}${defaultStringifySearch(search)}`] }),
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

  it('[UX-DASH-04] keeps the feed clear of the docked drawer, and gives the room back on close', async () => {
    serve();
    const router = await openAlert();
    await screen.findByRole('region', { name: drawerName });
    // jsdom has no layout, so the reserved room is checked as the style that makes it.
    expect(screen.getByTestId('alert-feed')).toHaveStyle({ marginInlineEnd: '560px' });
    await userEvent.click(screen.getByRole('button', { name: 'Close the investigation' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/alerts');
    });
    expect(screen.getByTestId('alert-feed')).not.toHaveStyle({ marginInlineEnd: '560px' });
  });

  it('returns focus to the card that opened it, or to the main region', async () => {
    serve();
    // With a filter set, the card's link carries it (?tier=...), as an analyst's usually does.
    const router = await openAlert({ tier: ['MEDIUM'] }, undefined, '/alerts');
    const link = await screen.findByRole('link', { name: 'J*** M***' });
    expect(link.getAttribute('href')).toContain('?');
    await userEvent.click(link);
    const drawer = await screen.findByRole('region', { name: drawerName });
    const heading = within(drawer).getByRole('heading', { level: 2, name: 'J*** M***' });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    await userEvent.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'J*** M***' })).toHaveFocus();
    });

    // The browser's Back closes it too, and focus comes back the same way.
    await userEvent.click(screen.getByRole('link', { name: 'J*** M***' }));
    await screen.findByRole('region', { name: drawerName });
    router.history.back();
    await waitFor(() => {
      expect(screen.queryByRole('region', { name: drawerName })).toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'J*** M***' })).toHaveFocus();
    });

    // Opened from a link to an alert the feed does not list: focus goes to the main region.
    document.body.innerHTML = '';
    await openAlert({}, undefined, '/alerts/7d1e2f3a-4b5c-4d6e-8f70-8192a3b4c5d6');
    await userEvent.click(await screen.findByRole('button', { name: 'Close the investigation' }));
    await waitFor(() => {
      expect(screen.getByRole('main')).toHaveFocus();
    });
  });

  it('shows who decided, whether it is final, and the anomaly tier without a risk badge', async () => {
    serve({
      alertBody: {
        ...alert,
        tier: 'ANOMALY',
        review_deadline_at: null,
        decisions: [
          { ...decided, state: 'UNDONE' },
          {
            ...decided,
            decision_id: '2c3d4e5f-6071-4b8c-9d0e-1f2a3b4c5d6e',
            decision: 'AUTO_RELEASED',
            analyst: null,
            comment: null,
            state: 'COMMITTED',
          },
        ],
      },
    });
    await openAlert();
    const drawer = await screen.findByRole('region', { name: drawerName });
    await within(drawer).findByText('93%');
    expect(within(drawer).getByText('Anomaly')).toBeInTheDocument();
    expect(within(drawer).queryByText('REVIEW')).toBeNull();
    const decisions = within(drawer).getByRole('region', { name: 'Decisions' });
    const [undone, released] = within(decisions).getAllByRole('listitem');
    if (undone === undefined || released === undefined) throw new Error('two decisions');
    expect(within(undone).getByText('Undone')).toBeInTheDocument();
    expect(within(released).getByText('Released automatically')).toBeInTheDocument();
    expect(within(released).getByText(/^System/)).toBeInTheDocument();
    // A final decision carries no state chip, and a missing comment no empty paragraph.
    expect(within(released).queryByText('Final')).toBeNull();
    expect(released.querySelectorAll('p')).toHaveLength(1);
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

  it('does not reopen on Back once closed', async () => {
    serve();
    const router = await openAlert({}, undefined, '/alerts');
    await userEvent.click(await screen.findByRole('link', { name: 'J*** M***' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Close the investigation' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/alerts');
    });
    router.history.back();
    // Closing replaced the drawer's entry, so Back does not land on it again.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(router.state.location.pathname).not.toBe(`/alerts/${alert.alert_id}`);
    expect(screen.queryByRole('region', { name: drawerName })).toBeNull();
  });

  it('[MOB-COMP-02] opens as a full-screen dialog below the large breakpoint', async () => {
    screenWidth(false);
    serve();
    await openAlert();
    const dialog = await screen.findByRole('dialog', { name: drawerName });
    expect(within(dialog).getByRole('heading', { level: 2, name: 'J*** M***' })).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
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

  // axe over the drawer and its table takes longer than the default 5 s on a CI runner.
  const AXE_MS = 15_000;

  it.each([
    ['wide', true],
    ['phone', false],
  ])(
    '[FR-04-12] has no axe violations in the summary on a %s screen',
    async (_name, wide) => {
      screenWidth(wide);
      serve();
      await openAlert();
      await screen.findByText('93%');
      expect(await axeViolations(document.body)).toEqual([]);
    },
    AXE_MS,
  );

  it.each([
    ['wide', true],
    ['phone', false],
  ])(
    '[FR-04-12] has no axe violations in the SHAP explanation on a %s screen',
    async (_name, wide) => {
      screenWidth(wide);
      serve();
      await openAlert();
      await userEvent.click(await screen.findByRole('tab', { name: 'SHAP explanation' }));
      await screen.findByRole('table', { name: "Each feature's effect on the score" });
      expect(await axeViolations(document.body)).toEqual([]);
    },
    AXE_MS,
  );
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

  it('says when the explanation cannot be read, and retries', async () => {
    const asked = serve({ explanationStatus: 503 });
    await openAlert();
    await userEvent.click(await screen.findByRole('tab', { name: 'SHAP explanation' }));
    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('The explanation could not be loaded.');
    await userEvent.click(within(error).getByRole('button', { name: 'Try again' }));
    await waitFor(() => {
      expect(asked.explanation).toBe(2);
    });
  });

  it('opens another alert on its summary, whichever tab was open', async () => {
    serve();
    const router = await openAlert();
    await openShap();
    await router.navigate({
      to: '/alerts/$alertId',
      params: { alertId: '7d1e2f3a-4b5c-4d6e-8f70-8192a3b4c5d6' },
    });
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Summary' })).toHaveAttribute('aria-selected', 'true');
    });
    expect(screen.getByRole('tab', { name: 'SHAP explanation' })).not.toHaveAttribute(
      'aria-controls',
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

describe("the account's context (FR-04-06, FR-04-07, FR-04-08)", () => {
  const TABS = [
    { name: 'Account history', asked: 'history', shows: 'list' },
    { name: 'Behaviour', asked: 'behaviour', shows: 'table' },
    { name: 'Network', asked: 'network', shows: 'group' },
  ] as const;

  it("reads each tab only when it is opened, for the alert's account", async () => {
    const asked = serve();
    await openAlert();
    // The tabs appear once the alert has loaded, which is when the account is known.
    await screen.findByRole('tab', { name: 'Account history' }, { timeout: 10_000 });
    for (const tab of TABS) {
      expect(asked[tab.asked]).toHaveLength(0);
    }
    for (const tab of TABS) {
      await userEvent.click(screen.getByRole('tab', { name: tab.name }));
      const panel = screen.getByRole('tabpanel', { name: tab.name });
      expect((await within(panel).findAllByRole(tab.shows)).length).toBeGreaterThan(0);
      expect(asked[tab.asked]).toHaveLength(1);
      expect(asked[tab.asked][0]?.pathname).toContain(`/accounts/${alert.account_token}/`);
    }
    // FR-04-06's 30, and the profile compares this alert's transaction.
    expect(asked.history[0]?.searchParams.get('limit')).toBe('30');
    expect(asked.behaviour[0]?.searchParams.get('transaction_id')).toBe(alert.transaction_id);
    // Three tabs opened in turn: more than the default 5 s on a loaded runner.
  }, 15_000);

  it.each(TABS)(
    'says when the $name tab cannot be read, with the reference, and retries',
    async (tab) => {
      const asked = serve({ accountStatus: 503 });
      await openAlert();
      await userEvent.click(await screen.findByRole('tab', { name: tab.name }));
      const error = await screen.findByRole('alert');
      expect(error).toHaveTextContent(/could not be loaded/);
      expect(error).toHaveTextContent(PROBLEM.correlation_id);
      await userEvent.click(within(error).getByRole('button', { name: 'Try again' }));
      await waitFor(() => {
        expect(asked[tab.asked]).toHaveLength(2);
      });
    },
  );
});
