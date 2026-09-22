import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { i18n as I18n } from 'i18next';
import { useState, type ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';
import { SessionProvider, type SessionStatus } from '../auth/session';
import { ThemeRoot } from '../design-system/ThemeRoot';
import type { ColourMode } from '../design-system/tokens';
import type { Direction } from '../i18n/direction';
import type { Region } from '../region/region';
import { RegionProvider } from '../region/RegionContext';
import { COUNTRY_Z } from './fixtures';
import { testI18n } from './i18n';

/** Tests never retry a failed request: a test that waits for a retry is a slow test. */
function testQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

export interface ProviderOptions {
  mode?: ColourMode;
  direction?: Direction;
  region?: Region;
  i18n?: I18n;
  queryClient?: QueryClient;
  /** The session the component renders inside; 'restoring' tries the refresh cookie. */
  session?: SessionStatus;
}

const ENGLISH = testI18n();

/** Everything a component needs around it, as the app provides it, with Country Z's region. */
export function Providers({
  mode = 'light',
  direction = 'ltr',
  region = COUNTRY_Z,
  i18n = ENGLISH,
  queryClient,
  session = 'authenticated',
  children,
}: ProviderOptions & { children: ReactNode }) {
  const [client] = useState(() => queryClient ?? testQueryClient());
  return (
    <I18nextProvider i18n={i18n}>
      <RegionProvider region={region}>
        <QueryClientProvider client={client}>
          <ThemeRoot mode={mode} direction={direction}>
            <SessionProvider initial={session}>{children}</SessionProvider>
          </ThemeRoot>
        </QueryClientProvider>
      </RegionProvider>
    </I18nextProvider>
  );
}
