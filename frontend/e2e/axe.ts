import type { Page } from '@playwright/test';
import { createRequire } from 'node:module';

const axePath = createRequire(import.meta.url).resolve('axe-core/axe.min.js');

interface AxeResult {
  violations: { id: string; impact: string | null; nodes: unknown[] }[];
}

/** Critical and serious axe violations on the page, colour contrast included (M8 gate). */
export async function seriousViolations(page: Page): Promise<string[]> {
  await page.addScriptTag({ path: axePath });
  const result = await page.evaluate(
    async () => await (window as unknown as { axe: { run: () => Promise<AxeResult> } }).axe.run(),
  );
  return result.violations
    .filter((v) => v.impact === 'critical' || v.impact === 'serious')
    .map((v) => `${v.impact ?? ''} ${v.id} (${String(v.nodes.length)})`);
}
