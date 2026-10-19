import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type {
  AlertDecisionAccepted,
  AlertDetail,
  AlertPage,
  AlertSummary,
  DashboardStats,
  Role,
  ScoringResult,
  StaffUser,
} from '../../api/generated/types.gen';
import { routeTree } from '../../app/router';
import examples from '../../mocks/generated/examples.json';
import { axeViolations } from '../../test/axe';
import { Providers } from '../../test/Providers';

// Contract examples throughout; every response is parsed by the generated schema in tests (H.4).
const alert = examples.AlertDetail[0] as AlertDetail;
const page = examples.AlertPage[0] as AlertPage;
const stats = examples.DashboardStats[0] as DashboardStats;
const scoring = examples.ScoringResult[0] as ScoringResult;
const accepted = examples.AlertDecisionAccepted[0] as AlertDecisionAccepted;
const summary = examples.AlertSummary[0] as AlertSummary;
const analyst = examples.StaffUser[0] as StaffUser;
const CORRELATION = '9f2c4b1e-7d3a-4c5b-8e6f-0a1b2c3d4e5f';

interface Sent {
  path: string;
  body: unknown;
  key: string | null;
}

interface Script {
  /** What each POST answers, in order; the last one repeats. */
  answers?: (() => Promise<Response>)[];
  alertBody?: AlertDetail;
  /** The feed after a decision; by default the decided alert is gone. */
  /** The feed after a decision; by default the decided alert is gone. 'never' never answers. */
  pageAfter?: AlertPage | 'never';
}

