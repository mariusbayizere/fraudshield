import { QueryClient } from '@tanstack/react-query';
import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type { AlertPage, AlertSummary, TokenResponse } from '../api/generated/types.gen';
import { routeTree } from '../app/router';
import examples from '../mocks/generated/examples.json';
import { Providers } from '../test/Providers';
import { useSession } from './session';
import { getAccessToken, setAccessToken } from './tokens';

const session: TokenResponse = {
  access_token: 'restored-token',
  token_type: 'Bearer',
  expires_in: 900,
  user: {
    user_id: '9f1b7a2e-58d2-4a70-8a31-0c2d3e4f5a6b',
    first_name: 'Test',
    last_name: 'Analyst',
    email: 'analyst@example.test',
    role: 'ANALYST',
    status: 'ACTIVE',
    employee_id: 'EMP-1',
    department: 'FRAUD_OPERATIONS',
    preferred_locale: 'en',
    created_at: '2026-01-05T08:00:00Z',
  },
};

const calls: { url: string; csrf: string | null }[] = [];

function answerRefresh(status: number, body: unknown = session) {
  vi.stubGlobal('fetch', (request: Request) => {
    calls.push({ url: request.url, csrf: request.headers.get('X-CSRF-Token') });
    return Promise.resolve(
      status === 204
        ? new Response(null, { status })
        : new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/json' },
          }),
    );
  });
}

function Probe({ next }: { next?: TokenResponse }) {
  const { status, user, signIn, signOut } = useSession();
  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="user">{user?.email ?? 'none'}</p>
      <button type="button" onClick={() => void signOut()}>
        Sign out
      </button>
      {next === undefined ? null : (
        <button
          type="button"
          onClick={() => {
            signIn(next);
          }}
        >
          Sign in as the next analyst
        </button>
      )}
    </div>
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
  calls.length = 0;
  document.cookie = 'fs_csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
});

describe('the session (D-27, ADR 0014)', () => {
  it('restores itself from the refresh cookie, sending the CSRF token with it', async () => {
    document.cookie = 'fs_csrf=csrf-value';
    answerRefresh(200);
    render(
      <Providers session="restoring">
        <Probe />
      </Providers>,
    );
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    expect(screen.getByTestId('user')).toHaveTextContent('analyst@example.test');
    expect(getAccessToken()).toBe('restored-token');
    expect(calls[0]?.url).toContain('/auth/refresh');
    expect(calls[0]?.csrf).toBe('csrf-value');
  });

  it('is anonymous when there is no usable refresh cookie', async () => {
    answerRefresh(401, { title: 'Unauthorized' });
    render(
      <Providers session="restoring">
        <Probe />
      </Providers>,
    );
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
    });
    expect(getAccessToken()).toBeNull();
  });

  it('forgets the token on sign-out even when the server call fails', async () => {
    document.cookie = 'fs_csrf=csrf-value';
    answerRefresh(200);
    render(
      <Providers session="restoring">
        <Probe />
      </Providers>,
    );
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    vi.stubGlobal('fetch', () => Promise.reject(new Error('offline')));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
    });
    expect(getAccessToken()).toBeNull();
  });

  // PB-140.
  it('forgets every query and mutation on sign-out, even when the server call fails', async () => {
    answerRefresh(200);
    const client = new QueryClient();
    render(
      <Providers session="restoring" queryClient={client}>
        <Probe />
      </Providers>,
    );
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    client.setQueryData(['alerts', 'standard'], { items: ['an alert'] });
    client.getMutationCache().build(client, { mutationKey: ['decide'] });
    vi.stubGlobal('fetch', () => Promise.reject(new Error('offline')));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('anonymous');
    });
    expect(client.getQueryCache().getAll()).toEqual([]);
    expect(client.getMutationCache().getAll()).toEqual([]);
  });

  // PB-140.
  it('forgets the queries when someone else signs in over a session never signed out', async () => {
    answerRefresh(200);
    const client = new QueryClient();
    const nextAnalyst: TokenResponse = {
      ...session,
      access_token: 'next-token',
      user: { ...session.user, user_id: '1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f' },
    };
    render(
      <Providers session="restoring" queryClient={client}>
        <Probe next={nextAnalyst} />
      </Providers>,
    );
    await waitFor(() => {
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated');
    });
    client.setQueryData(['alerts', 'standard'], { items: ['an alert'] });
    await userEvent.click(screen.getByRole('button', { name: 'Sign in as the next analyst' }));
    expect(client.getQueryCache().getAll()).toEqual([]);
  });
});

