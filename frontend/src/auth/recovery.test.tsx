import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { routeTree } from '../app/router';
import { Providers } from '../test/Providers';

const user = userEvent.setup({ delay: null });
const sent: { url: string; body: unknown }[] = [];

function server(answers: Record<string, { status: number; body?: unknown }>) {
  vi.stubGlobal('fetch', async (request: Request) => {
    const path = new URL(request.url).pathname.replace('/api/v1', '');
    const answer = answers[path] ?? { status: 404 };
    sent.push({
      url: path,
      body: request.method === 'POST' ? await request.clone().json() : undefined,
    });
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
}

async function open(path: string, heading: string) {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(
    <Providers session="anonymous">
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 10_000 });
  return router;
}

afterEach(() => {
  vi.unstubAllGlobals();
  sent.length = 0;
});

describe('forgetting a password (FR-07-06)', { timeout: 30_000 }, () => {
  it('answers the same way whether or not the email belongs to an account', async () => {
    server({ '/auth/password-reset/request': { status: 202 } });
    await open('/forgot-password', 'Forgot your password?');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.click(screen.getByRole('button', { name: 'Send the code' }));
    const answer = await screen.findByRole('alert');
    expect(answer).toHaveTextContent(
      'If that email belongs to an account, a six-digit code is on its way.',
    );
    // The answer says nothing about this address, so the page cannot be used to test addresses.
    expect(answer.textContent).not.toContain('someone@example.test');
    expect(sent[0]?.body).toEqual({ email: 'someone@example.test' });
  });

  it('will not send a malformed address', async () => {
    server({ '/auth/password-reset/request': { status: 202 } });
    await open('/forgot-password', 'Forgot your password?');
    await user.type(screen.getByLabelText(/Work email/), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Send the code' }));
    expect(await screen.findByText('Enter an email address like name@example.com.')).toBeVisible();
    expect(sent).toHaveLength(0);
  });

  it('says when the network has asked too often', async () => {
    server({ '/auth/password-reset/request': { status: 429, body: { title: 'Too many' } } });
    await open('/forgot-password', 'Forgot your password?');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.click(screen.getByRole('button', { name: 'Send the code' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests');
  });
});

describe('resetting a password (FR-07-06)', { timeout: 30_000 }, () => {
  const verified = {
    '/auth/password-reset/verify': {
      status: 200,
      body: { reset_token: 'a'.repeat(40), expires_at: '2026-09-22T12:00:00Z' },
    },
  };

  it('exchanges the code for a token, then sets the password and returns to sign in', async () => {
    server({ ...verified, '/auth/password-reset/complete': { status: 204 } });
    const router = await open('/reset-password', 'Set a new password');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.type(screen.getByLabelText(/Six-digit code/), '123456');
    await user.click(screen.getByRole('button', { name: 'Check the code' }));
    await user.type(await screen.findByLabelText(/New password/), 'Str0ng!passphrase');
    await user.type(screen.getByLabelText(/Confirm password/), 'Str0ng!passphrase');
    await user.click(screen.getByRole('button', { name: 'Set password' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(sent.map((call) => call.url)).toEqual([
      '/auth/password-reset/verify',
      '/auth/password-reset/complete',
    ]);
    expect(sent[1]?.body).toEqual({
      reset_token: 'a'.repeat(40),
      new_password: 'Str0ng!passphrase',
    });
  });

  it('keeps a six-digit code six digits, and refuses to check anything else', async () => {
    server(verified);
    await open('/reset-password', 'Set a new password');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.type(screen.getByLabelText(/Six-digit code/), '12ab34cd56789');
    expect(screen.getByLabelText(/Six-digit code/)).toHaveValue('123456');
    await user.clear(screen.getByLabelText(/Six-digit code/));
    await user.type(screen.getByLabelText(/Six-digit code/), '123');
    expect(screen.getByRole('button', { name: 'Check the code' })).toBeDisabled();
    await user.type(screen.getByLabelText(/Six-digit code/), '456');
    expect(screen.getByRole('button', { name: 'Check the code' })).toBeEnabled();
  });

  it('says when the code is wrong', async () => {
    server({ '/auth/password-reset/verify': { status: 400, body: { title: 'no' } } });
    await open('/reset-password', 'Set a new password');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.type(screen.getByLabelText(/Six-digit code/), '123456');
    await user.click(screen.getByRole('button', { name: 'Check the code' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That code is not right');
  });

  it('says when the reset token has expired, without losing the typed password', async () => {
    server({
      ...verified,
      '/auth/password-reset/complete': { status: 410, body: { title: 'gone' } },
    });
    await open('/reset-password', 'Set a new password');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.type(screen.getByLabelText(/Six-digit code/), '123456');
    await user.click(screen.getByRole('button', { name: 'Check the code' }));
    await user.type(await screen.findByLabelText(/New password/), 'Str0ng!passphrase');
    await user.type(screen.getByLabelText(/Confirm password/), 'Str0ng!passphrase');
    await user.click(screen.getByRole('button', { name: 'Set password' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That code has expired');
    expect(screen.getByLabelText(/New password/)).toHaveValue('Str0ng!passphrase');
  });

  it('will not set a password the policy refuses', async () => {
    server(verified);
    await open('/reset-password', 'Set a new password');
    await user.type(screen.getByLabelText(/Work email/), 'someone@example.test');
    await user.type(screen.getByLabelText(/Six-digit code/), '123456');
    await user.click(screen.getByRole('button', { name: 'Check the code' }));
    await user.type(await screen.findByLabelText(/New password/), 'password');
    await user.type(screen.getByLabelText(/Confirm password/), 'password');
    expect(screen.getByRole('button', { name: 'Set password' })).toBeDisabled();
  });
});

describe('unlocking an account (FR-07-05)', { timeout: 30_000 }, () => {
  const token = 'A'.repeat(30);

  it('uses the token from the link and says the account is unlocked', async () => {
    server({ '/auth/unlock': { status: 204 } });
    await open(`/unlock?token=${token}`, 'Unlock your account');
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account is unlocked');
    expect(sent[0]?.body).toEqual({ token });
  });

  it('says when the link has expired or has been used', async () => {
    server({ '/auth/unlock': { status: 410, body: { title: 'gone' } } });
    await open(`/unlock?token=${token}`, 'Unlock your account');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'has expired or has already been used',
    );
  });

  it('asks for the emailed link again when the token is missing or malformed', async () => {
    server({ '/auth/unlock': { status: 204 } });
    await open('/unlock?token=short', 'Unlock your account');
    expect(await screen.findByRole('alert')).toHaveTextContent('missing its unlock code');
    expect(sent).toHaveLength(0);
  });
});

describe('waiting for approval (D-23, D-24)', { timeout: 30_000 }, () => {
  it('says the account exists and nobody sees data until an administrator approves', async () => {
    server({});
    await open('/pending-approval', 'Waiting for approval');
    expect(
      screen.getByText(
        'Your account exists, and an administrator has to approve it before you can sign in.',
      ),
    ).toBeVisible();
    expect(screen.getByText(/Nobody can see fraud data until then/)).toBeVisible();
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toBeVisible();
  });
});
