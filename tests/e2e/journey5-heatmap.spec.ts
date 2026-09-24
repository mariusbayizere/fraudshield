// Journey 5 of TEST-12: a Risk Officer views the geographic fraud heatmap.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).
//
// Requirements: FR-05-02 (portfolio dashboard including the heatmap) as D-46 replaces it: the map
// is aggregated to administrative districts with k-anonymity, cells below the threshold are merged
// or suppressed, and the base map is self-hosted or absent — never an external tile service.
//
// The k-anonymity clause is the one this journey guards. It is a privacy property of a screen that
// looks the same whether or not it holds: a cell with a handful of events renders exactly like a
// safe one, so the assertion is made against the response the console drew from.
import { expect, test } from '@playwright/test';

import { recordTiming, seriousViolations, signIn } from './helpers';

interface HeatmapCell {
  country: string;
  district_code: string;
  fraud_events: number;
}

test.describe('[TEST-12] journey 5 — the portfolio heatmap', () => {
  test('[FR-05-02] the heatmap renders and no cell falls below the k-anonymity threshold', async ({
    page,
  }, testInfo) => {
    await signIn(page, 'RISK_OFFICER');

    const heatmapResponse = page.waitForResponse((response) =>
      response.url().includes('/api/v1/portfolio/heatmap'),
    );
    const started = Date.now();
    await page.goto('/portfolio');
    const response = await heatmapResponse;
    const heatmap = (await response.json()) as {
      k_anonymity_threshold: number;
      cells: HeatmapCell[];
    };

    await expect(page.getByRole('heading', { level: 1, name: 'Portfolio' })).toBeVisible();
    await expect(page.getByRole('figure').or(page.getByRole('img', { name: /map|heatmap/i }))).toBeVisible();
    await recordTiming(testInfo, 'FR-05-02 heatmap rendered', 2000, Date.now() - started);

    // D-46: the threshold is fixed at ten, and every cell that survives aggregation must be at or
    // above it. A single cell below it is a disclosure, not a rendering defect.
    expect(heatmap.k_anonymity_threshold).toBe(10);
    const exposed = heatmap.cells.filter((cell) => cell.fraud_events < heatmap.k_anonymity_threshold);
    expect(exposed, 'cells below the k-anonymity threshold').toEqual([]);

    // No external tile service: the map is self-hosted vector tiles or a plain boundary map.
    const external: string[] = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.host !== new URL(page.url()).host) {
        external.push(url.host);
      }
    });
    await page.getByRole('combobox', { name: /period/i }).selectOption('month');
    await page.waitForResponse((r) => r.url().includes('/portfolio/heatmap'));
    expect(external, 'the heatmap must not fetch tiles from a third party').toEqual([]);

    expect(await seriousViolations(page)).toEqual([]);
  });
});
