import {
  AA_LARGE_TEXT_AND_UI,
  AA_NORMAL_TEXT,
  InvalidColourError,
  contrastRatio,
  relativeLuminance,
} from './contrast';

const WHITE = '#FFFFFF';

/**
 * Reported ratios (D-33) are rounded to two decimals. Pass/fail is always decided on the exact
 * ratio: 4.496 would be reported as 4.50 yet still fails the 4.5:1 threshold.
 */
const twoDecimals = (ratio: number): number => Math.round(ratio * 100) / 100;

describe('contrastRatio', () => {
  it('matches the WCAG reference extremes', () => {
    expect(contrastRatio('#000000', WHITE)).toBeCloseTo(21, 10);
    expect(contrastRatio('#777777', '#777777')).toBe(1);
    expect(relativeLuminance(WHITE)).toBeCloseTo(1, 12);
  });

  it('is symmetric in its arguments', () => {
    expect(contrastRatio('#D97706', '#FFFBEB')).toBe(contrastRatio('#FFFBEB', '#D97706'));
  });

  it('decides pass/fail on the exact ratio, not the rounded report', () => {
    // #767676 on white is the classic boundary: 4.54:1 passes; #777777 is 4.48:1 and fails.
    expect(contrastRatio('#767676', WHITE)).toBeGreaterThanOrEqual(AA_NORMAL_TEXT);
    expect(contrastRatio('#777777', WHITE)).toBeLessThan(AA_NORMAL_TEXT);
  });

  it('rejects colours that are not #RRGGBB', () => {
    expect(() => relativeLuminance('#FFF')).toThrow(InvalidColourError);
    expect(() => relativeLuminance('red')).toThrow(InvalidColourError);
  });

  // D-33 measured failures of SRS 5.2 pairings when used for normal-size text.
  it.each([
    ['white on risk.medium #D97706', WHITE, '#D97706', 3.19],
    ['white on risk.low #059669', WHITE, '#059669', 3.77],
    ['white on channel.agent_banking #0D9488', WHITE, '#0D9488', 3.74],
    ['#D97706 on risk.medium.bg #FFFBEB', '#D97706', '#FFFBEB', 3.07],
    ['#DC2626 on risk.high.bg #FEF2F2', '#DC2626', '#FEF2F2', 4.41],
  ])('[D-33] SRS pairing %s fails AA text contrast at %d:1', (_label, fg, bg, expected) => {
    const ratio = contrastRatio(fg, bg);
    expect(twoDecimals(ratio)).toBe(expected);
    expect(ratio).toBeLessThan(AA_NORMAL_TEXT);
  });

  // D-33 text-safe replacement tokens.
  it.each([
    ['risk.high.text on risk.high.bg', '#B91C1C', '#FEF2F2', 5.91],
    ['risk.medium.text on risk.medium.bg', '#B45309', '#FFFBEB', 4.84],
    ['white on risk.medium.text', WHITE, '#B45309', 5.02],
    ['risk.low.text on risk.low.bg', '#047857', '#F0FDF4', 5.24],
    ['white on risk.low.text', WHITE, '#047857', 5.48],
    ['white on channel.agent_banking.text', WHITE, '#0F766E', 5.47],
  ])('[D-33] text-safe token %s passes AA at %d:1', (_label, fg, bg, expected) => {
    const ratio = contrastRatio(fg, bg);
    expect(twoDecimals(ratio)).toBe(expected);
    expect(ratio).toBeGreaterThanOrEqual(AA_NORMAL_TEXT);
  });

  it('[D-33] keeps the SRS hues usable as borders and fills (non-text >= 3:1)', () => {
    for (const hue of ['#D97706', '#059669', '#0D9488']) {
      expect(contrastRatio(hue, WHITE)).toBeGreaterThanOrEqual(AA_LARGE_TEXT_AND_UI);
    }
  });
});
