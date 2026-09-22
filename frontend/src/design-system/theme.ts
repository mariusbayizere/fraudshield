import { createTheme, type Theme } from '@mui/material/styles';
import type { CSSProperties } from 'react';
import {
  tokens,
  type ChannelColours,
  type Channel,
  type ColourMode,
  type RiskColours,
  type RiskTier,
} from './tokens';

declare module '@mui/material/styles' {
  interface Palette {
    risk: Record<RiskTier, RiskColours>;
    channel: Record<Channel, ChannelColours>;
    shap: { increasesFraud: string; decreasesFraud: string };
    chipText: string;
    focusRing: string;
  }
  interface PaletteOptions {
    risk?: Record<RiskTier, RiskColours>;
    channel?: Record<Channel, ChannelColours>;
    shap?: { increasesFraud: string; decreasesFraud: string };
    chipText?: string;
    focusRing?: string;
  }
  interface BreakpointOverrides {
    xs: true;
    sm: true;
    md: true;
    lg: true;
    xl: true;
    fp: true;
    xxl: true;
    xxxl: true;
  }
  interface TypographyVariants {
    numeric: CSSProperties;
  }
  interface TypographyVariantsOptions {
    numeric?: CSSProperties;
  }
}

declare module '@mui/material/Typography' {
  interface TypographyPropsVariantOverrides {
    numeric: true;
  }
}

/**
 * The MUI theme for one colour mode, built only from design tokens (E.9, D-36, D-37).
 *
 * MUI's semantic colours map onto the SRS's own: primary is brand navy, secondary the accent,
 * error/warning/success the three risk tiers. Text drawn in a risk colour must use the tier's
 * `text` token (D-33), which the palette carries under `palette.risk`.
 */
export function createFraudShieldTheme(mode: ColourMode): Theme {
  const c = tokens.color[mode];
  const t = tokens.typography;
  return createTheme({
    palette: {
      mode,
      primary: { main: c.brand.navy, contrastText: c.brand.onBrand },
      secondary: { main: c.brand.accent, contrastText: c.brand.onBrand },
      error: { main: c.risk.high.border, dark: c.risk.high.text, contrastText: c.chipText },
      warning: { main: c.risk.medium.border, dark: c.risk.medium.text, contrastText: c.chipText },
      success: { main: c.risk.low.border, dark: c.risk.low.text, contrastText: c.chipText },
      background: { default: c.surface.background, paper: c.surface.paper },
      text: { primary: c.surface.textPrimary, secondary: c.surface.textSecondary },
      divider: c.surface.divider,
      risk: c.risk,
      channel: c.channel,
      shap: c.shap,
      chipText: c.chipText,
      focusRing: c.focusRing,
    },
    breakpoints: { values: tokens.breakpoints },
    spacing: tokens.spacing.unit,
    shape: { borderRadius: tokens.radius.md },
    typography: {
      fontFamily: t.fontFamily,
      fontWeightRegular: t.weights.regular,
      fontWeightMedium: t.weights.medium,
      fontWeightBold: t.weights.bold,
      // Amounts, scores and timers line up in columns (E.9: tabular numerals).
      numeric: { fontVariantNumeric: t.numeric, fontFamily: t.fontFamily },
    },
    zIndex: tokens.zIndex,
    transitions: {
      duration: {
        shortest: tokens.motion.durationMs.short,
        shorter: tokens.motion.durationMs.short,
        short: tokens.motion.durationMs.short,
        standard: tokens.motion.durationMs.standard,
        complex: tokens.motion.durationMs.standard,
        enteringScreen: tokens.motion.durationMs.drawer,
        leavingScreen: tokens.motion.durationMs.drawer,
      },
    },
    components: {
      MuiButtonBase: {
        styleOverrides: {
          root: {
            // FR-04-12: a visible focus ring in the accent colour, never removed.
            '&.Mui-focusVisible': {
              outline: `3px solid ${c.focusRing}`,
              outlineOffset: 2,
            },
          },
        },
      },
    },
  });
}

/** How long a HIGH card pulses on arrival before settling (D-34): three cycles, then static. */
export const HIGH_PULSE_TOTAL_MS = tokens.motion.durationMs.pulseCycle * tokens.motion.pulseCycles;