describe('the shell behind the session', () => {
  // PB-140.
  it('does not show the next analyst on the tab what the last one loaded', async () => {
    const [first] = (examples.AlertPage[0] as AlertPage).items;
    if (first === undefined) throw new Error('the contract has no AlertPage example with an alert');
    const alert: AlertSummary = { ...first, masked_account_name: 'U*** N***' };
    // The first analyst's session and feed; then the next analyst's feed never answers, so
    // anything on screen can only have come from what the first one left behind.
    let feed: () => Promise<Response> = () =>
      Promise.resolve(
        new Response(JSON.stringify({ items: [alert], next_cursor: null }), {
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    vi.stubGlobal('fetch', (request: Request) => {
      const url = new URL(request.url);
      if (url.pathname.endsWith('/auth/refresh')) {
        return Promise.resolve(
          new Response(JSON.stringify(session), {
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      if (url.pathname.endsWith('/auth/logout'))
        return Promise.resolve(new Response(null, { status: 204 }));
      if (url.pathname.endsWith('/alerts')) return feed();
      return new Promise(() => undefined);
    });
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/alerts'] }),
    });
    const nextAnalyst = {
      ...session,
      access_token: 'next-token',
      user: {
        ...session.user,
        user_id: '1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
        email: 'next@example.test',
      },
    };
    render(
      <Providers session="restoring">
        <Probe next={nextAnalyst} />
        <RouterProvider router={router} />
      </Providers>,
    );
    expect(
      await screen.findByRole('article', { name: 'Alert for U*** N***' }, { timeout: 10_000 }),
    ).toBeInTheDocument();

    feed = () => new Promise(() => undefined);
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    // Nor is the next analyst sent back to the last one's page after signing in.
    expect(router.state.location.search).toEqual({});
    await userEvent.click(screen.getByRole('button', { name: 'Sign in as the next analyst' }));
    await router.navigate({ to: '/alerts' });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Alerts' }, { timeout: 10_000 }),
    ).toBeVisible();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('article', { name: 'Alert for U*** N***' })).toBeNull();
  });

  it('shows a busy indicator, not the console, while the session is being restored', async () => {
    // A refresh that never answers: the guard must not show the console meanwhile.
    vi.stubGlobal('fetch', () => new Promise(() => undefined));
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/alerts'] }),
    });
    render(
      <Providers session="restoring">
        <RouterProvider router={router} />
      </Providers>,
    );
    expect(
      await screen.findByLabelText('Restoring your session', {}, { timeout: 10_000 }),
    ).toBeVisible();
    expect(screen.queryByRole('navigation', { name: 'Main navigation' })).toBeNull();
    expect(router.state.location.pathname).toBe('/alerts');
  });

  // PB-140.
  it('opens sign-in without the page a signed-out analyst was on, criteria and all', async () => {
    answerRefresh(200);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({
        initialEntries: ['/search?account_id=%22tok_exampleAccount0000000001%22'],
      }),
    });
    render(
      <Providers session="restoring">
        <Probe />
        <RouterProvider router={router} />
      </Providers>,
    );
    await screen.findByRole('heading', { level: 1, name: 'Search' }, { timeout: 10_000 });
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(router.state.location.search).toEqual({});
    expect(router.state.location.href).not.toContain('tok_');
  });

  it('sends an anonymous visitor to sign in, remembering the page asked for', async () => {
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/anomalies'] }),
    });
    render(
      <Providers session="anonymous">
        <RouterProvider router={router} />
      </Providers>,
    );
    await waitFor(
      () => {
        expect(router.state.location.pathname).toBe('/login');
      },
      { timeout: 10_000 },
    );
    expect(router.state.location.search).toEqual({ next: '/anomalies' });
    // The console itself never rendered: the guard shows nothing behind it.
    expect(screen.queryByRole('navigation', { name: 'Main navigation' })).toBeNull();
    expect(screen.queryByRole('heading', { level: 1, name: 'Anomalies' })).toBeNull();
  });

  it('shows the console to a signed-in analyst', async () => {
    answerRefresh(200);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/alerts'] }),
    });
    render(
      <Providers session="restoring">
        <RouterProvider router={router} />
      </Providers>,
    );
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Alerts' }, { timeout: 10_000 }),
    ).toBeVisible();
  });
});
