import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { routeTree } from '../app/router';
import { Providers } from '../test/Providers';
import { useSession } from './session';
import { getAccessToken, setAccessToken } from './tokens';

const session = {
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

function Probe() {
  const { status, user, signOut } = useSession();
  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="user">{user?.email ?? 'none'}</p>
      <button type="button" onClick={() => void signOut()}>
        Sign out
      </button>
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
});

describe('the shell behind the session', () => {
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
