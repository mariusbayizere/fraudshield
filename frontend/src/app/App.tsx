import useMediaQuery from '@mui/material/useMediaQuery';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, type AnyRouter } from '@tanstack/react-router';
import type { i18n as I18n } from 'i18next';
import { useState } from 'react';
import { I18nextProvider } from 'react-i18next';
import { ThemeRoot } from '../design-system/ThemeRoot';
import type { Direction } from '../i18n/direction';
import type { Region } from '../region/region';
import { RegionProvider } from '../region/RegionContext';

export interface AppProps {
  i18n: I18n;
  region: Region;
  router: AnyRouter;
  /** `?dir=` at start-up (ADR 0080); otherwise the language's own direction. */
  directionOverride: Direction | undefined;
}

/** Providers in dependency order: language, region, server state, theme, then routes. */
export function App({ i18n, region, router, directionOverride }: AppProps) {
  const [queryClient] = useState(() => new QueryClient());
  const dark = useMediaQuery('(prefers-color-scheme: dark)', { noSsr: true });
  const direction = directionOverride ?? i18n.dir();
  return (
    <I18nextProvider i18n={i18n}>
      <RegionProvider region={region}>
        <QueryClientProvider client={queryClient}>
          <ThemeRoot mode={dark ? 'dark' : 'light'} direction={direction}>
            <RouterProvider router={router} />
          </ThemeRoot>
        </QueryClientProvider>
      </RegionProvider>
    </I18nextProvider>
  );
}
