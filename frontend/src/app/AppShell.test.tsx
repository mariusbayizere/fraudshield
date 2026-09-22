import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { act, render, screen, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { axeViolations } from '../test/axe';
import { testI18n } from '../test/i18n';
import { Providers } from '../test/Providers';
import type { Direction } from '../i18n/direction';
import { routeTree } from './router';

function wideScreen(wide: boolean) {
  vi.stubGlobal('matchMedia', (query: string): Partial<MediaQueryList> => ({
    matches: wide && query.includes('min-width'),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

async function renderAt(
  path: string,
  { direction = 'ltr', wide = true }: { direction?: Direction; wide?: boolean } = {},
) {
  wideScreen(wide);
  const i18n = testI18n();
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(
    <Providers direction={direction} i18n={i18n}>
      <RouterProvider router={router} />
    </Providers>,
  );
  await screen.findByRole('main');
  return { router, i18n };
}

afterEach(() => {
  vi.unstubAllGlobals();
  try {
    localStorage.clear();
  } catch {
    // jsdom always has storage; the guard mirrors the app's.
  }
});

describe('AppShell (E.9, 05B A.7)', () => {
  it.each([
    ['wide', true],
    ['phone', false],
  ])('has no axe violations on a %s screen', async (_name, wide) => {
    await renderAt('/alerts', { wide });
    await screen.findByRole('heading', { level: 1, name: 'Alerts' });
    expect(await axeViolations(document.body)).toEqual([]);
  });

  it('opens / on the alert feed', async () => {
    const { router } = await renderAt('/');
    await screen.findByRole('heading', { level: 1, name: 'Alerts' });
    expect(router.state.location.pathname).toBe('/alerts');
  });

  it('lists every section in a labelled navigation, marking the current page', async () => {
    await renderAt('/rules');
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getAllByRole('link')).toHaveLength(22);
    expect(within(nav).getByRole('link', { name: 'Rules' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(await screen.findByRole('heading', { level: 1, name: 'Rules' })).toBeVisible();
  });

  it('offers a skip link to the main content as the first focus stop', async () => {
    await renderAt('/alerts');
    await userEvent.tab();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveFocus();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
  });

  it('says plainly that an unbuilt screen has no real data', async () => {
    await renderAt('/portfolio');
    expect(await screen.findByText('Not built yet')).toBeVisible();
  });

  it('switches language, sets <html lang> and remembers the choice', async () => {
    await renderAt('/alerts');
    await userEvent.selectOptions(screen.getByLabelText('Language'), 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Alertes' })).toBeVisible();
    expect(document.documentElement.lang).toBe('fr');
    expect(localStorage.getItem('fs.language')).toBe('fr');
  });

  it.each(['ltr', 'rtl'] as const)(
    'anchors the drawer to the inline start in %s',
    async (direction) => {
      await renderAt('/alerts', { direction });
      // anchor="left" means the inline start. MUI keeps the class name and the RTL cache flips
      // the paper's physical offset, so the computed side is what proves the mirroring.
      const paper = document.querySelector('.MuiDrawer-paper');
      if (paper === null) throw new Error('no drawer');
      const style = getComputedStyle(paper);
      const [near, far] = direction === 'ltr' ? ['left', 'right'] : ['right', 'left'];
      expect(style.getPropertyValue(near)).toBe('0px');
      expect(style.getPropertyValue(far)).not.toBe('0px');
      expect(document.documentElement.dir).toBe(direction);
    },
  );

  it('shows a bottom bar with the main destinations and a menu on phones', async () => {
    await renderAt('/anomalies', { wide: false });
    const bar = screen.getByRole('navigation', { name: 'Primary destinations' });
    expect(
      within(bar)
        .getAllByRole('link')
        .map((l) => l.textContent),
    ).toEqual(['Alerts', 'Anomalies', 'Search']);
    await userEvent.click(screen.getByRole('button', { name: 'Open menu' }));
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    await act(async () => {
      await userEvent.click(within(nav).getByRole('link', { name: 'Settings' }));
    });
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
  });
});
