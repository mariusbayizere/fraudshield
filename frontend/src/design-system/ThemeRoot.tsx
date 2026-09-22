import CssBaseline from '@mui/material/CssBaseline';
import { StyledEngineProvider, ThemeProvider, type Theme } from '@mui/material/styles';
import type { ReactNode } from 'react';
import { createFraudShieldTheme } from './theme';
import type { ColourMode } from './tokens';

const themes: Record<ColourMode, Theme> = {
  light: createFraudShieldTheme('light'),
  dark: createFraudShieldTheme('dark'),
};

/**
 * The providers every screen, story and component test renders inside.
 *
 * D-37: MUI's CssBaseline owns the resets and `injectFirst` puts MUI's styles ahead of Tailwind's,
 * so a Tailwind layout utility can override a component without `!important`.
 */
export function ThemeRoot({ mode, children }: { mode: ColourMode; children: ReactNode }) {
  return (
    <StyledEngineProvider injectFirst>
      <ThemeProvider theme={themes[mode]}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </StyledEngineProvider>
  );
}