const json = (body: unknown, status = 200) =>
  Promise.resolve(
    new Response(body === null ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
const problem = (type: string, status: number) =>
  json({ type, title: 'Refused', status, correlation_id: CORRELATION }, status);

/** The feed and drawer reads, and the panel's POSTs, which are recorded. */
function serve({
  answers = [
    () => json({ ...accepted, undo_until: new Date(Date.now() + 5_000).toISOString() }, 202),
  ],
  alertBody = alert,
  pageAfter = { items: [], next_cursor: null },
}: Script = {}) {
  const sent: Sent[] = [];
  let decided = false;
  vi.stubGlobal('fetch', async (request: Request) => {
    const path = new URL(request.url).pathname;
    if (request.method === 'POST') {
      const text = await request.text();
      sent.push({
        path,
        body: text === '' ? null : (JSON.parse(text) as unknown),
        key: request.headers.get('Idempotency-Key'),
      });
      const answer = answers[Math.min(sent.length - 1, answers.length - 1)];
      if (answer === undefined) throw new Error('no answer scripted');
      const response = await answer();
      if (response.ok) decided = true;
      return response;
    }
    if (path.endsWith('/dashboard/stats')) return json(stats);
    if (path.endsWith('/scoring-result')) return json(scoring);
    if (/\/alerts\/[^/]+$/.test(path)) return json(alertBody);
    if (path.endsWith('/alerts')) {
      if (!decided) return json(page);
      return pageAfter === 'never' ? new Promise<Response>(() => undefined) : json(pageAfter);
    }
    throw new Error(`unexpected ${request.method} ${path}`);
  });
  return sent;
}

function wide() {
  vi.stubGlobal('matchMedia', (query: string): Partial<MediaQueryList> => ({
    matches: query.includes('min-width'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

beforeEach(wide);
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function open(role: Role = 'ANALYST') {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/alerts/${alert.alert_id}`] }),
  });
  render(
    <Providers user={{ ...analyst, role }}>
      <RouterProvider router={router} />
    </Providers>,
  );
  const panel = await screen.findByRole('region', { name: 'Decide' }, { timeout: 10_000 });
  return { router, panel };
}

const COMMENT = 'Velocity spike after a SIM swap';

// Typing a real comment key by key, after the shell's first lazy load, outruns the default 5 s.
describe(
  'the decision panel (FR-04-04, UX-DASH-06, UX-ROLE-AN-03, D-44)',
  { timeout: 15_000 },
  () => {
    it('[FR-04-04, UX-DASH-06] keeps both decisions off until the comment has 10 characters', async () => {
      serve();
      const { panel } = await open();
      const confirm = within(panel).getByRole('button', { name: 'Confirm fraud' });
      const legitimate = within(panel).getByRole('button', { name: 'Mark legitimate' });
      expect(confirm).toBeDisabled();
      const comment = within(panel).getByRole('textbox', { name: 'Comment' });
      // Nine characters, padded with spaces, which do not count.
      await userEvent.type(comment, '   Umugore   ');
      expect(confirm).toBeDisabled();
      expect(within(panel).getByText('7 characters, 10 to 2000')).toBeInTheDocument();
      await userEvent.clear(comment);
      // Nine code points, two outside the basic plane (eleven UTF-16 units), then ten: the
      // contract counts code points.
      await userEvent.type(comment, 'Ikibazo😀🙂');
      expect(confirm).toBeDisabled();
      await userEvent.type(comment, 's');
      expect(confirm).toBeEnabled();
      expect(legitimate).toBeEnabled();
    });

    it('[FR-04-04, D-44] decides, closes the drawer, drops the alert and offers Undo', async () => {
      // The feed's refetch never answers, so the alert can only be gone because the decision
      // removed it at once (FR-04-04: success removes the alert from the feed).
      const sent = serve({ pageAfter: 'never' });
      const { router, panel } = await open();
      await userEvent.type(
        within(panel).getByRole('textbox', { name: 'Comment' }),
        `  ${COMMENT} `,
      );
      await userEvent.click(within(panel).getByRole('button', { name: 'Confirm fraud' }));

      await waitFor(() => {
        expect(router.state.location.pathname).toBe('/alerts');
      });
      expect(sent[0]).toEqual({
        path: `/api/v1/alerts/${alert.alert_id}/decisions`,
        body: { decision: 'CONFIRM_FRAUD', comment: COMMENT, alert_version: alert.version },
        key: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      });
      const notice = await screen.findByRole('alert');
      expect(notice).toHaveTextContent('Fraud confirmed for J*** M***.');
      expect(screen.queryByRole('article', { name: 'Alert for J*** M***' })).toBeNull();

      await userEvent.click(within(notice).getByRole('button', { name: 'Undo' }));
      await waitFor(() => {
        expect(sent[1]?.path).toBe(
          `/api/v1/alerts/${alert.alert_id}/decisions/${accepted.decision_id}/undo`,
        );
      });
      expect(
        await screen.findByText('Decision undone; the alert is back in the feed.'),
      ).toBeVisible();
    });

    it('says when it is too late to undo', async () => {
      serve({
        answers: [
          () => json({ ...accepted, undo_until: new Date(Date.now() + 5_000).toISOString() }, 202),
          () => problem('urn:fraudshield:problem:undo-window-expired', 409),
        ],
      });
      const { panel } = await open();
      await userEvent.type(within(panel).getByRole('textbox', { name: 'Comment' }), COMMENT);
      await userEvent.click(within(panel).getByRole('button', { name: 'Mark legitimate' }));
      await userEvent.click(
        within(await screen.findByRole('alert')).getByRole('button', { name: 'Undo' }),
      );
      expect(await screen.findByText('Too late to undo: the decision stands.')).toBeVisible();
    });

    it('offers Undo no longer than the grace, even if the server says longer', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      serve({
        answers: [
          () => json({ ...accepted, undo_until: new Date(Date.now() + 60_000).toISOString() }, 202),
        ],
      });
      const { panel } = await open();
      await userEvent.type(within(panel).getByRole('textbox', { name: 'Comment' }), COMMENT);
      await userEvent.click(within(panel).getByRole('button', { name: 'Confirm fraud' }));
      expect(await screen.findByRole('button', { name: 'Undo' })).toBeVisible();
      await vi.advanceTimersByTimeAsync(5_500);
      await waitFor(() => {
        expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
      });
    });

    it.each([
      ['urn:fraudshield:problem:stale-alert', 409, 'This alert changed while you were reading it.'],
      ['urn:fraudshield:problem:review-deadline-passed', 409, 'The review deadline passed'],
      ['urn:fraudshield:problem:escalation-level-required', 403, 'needs a higher role to decide'],
    ])('[D-29] explains a refusal (%s) and keeps the drawer open', async (type, status, text) => {
      serve({ answers: [() => problem(type, status)] });
      const { router, panel } = await open();
      await userEvent.type(within(panel).getByRole('textbox', { name: 'Comment' }), COMMENT);
      await userEvent.click(within(panel).getByRole('button', { name: 'Confirm fraud' }));
      expect(await within(panel).findByText(new RegExp(text))).toBeVisible();
      expect(router.state.location.pathname).toBe(`/alerts/${alert.alert_id}`);
    });

    it('[D-29] sends a refused action again under a new key, and an unanswered one under the same', async () => {
      const sent = serve({
        answers: [
          () => problem('urn:fraudshield:problem:stale-alert', 409),
          () => Promise.reject(new TypeError('network down')),
          () => json({ ...accepted, undo_until: new Date(Date.now() + 5_000).toISOString() }, 202),
        ],
      });
      const { panel } = await open();
      await userEvent.type(within(panel).getByRole('textbox', { name: 'Comment' }), COMMENT);
      const confirm = within(panel).getByRole('button', { name: 'Confirm fraud' });
      await userEvent.click(confirm);
      await within(panel).findByText(/This alert changed/);
      await userEvent.click(confirm);
      await within(panel).findByText('The action could not be sent. Try again.');
      await userEvent.click(confirm);
      await waitFor(() => {
        expect(sent).toHaveLength(3);
      });
      expect(sent[1]?.key).not.toBe(sent[0]?.key);
      expect(sent[2]?.key).toBe(sent[1]?.key);
    });

    it('[FR-04-10] escalates to a higher role with a reason', async () => {
      const sent = serve({ answers: [() => json({ ...summary, status: 'ESCALATED' }, 201)] });
      const { router, panel } = await open('ANALYST');
      const target = within(panel).getByRole('combobox', { name: 'Escalate to' });
      expect(
        within(target)
          .getAllByRole('option')
          .map((option) => option.textContent),
      ).toEqual(['Choose a role', 'Senior analyst', 'Risk officer']);
      const escalate = within(panel).getByRole('button', { name: 'Escalate' });
      await userEvent.selectOptions(target, 'SENIOR_ANALYST');
      expect(escalate).toBeDisabled();
      await userEvent.type(
        within(panel).getByRole('textbox', { name: 'Reason for escalating' }),
        'Linked to an active device campaign',
      );
      await userEvent.click(escalate);
      await waitFor(() => {
        expect(router.state.location.pathname).toBe('/alerts');
      });
      expect(sent[0]).toEqual({
        path: `/api/v1/alerts/${alert.alert_id}/escalations`,
        body: {
          target_role: 'SENIOR_ANALYST',
          reason: 'Linked to an active device campaign',
          alert_version: alert.version,
        },
        key: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      });
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'J*** M*** escalated to Senior analyst.',
      );
    });

    it.each([
      ['SENIOR_ANALYST', ['Choose a role', 'Risk officer']],
      ['RISK_OFFICER', null],
    ] as const)('offers a %s only the roles above it', async (role, options) => {
      serve();
      const { panel } = await open(role);
      const target = within(panel).queryByRole('combobox', { name: 'Escalate to' });
      if (options === null) {
        expect(target).toBeNull();
      } else {
        if (target === null) throw new Error('no escalation offered');
        expect(
          within(target)
            .getAllByRole('option')
            .map((option) => option.textContent),
        ).toEqual(options);
      }
    });

    it('offers no decision on an alert already decided', async () => {
      serve({ alertBody: { ...alert, status: 'CONFIRMED_FRAUD' } });
      const router = createRouter({
        routeTree,
        history: createMemoryHistory({ initialEntries: [`/alerts/${alert.alert_id}`] }),
      });
      render(
        <Providers user={analyst}>
          <RouterProvider router={router} />
        </Providers>,
      );
      expect(
        await screen.findByText(
          'This alert is Confirmed fraud; it can no longer be decided here.',
          {},
          { timeout: 10_000 },
        ),
      ).toBeVisible();
      expect(screen.queryByRole('button', { name: 'Confirm fraud' })).toBeNull();
    });

    it('[FR-04-12] has no axe violations', async () => {
      serve();
      await open();
      expect(await axeViolations(document.body)).toEqual([]);
    }, 15_000);
  },
);
