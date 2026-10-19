import { screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type { AnalystPerformance } from '../api/generated/types.gen';
import examples from '../mocks/generated/examples.json';
import { axeViolations } from '../test/axe';
import { renderThemed } from '../test/render';
import MyPerformancePage from './MyPerformancePage';

const example = examples.AnalystPerformance[0] as AnalystPerformance;

function serve(body: unknown, status = 200) {
  const asked = { count: 0 };
  vi.stubGlobal('fetch', (request: Request) => {
    const path = new URL(request.url).pathname;
    if (!path.endsWith('/me/performance')) {
      return Promise.reject(new Error(`unexpected request ${path}`));
    }
    asked.count += 1;
    return Promise.resolve(
      new Response(
        JSON.stringify(
          status === 200
            ? body
            : {
                type: 'about:blank',
                title: 'Unexpected error',
                status,
                correlation_id: '9f2c4b1e-7d3a-4c5b-8e6f-0a1b2c3d4e5f',
              },
        ),
        { status, headers: { 'Content-Type': 'application/json' } },
      ),
    );
  });
  return asked;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

function figure(label: string): HTMLElement {
  const term = screen.getByText(label);
  const card = term.parentElement;
  if (card === null) throw new Error(`no card for ${label}`);
  return card;
}

describe('my performance (/me/performance, FR-04-11, UX-ROLE-AN-04)', () => {
  it('[FR-04-11, UX-ROLE-AN-04] shows reviews today, average review time, backlog and accuracy', async () => {
    serve({
      ...example,
      reviewed_today: 1234,
      average_review_seconds: 95.4,
      backlog: 7,
      accuracy_rate: 0.9125,
      verified_decisions: 80,
    });
    const { container } = renderThemed(<MyPerformancePage />);
    await screen.findByText('Alerts reviewed today');
    expect(figure('Alerts reviewed today')).toHaveTextContent('1,234');
    expect(figure('Average review time')).toHaveTextContent(/1 min\s+35 sec/);
    expect(figure('Alerts waiting for you')).toHaveTextContent('7');
    expect(figure('Accuracy')).toHaveTextContent('91.3%');
    expect(figure('Accuracy')).toHaveTextContent('From 80 verified decisions');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('says accuracy is not available yet, never 0%, until a decision is verified', async () => {
    serve({ ...example, accuracy_rate: null, verified_decisions: 0 });
    renderThemed(<MyPerformancePage />);
    const accuracy = await waitFor(() => figure('Accuracy'));
    expect(accuracy).toHaveTextContent('Not yet');
    expect(accuracy).toHaveTextContent(
      'Shown once customers have verified some of your decisions.',
    );
    expect(accuracy).not.toHaveTextContent('%');
  });

  it('writes a review time under a minute in seconds alone', async () => {
    serve({ ...example, average_review_seconds: 42 });
    renderThemed(<MyPerformancePage />);
    const time = await waitFor(() => figure('Average review time'));
    expect(time).toHaveTextContent('42 sec');
    expect(time).not.toHaveTextContent('min');
  });

  it('says when the figures cannot be read, with the reference, and retries', async () => {
    const asked = serve(null, 503);
    renderThemed(<MyPerformancePage />);
    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('Your figures could not be loaded.');
    expect(error).toHaveTextContent('9f2c4b1e-7d3a-4c5b-8e6f-0a1b2c3d4e5f');
    await userEvent.click(within(error).getByRole('button', { name: 'Try again' }));
    await waitFor(() => {
      expect(asked.count).toBe(2);
    });
  });
});
