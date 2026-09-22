import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';

// Typing whole forms character by character with the default delay outruns the test timeout.
const user = userEvent.setup({ delay: null });
import examples from 'libphonenumber-js/examples.mobile.json';
import { getCountries, getExampleNumber } from 'libphonenumber-js/min';
import { routeTree } from '../../app/router';
import { Providers } from '../../test/Providers';

const sent: { url: string; body: unknown }[] = [];

// A dialling country and a number valid for it, taken from the phone metadata rather than named
// here: no country belongs in this source (ADR 0023). Country Z, the fixture region, has none.
const DIAL_COUNTRY = getCountries()[0] ?? 'GB';
const EXAMPLE = getExampleNumber(DIAL_COUNTRY, examples);
const EXAMPLE_NATIONAL: string = EXAMPLE?.nationalNumber ?? '';
const EXAMPLE_E164 = EXAMPLE?.number ?? '';

function server(registerStatus = 202) {
  vi.stubGlobal('fetch', async (request: Request) => {
    const url = request.url;
    if (url.includes('/auth/availability')) {
      const value = new URL(url).searchParams.get('value') ?? '';
      return new Response(JSON.stringify({ available: value !== 'taken@example.test' }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }
    sent.push({ url, body: await request.clone().json() });
    return new Response(
      JSON.stringify(
        registerStatus === 202
          ? { status: 'PENDING_APPROVAL', message_key: 'registration.pending' }
          : { title: 'Invalid' },
      ),
      { status: registerStatus, headers: { 'Content-Type': 'application/json' } },
    );
  });
}

async function renderRegister() {
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/register'] }),
  });
  render(
    <Providers session="anonymous">
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('heading', { level: 1, name: 'Create an account' }, { timeout: 10_000 });
  return router;
}

async function fillValidForm(email = 'new.analyst@example.test') {
  await user.selectOptions(screen.getByLabelText(/Country/), DIAL_COUNTRY);
  await user.type(screen.getByLabelText(/First name/), 'Aline');
  await user.type(screen.getByLabelText(/Last name/), 'Uwase');
  await user.type(screen.getByLabelText(/Work email/), email);
  await user.type(screen.getByLabelText(/Phone number/), EXAMPLE_NATIONAL);
  await user.type(screen.getByLabelText(/Employee ID/), 'EMP0042');
  await user.type(screen.getByLabelText(/^Password/), 'Str0ng!passphrase');
  await user.type(screen.getByLabelText(/Confirm password/), 'Str0ng!passphrase');
}

afterEach(() => {
  vi.unstubAllGlobals();
  sent.length = 0;
});

// The page is a lazy route and the form is large; these tests fill it field by field.
describe('registration (SRS 5.3, D-24)', { timeout: 30_000 }, () => {
  it('sends the contract shape, with the phone in E.164, and goes to pending approval', async () => {
    server();
    const router = await renderRegister();
    await fillValidForm();
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/pending-approval');
    });
    expect(sent).toHaveLength(1);
    expect(sent[0]?.body).toEqual({
      first_name: 'Aline',
      last_name: 'Uwase',
      email: 'new.analyst@example.test',
      // Typed as a local number, sent in E.164 for the selected country.
      phone: EXAMPLE_E164,
      employee_id: 'EMP0042',
      department: 'FRAUD_OPERATIONS',
      requested_role: 'ANALYST',
      password: 'Str0ng!passphrase',
      preferred_locale: 'en',
    });
  });

  it('asks for a dialling country when the deployment has none (ADR 0023)', async () => {
    server();
    await renderRegister();
    // The fixture region is Country Z, which the phone metadata does not know.
    expect(screen.getByLabelText(/Country/)).toHaveValue('');
    await user.type(screen.getByLabelText(/Phone number/), '123456789');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Choose the phone number’s country.')).toBeVisible();
    expect(sent).toHaveLength(0);
  });

  it('asks for the role separately from the department, and says who decides (D-24)', async () => {
    server();
    await renderRegister();
    expect(screen.getByLabelText(/Department/)).toBeVisible();
    expect(screen.getByLabelText(/Role you are asking for/)).toBeVisible();
    expect(screen.getByText('An administrator decides the role you are granted.')).toBeVisible();
  });

  it('refuses to send an incomplete form and says which fields are wrong', async () => {
    server();
    await renderRegister();
    await user.type(screen.getByLabelText(/First name/), 'A');
    await user.type(screen.getByLabelText(/Work email/), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Enter an email address like name@example.com.')).toBeVisible();
    expect(screen.getAllByText('This is required.').length).toBeGreaterThan(0);
    expect(sent).toHaveLength(0);
  });

  it('rejects a phone number that is not valid for the country chosen', async () => {
    server();
    await renderRegister();
    await user.selectOptions(screen.getByLabelText(/Country/), DIAL_COUNTRY);
    await user.type(screen.getByLabelText(/Phone number/), '123');
    await user.tab();
    expect(
      await screen.findByText('This is not a valid number for the country selected.'),
    ).toBeVisible();
  });

  it('says when an email is already registered, and will not send it', async () => {
    server();
    await renderRegister();
    await fillValidForm('taken@example.test');
    expect(await screen.findByText('This email is already registered.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(sent).toHaveLength(0);
  });

  it('shows the password rules still unmet, and the strength as words', async () => {
    server();
    await renderRegister();
    await user.type(screen.getByLabelText(/^Password/), 'abcdefgh');
    const unmet = screen.getByRole('list', { name: 'Rules still to meet' });
    expect(within(unmet).getByText('One upper-case letter')).toBeVisible();
    expect(within(unmet).getByText('One digit')).toBeVisible();
    expect(screen.getByText('Password strength: weak')).toBeVisible();
    await user.clear(screen.getByLabelText(/^Password/));
    await user.type(screen.getByLabelText(/^Password/), 'Str0ng!passphrase');
    expect(screen.queryByRole('list', { name: 'Rules still to meet' })).toBeNull();
    expect(screen.getByText('Password strength: strong')).toBeVisible();
  });

  it('will not send while the two passwords differ', async () => {
    server();
    await renderRegister();
    await fillValidForm();
    await user.clear(screen.getByLabelText(/Confirm password/));
    await user.type(screen.getByLabelText(/Confirm password/), 'Str0ng!passphras');
    expect(await screen.findByText('The two passwords do not match.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(sent).toHaveLength(0);
  });

  it('says plainly when the server refuses the registration', async () => {
    server(422);
    await renderRegister();
    await fillValidForm();
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The account could not be created');
  });
});
