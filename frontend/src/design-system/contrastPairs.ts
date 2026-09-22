import { AA_LARGE_TEXT_AND_UI, AA_NORMAL_TEXT } from './color/contrast';
import { CHANNELS, RISK_TIERS, type ColourMode, type Tokens } from './tokens';

export interface ColourPair {
  name: string;
  foreground: string;
  background: string;
  /** 4.5 for text (WCAG 1.4.3), 3 for borders, bars, icons and focus rings (1.4.11). */
  minimum: number;
}

/**
 * Every foreground/background pair the theme draws, in one mode (D-33).
 *
 * A pair is listed here because a component uses it, not because it looked right: the contrast
 * test measures each one, so a colour change that breaks a pairing fails a test rather than an
 * audit. Text pairs need 4.5:1; borders, SHAP bars, channel fills and the focus ring need 3:1.
 */
export function colourPairs(tokens: Tokens, mode: ColourMode): ColourPair[] {
  const c = tokens.color[mode];
  const s = c.surface;
  const text = (name: string, foreground: string, background: string): ColourPair => ({
    name,
    foreground,
    background,
    minimum: AA_NORMAL_TEXT,
  });
  const ui = (name: string, foreground: string, background: string): ColourPair => ({
    name,
    foreground,
    background,
    minimum: AA_LARGE_TEXT_AND_UI,
  });
  const pairs: ColourPair[] = [
    text('text.primary on background', s.textPrimary, s.background),
    text('text.primary on paper', s.textPrimary, s.paper),
    text('text.secondary on paper', s.textSecondary, s.paper),
    text('text.secondary on background', s.textSecondary, s.background),
    text('link (brand.accent) on paper', c.brand.accent, s.paper),
    text('button text on brand.navy', c.brand.onBrand, c.brand.navy),
    ui('focus ring on paper', c.focusRing, s.paper),
  ];
  if (mode === 'light') {
    pairs.push(text('button text on brand.accent', c.brand.onBrand, c.brand.accent));
  }
  for (const tier of RISK_TIERS) {
    const r = c.risk[tier];
    pairs.push(
      text(`risk.${tier}.text on risk.${tier}.bg`, r.text, r.bg),
      text(`risk.${tier}.text on paper`, r.text, s.paper),
      ui(`risk.${tier}.border on risk.${tier}.bg`, r.border, r.bg),
      ui(`risk.${tier}.border on paper`, r.border, s.paper),
    );
  }
  for (const channel of CHANNELS) {
    const ch = c.channel[channel];
    pairs.push(
      text(`chip text on channel.${channel}.chip`, c.chipText, ch.chip),
      ui(`channel.${channel}.fill on paper`, ch.fill, s.paper),
    );
  }
  pairs.push(
    ui('shap.increasesFraud on paper', c.shap.increasesFraud, s.paper),
    ui('shap.decreasesFraud on paper', c.shap.decreasesFraud, s.paper),
  );
  return pairs;
}
