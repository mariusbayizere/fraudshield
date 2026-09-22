export type Direction = 'ltr' | 'rtl';

/**
 * A `?dir=rtl` or `?dir=ltr` override (ADR 0080). None of the four UI languages is written
 * right-to-left, so this is how the right-to-left layout ADR 0023 requires is exercised and
 * tested before a right-to-left language arrives. It changes layout only.
 */
export function directionOverride(search: string): Direction | undefined {
  const value = new URLSearchParams(search).get('dir');
  return value === 'rtl' || value === 'ltr' ? value : undefined;
}
