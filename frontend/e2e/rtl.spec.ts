import { expect, test } from '@playwright/test';
import { seriousViolations } from './axe';

/**
 * ADR 0023 and ADR 0080 R6: the console works right-to-left. None of the four UI languages is
 * RTL, so `?dir=rtl` forces the direction; the layout must mirror, not merely flip a flag.
 */
test.describe('right-to-left', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  for (const dir of ['ltr', 'rtl'] as const) {
    test(`the shell lays out ${dir}`, async ({ page }) => {
      await page.goto(`/alerts?dir=${dir}`);
      await expect(page.getByRole('heading', { level: 1, name: 'Alerts' })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('dir', dir);

      const nav = await page.getByRole('navigation', { name: 'Main navigation' }).boundingBox();
      const main = await page.getByRole('main').boundingBox();
      const heading = await page.getByRole('heading', { level: 1 }).boundingBox();
      if (nav === null || main === null || heading === null) throw new Error('not laid out');
      if (dir === 'ltr') {
        expect(nav.x + nav.width).toBeLessThanOrEqual(main.x + 1);
        expect(heading.x).toBeLessThan(main.x + main.width / 2);
      } else {
        // The drawer sits at the inline start, which is the right; text starts on the right.
        expect(main.x + main.width).toBeLessThanOrEqual(nav.x + 1);
        expect(heading.x + heading.width).toBeGreaterThan(main.x + main.width / 2);
      }
      expect(await seriousViolations(page)).toEqual([]);
    });
  }

  test('the direction survives navigation', async ({ page }) => {
    await page.goto('/alerts?dir=rtl');
    await page.getByRole('link', { name: 'Rules' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Rules' })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  });
});
