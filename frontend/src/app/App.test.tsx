import { createMemoryHistory, createRouter } from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';
import { COUNTRY_Z } from '../test/fixtures';
import { testI18n } from '../test/i18n';
import { App } from './App';
import { routeTree } from './router';

describe('App', () => {
  it.each([
    [undefined, 'ltr'],
    ['rtl', 'rtl'],
  ] as const)('starts the console with direction override %s as %s', async (override, dir) => {
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/search'] }),
    });
    render(
      <App i18n={testI18n()} region={COUNTRY_Z} router={router} directionOverride={override} />,
    );
    // The shell is a lazy route (ADR 0080 §10); its first import takes longer than the default.
    const heading = await screen.findByRole(
      'heading',
      { level: 1, name: 'Search' },
      { timeout: 10_000 },
    );
    expect(heading).toBeVisible();
    expect(document.documentElement.dir).toBe(dir);
  });
});
