/**
 * WCAG 2.1 relative luminance and contrast ratio.
 *
 * Used by the design-token contrast test (D-33) so that every text/background pair in the
 * theme is measured rather than assumed. Formulae: WCAG 2.1 success criterion 1.4.3,
 * "relative luminance" and "contrast ratio" definitions.
 */

const HEX_COLOUR = /^#([0-9a-f]{6})$/i;

/** Minimum contrast for normal-size text (WCAG 2.1 AA, SC 1.4.3). */
export const AA_NORMAL_TEXT = 4.5;
/** Minimum contrast for large text and non-text UI components (SC 1.4.3 / 1.4.11). */
export const AA_LARGE_TEXT_AND_UI = 3;

export class InvalidColourError extends Error {
  constructor(value: string) {
    super(`expected a #RRGGBB colour, got "${value}"`);
    this.name = 'InvalidColourError';
  }
}

function channelToLinear(channel8bit: number): number {
  const srgb = channel8bit / 255;
  return srgb <= 0.04045 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4;
}

/** Relative luminance of an opaque sRGB colour written as #RRGGBB. */
export function relativeLuminance(hex: string): number {
  const match = HEX_COLOUR.exec(hex);
  const digits = match?.[1];
  if (digits === undefined) {
    throw new InvalidColourError(hex);
  }
  const value = Number.parseInt(digits, 16);
  const red = channelToLinear((value >> 16) & 0xff);
  const green = channelToLinear((value >> 8) & 0xff);
  const blue = channelToLinear(value & 0xff);
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/** Contrast ratio between two opaque colours, from 1 (identical) to 21 (black on white). */
export function contrastRatio(foreground: string, background: string): number {
  const a = relativeLuminance(foreground);
  const b = relativeLuminance(background);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}
