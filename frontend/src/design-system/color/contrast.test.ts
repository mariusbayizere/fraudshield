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
    { label: 'white on risk.medium #D97706', fg: WHITE, bg: '#D97706', expected: 3.19 },
    { label: 'white on risk.low #059669', fg: WHITE, bg: '#059669', expected: 3.77 },
    { label: 'white on channel.agent_banking #0D9488', fg: WHITE, bg: '#0D9488', expected: 3.74 },
    { label: '#D97706 on risk.medium.bg #FFFBEB', fg: '#D97706', bg: '#FFFBEB', expected: 3.07 },
    { label: '#DC2626 on risk.high.bg #FEF2F2', fg: '#DC2626', bg: '#FEF2F2', expected: 4.41 },
  ])('[D-33] SRS pairing $label fails AA text contrast at $expected:1', ({ fg, bg, expected }) => {
    const ratio = contrastRatio(fg, bg);
    expect(twoDecimals(ratio)).toBe(expected);
    expect(ratio).toBeLessThan(AA_NORMAL_TEXT);
  });

  // D-33 text-safe replacement tokens.
  it.each([
    { label: 'risk.high.text on risk.high.bg', fg: '#B91C1C', bg: '#FEF2F2', expected: 5.91 },
    { label: 'risk.medium.text on risk.medium.bg', fg: '#B45309', bg: '#FFFBEB', expected: 4.84 },
    { label: 'white on risk.medium.text', fg: WHITE, bg: '#B45309', expected: 5.02 },
    { label: 'risk.low.text on risk.low.bg', fg: '#047857', bg: '#F0FDF4', expected: 5.24 },
    { label: 'white on risk.low.text', fg: WHITE, bg: '#047857', expected: 5.48 },
    { label: 'white on channel.agent_banking.text', fg: WHITE, bg: '#0F766E', expected: 5.47 },
  ])('[D-33] text-safe token $label passes AA at $expected:1', ({ fg, bg, expected }) => {
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
