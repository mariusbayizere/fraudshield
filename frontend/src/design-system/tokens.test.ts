import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { contrastRatio } from './color/contrast';
import { colourPairs } from './contrastPairs';
import { renderCssVariables, renderTailwindTheme } from './css';
import { createFraudShieldTheme, HIGH_PULSE_TOTAL_MS } from './theme';
import { BREAKPOINT_ORDER, CHANNELS, tokens, type ColourMode } from './tokens';

const MODES: ColourMode[] = ['light', 'dark'];

describe.each(MODES)('the %s scheme', (mode) => {
  it.each(colourPairs(tokens, mode).map((p) => [p.name, p] as const))(
    '%s meets its WCAG minimum (D-33)',
    (_name, pair) => {
      expect(contrastRatio(pair.foreground, pair.background)).toBeGreaterThanOrEqual(pair.minimum);
    },
  );

  it('builds an MUI theme whose semantic colours are the tokens, not defaults', () => {
    const theme = createFraudShieldTheme(mode);
    const c = tokens.color[mode];
    expect(theme.palette.mode).toBe(mode);
    expect(theme.palette.primary.main).toBe(c.brand.navy);
    expect(theme.palette.secondary.main).toBe(c.brand.accent);
    expect(theme.palette.error.main).toBe(c.risk.high.border);
    expect(theme.palette.error.dark).toBe(c.risk.high.text);
    expect(theme.palette.background.paper).toBe(c.surface.paper);
    expect(theme.palette.risk.medium.text).toBe(c.risk.medium.text);
    expect(theme.palette.channel.USSD.chip).toBe(c.channel.USSD.chip);
  });

  it('puts white chip text only on a chip background that passes, never on a failing hue', () => {
    // D-33's own measurement: white on the SRS amber is 3.19:1 and on teal 3.74:1, so the USSD
    // and AGENT_BANKING chips must use their text-safe backgrounds.
    const c = tokens.color[mode];
    expect(c.channel.USSD.chip).not.toBe('#D97706');
    expect(c.channel.AGENT_BANKING.chip).not.toBe('#0D9488');
    for (const channel of CHANNELS) {
      expect(contrastRatio(c.chipText, c.channel[channel].chip)).toBeGreaterThanOrEqual(4.5);
    }
  });
});

describe('breakpoints (D-36)', () => {
  it('are the canonical set, ascending, with these exact names and widths', () => {
    expect(tokens.breakpoints).toEqual({
      fp: 240,
      xs: 320,
      sm: 375,
      md: 600,
      lg: 768,
      xl: 1024,
      xxl: 1280,
      xxxl: 1536,
    });
    const widths = BREAKPOINT_ORDER.map((name) => tokens.breakpoints[name]);
    expect(widths).toEqual([...widths].sort((a, b) => a - b));
  });

  it('are identical in MUI and in Tailwind', () => {
    const theme = createFraudShieldTheme('light');
    for (const name of BREAKPOINT_ORDER) {
      expect(theme.breakpoints.values[name]).toBe(tokens.breakpoints[name]);
    }
    const tailwind = renderTailwindTheme(tokens);
    for (const name of BREAKPOINT_ORDER) {
      expect(tailwind).toContain(`--breakpoint-${name}: ${String(tokens.breakpoints[name])}px;`);
    }
    expect(tailwind).toContain('--breakpoint-*: initial;');
  });
});

describe('motion (D-34)', () => {
  it('pulses a HIGH card for three 1.2 s cycles, 3.6 s in all, then stops', () => {
    expect(HIGH_PULSE_TOTAL_MS).toBe(3600);
  });

  it('turns every duration to zero when reduced motion is requested', () => {
    const css = renderCssVariables(tokens);
    const reduced = css.slice(css.indexOf('prefers-reduced-motion'));
    for (const name of ['short', 'standard', 'drawer', 'pulse-cycle']) {
      expect(reduced).toContain(`--fs-duration-${name}: 0ms;`);
    }
  });
});

describe('the generated CSS', () => {
  it.each([
    ['tokens.css', renderCssVariables],
    ['tailwind-theme.css', renderTailwindTheme],
  ] as const)('%s is what the tokens produce, so it cannot drift by hand', (file, render) => {
    const committed = readFileSync(join(import.meta.dirname, 'generated', file), 'utf8');
    expect(committed).toBe(render(tokens));
  });
});
