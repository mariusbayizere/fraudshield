import type { i18n as I18n } from 'i18next';
import type { ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';
import { ThemeRoot } from '../design-system/ThemeRoot';
import type { ColourMode } from '../design-system/tokens';
import type { Direction } from '../i18n/direction';
import type { Region } from '../region/region';
import { RegionProvider } from '../region/RegionContext';
import { COUNTRY_Z } from './fixtures';
import { testI18n } from './i18n';

export interface ProviderOptions {
  mode?: ColourMode;
  direction?: Direction;
  region?: Region;
  i18n?: I18n;
}

const ENGLISH = testI18n();

/** Everything a component needs around it, as the app provides it, with Country Z's region. */
export function Providers({
  mode = 'light',
  direction = 'ltr',
  region = COUNTRY_Z,
  i18n = ENGLISH,
  children,
}: ProviderOptions & { children: ReactNode }) {
  return (
    <I18nextProvider i18n={i18n}>
      <RegionProvider region={region}>
        <ThemeRoot mode={mode} direction={direction}>
          {children}
        </ThemeRoot>
      </RegionProvider>
    </I18nextProvider>
  );
}
