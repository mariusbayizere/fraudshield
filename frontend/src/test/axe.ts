import axe from 'axe-core';

/**
 * Axe violations in a rendered tree (FR-04-12; M8 gate: zero critical or serious).
 *
 * Component tests hold every impact level to zero, which is stricter than the gate. Colour
 * contrast is off here only because jsdom does no layout or painting, so axe cannot measure it;
 * tokens.test.ts measures every pair the theme draws, and the Playwright journeys run axe with
 * contrast on in real browsers.
 */
export async function axeViolations(container: Element): Promise<string[]> {
  const result = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false } },
  });
  return result.violations.map(
    (v) => `${v.impact ?? 'unknown'} ${v.id}: ${v.help} (${String(v.nodes.length)} node(s))`,
  );
}
