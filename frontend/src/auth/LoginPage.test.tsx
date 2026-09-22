import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { routeTree } from '../app/router';
import { Providers } from '../test/Providers';
import { getAccessToken, setAccessToken } from './tokens';

const session = {
  access_token: 'token-abc',
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

function answer(status: number, body: unknown = { title: 'no' }) {
  vi.stubGlobal('fetch', () =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
}

async function renderLogin(path = '/login') {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(
    <Providers session="anonymous">
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Sign in' }, { timeout: 10_000 });
  return router;
}

async function signIn() {
  await userEvent.type(screen.getByLabelText(/Work email/), 'analyst@example.test');
  await userEvent.type(screen.getByLabelText(/^Password/), 'Correct-horse-9!');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

describe('signing in (FR-07-03)', () => {
  it('keeps the access token in memory and goes where the analyst was heading', async () => {
    answer(200, session);
    const router = await renderLogin('/login?next=%2Fanomalies');
    await signIn();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/anomalies');
    });
    expect(getAccessToken()).toBe('token-abc');
    expect(localStorage.getItem('fs.language')).toBeNull();
    expect(JSON.stringify(localStorage)).not.toContain('token-abc');
  });

  it.each([
    [401, 'That email and password do not match an account.'],
    [
      423,
      'This account is locked after too many failed attempts. Use the unlock link sent to your email.',
    ],
    [429, 'Too many attempts from this network. Try again in a few minutes.'],
    [500, 'Sign-in is unavailable right now. Try again shortly.'],
  ])('says what happened on %d, and keeps no token', async (status, message) => {
    answer(status);
    await renderLogin();
    await signIn();
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(getAccessToken()).toBeNull();
  });

  it('sends an account that cannot sign in yet to the pending-approval page (D-23)', async () => {
    answer(403);
    const router = await renderLogin();
    await signIn();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/pending-approval');
    });
    expect(getAccessToken()).toBeNull();
  });

  it('ignores a next parameter that points off this console', async () => {
    answer(200, session);
    const router = await renderLogin('/login?next=https%3A%2F%2Felsewhere.test%2Fsteal');
    await signIn();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/alerts');
    });
  });

  it('shows and hides the password without losing it', async () => {
    answer(401);
    await renderLogin();
    const password = screen.getByLabelText(/^Password/);
    await userEvent.type(password, 'secret-value');
    expect(password).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(password).toHaveAttribute('type', 'text');
    expect(password).toHaveValue('secret-value');
    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(password).toHaveAttribute('type', 'password');
  });
});
