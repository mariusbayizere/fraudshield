import createCache, { type EmotionCache } from '@emotion/cache';
import { CacheProvider } from '@emotion/react';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider, type Theme } from '@mui/material/styles';
import { useEffect, type ReactNode } from 'react';
import { prefixer } from 'stylis';
import rtlPlugin from 'stylis-plugin-rtl';
import type { Direction } from '../i18n/direction';
import { createFraudShieldTheme } from './theme';
import type { ColourMode } from './tokens';

/**
 * One Emotion cache per direction. `prepend` puts MUI's styles ahead of Tailwind's, so a
 * Tailwind layout utility can override a component without `!important` (D-37; it replaces
 * StyledEngineProvider's `injectFirst`, which a custom cache supersedes). The right-to-left
 * cache flips MUI's own physical properties; M8 code uses logical properties, which need no
 * flipping (ADR 0080).
 */
const caches: Record<Direction, EmotionCache> = {
  ltr: createCache({ key: 'mui', prepend: true }),
  rtl: createCache({ key: 'muirtl', prepend: true, stylisPlugins: [prefixer, rtlPlugin] }),
};

const themes = new Map<string, Theme>();
function themeFor(mode: ColourMode, direction: Direction): Theme {
  const key = `${mode}|${direction}`;
  let theme = themes.get(key);
  if (theme === undefined) {
    theme = createFraudShieldTheme(mode, direction);
    themes.set(key, theme);
  }
  return theme;
}

/** The providers every screen, story and component test renders inside. */
export function ThemeRoot({
  mode,
  direction = 'ltr',
  children,
}: {
  mode: ColourMode;
  direction?: Direction;
  children: ReactNode;
}) {
  useEffect(() => {
    document.documentElement.dir = direction;
  }, [direction]);
  return (
    <CacheProvider value={caches[direction]}>
      <ThemeProvider theme={themeFor(mode, direction)}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </CacheProvider>
  );
}
