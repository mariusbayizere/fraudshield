import { BREAKPOINT_ORDER, CHANNELS, RISK_TIERS, type ColourMode, type Tokens } from './tokens.ts';

const kebab = (value: string): string =>
  value
    .replaceAll('_', '-')
    .replace(/([a-z])([A-Z])/g, '$1-$2')
    .toLowerCase();

function colourVariables(tokens: Tokens, mode: ColourMode): string[] {
  const c = tokens.color[mode];
  const lines = [
    `--fs-brand-navy: ${c.brand.navy};`,
    `--fs-brand-accent: ${c.brand.accent};`,
    `--fs-on-brand: ${c.brand.onBrand};`,
    `--fs-focus-ring: ${c.focusRing};`,
    `--fs-chip-text: ${c.chipText};`,
    `--fs-shap-increases-fraud: ${c.shap.increasesFraud};`,
    `--fs-shap-decreases-fraud: ${c.shap.decreasesFraud};`,
  ];
  for (const [key, value] of Object.entries(c.surface)) {
    lines.push(`--fs-${kebab(key)}: ${value};`);
  }
  for (const tier of RISK_TIERS) {
    const risk = c.risk[tier];
    for (const key of ['bg', 'border', 'badge', 'text'] as const) {
      lines.push(`--fs-risk-${tier}-${key}: ${risk[key]};`);
    }
  }
  for (const channel of CHANNELS) {
    const colours = c.channel[channel];
    for (const key of ['fill', 'chip'] as const) {
      lines.push(`--fs-channel-${kebab(channel)}-${key}: ${colours[key]};`);
    }
  }
  return lines;
}

const block = (selector: string, lines: string[], indent = ''): string =>
  [`${indent}${selector} {`, ...lines.map((l) => `${indent}  ${l}`), `${indent}}`].join('\n');

/**
 * CSS custom properties for both schemes. Dark applies when the user chose it
 * (`data-theme="dark"`) or when the system prefers it and the user has not chosen light.
 */
export function renderCssVariables(tokens: Tokens): string {
  const shared = [
    `--fs-font-family: ${tokens.typography.fontFamily};`,
    `--fs-font-family-mono: ${tokens.typography.fontFamilyMono};`,
    `--fs-space: ${String(tokens.spacing.unit)}px;`,
    ...Object.entries(tokens.radius).map(([k, v]) => `--fs-radius-${k}: ${String(v)}px;`),
    ...Object.entries(tokens.motion.durationMs).map(
      ([k, v]) => `--fs-duration-${kebab(k)}: ${String(v)}ms;`,
    ),
    `--fs-touch-target: ${String(tokens.touchTargetPx)}px;`,
  ];
  return [
    '/* GENERATED from design-tokens/tokens.json by scripts/generate-tokens.ts. Do not edit. */',
    block(':root', [...shared, ...colourVariables(tokens, 'light')]),
    block('[data-theme="dark"]', colourVariables(tokens, 'dark')),
    '@media (prefers-color-scheme: dark) {',
    block(':root:not([data-theme="light"])', colourVariables(tokens, 'dark'), '  '),
    '}',
    // D-34 and WCAG 2.3.3: with reduced motion requested, nothing animates.
    '@media (prefers-reduced-motion: reduce) {',
    block(
      ':root',
      Object.keys(tokens.motion.durationMs).map(
        (k) => `--fs-duration-${kebab(k)}: ${String(tokens.motion.reducedDurationMs)}ms;`,
      ),
      '  ',
    ),
    '}',
    '',
  ].join('\n');
}

/**
 * Tailwind 4's `@theme`, so utilities use the same breakpoints (D-36) and spacing as MUI. Colours
 * point at the CSS variables, so a utility follows the active scheme instead of fixing one.
 */
export function renderTailwindTheme(tokens: Tokens): string {
  const colours = ['brand-navy', 'brand-accent', 'on-brand', 'background', 'paper', 'divider']
    .map((name) => `--color-${name}: var(--fs-${name});`)
    .concat(
      RISK_TIERS.flatMap((tier) =>
        ['bg', 'border', 'text'].map(
          (key) => `--color-risk-${tier}-${key}: var(--fs-risk-${tier}-${key});`,
        ),
      ),
    );
  const lines = [
    '--breakpoint-*: initial;',
    ...BREAKPOINT_ORDER.map(
      (name) => `--breakpoint-${name}: ${String(tokens.breakpoints[name])}px;`,
    ),
    `--spacing: ${String(tokens.spacing.unit)}px;`,
    `--font-sans: ${tokens.typography.fontFamily};`,
    ...colours,
  ];
  return [
    '/* GENERATED from design-tokens/tokens.json by scripts/generate-tokens.ts. Do not edit. */',
    block('@theme', lines),
    '',
  ].join('\n');
}
