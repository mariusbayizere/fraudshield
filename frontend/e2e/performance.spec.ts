import { expect, test, type Page } from '@playwright/test';

/**
 * MOB-PERF-01 to 05 on the SRS's 3G (Moto G4) targets, measured rather than inferred from bundle
 * size (ADR 0080 §10, amendment 2026-10-19). A first visit to the sign-in page, the page every
 * analyst loads first, from a production build with the cache and the service worker out of play.
 *
 * Conditions: MOB-NET-02 puts 3G at 1-5 Mbps and 100-200 ms; the slow end is used (1 Mbps down,
 * 200 ms round trip). The CPU is slowed four times, as MOB-PERF-01 specifies for the Moto G4.
 *
 * Time to interactive follows Lighthouse's rule on the main thread only: the end of the last long
 * task (over 50 ms) before the first 5 s window after first contentful paint with none. Lighthouse
 * also requires that window to have at most two requests in flight; this measurement does not
 * check the network, so it can read earlier than Lighthouse would.
 */

const NETWORK = {
  offline: false,
  latency: 200,
  downloadThroughput: (1 * 1024 * 1024) / 8,
  uploadThroughput: (512 * 1024) / 8,
};
const CPU_SLOWDOWN = 4;
const QUIET_MS = 5_000;
const RUNS = 3;

/** MOB-PERF-01 to 05, 3G column. */
const TARGETS = { fcp: 2_500, lcp: 4_000, tti: 5_000, tbt: 300, cls: 0.1 };

interface Run {
  fcp: number;
  lcp: number;
  tti: number;
  tbt: number;
  cls: number;
}

declare global {
  interface Window {
    __fsPerf: { longTasks: { start: number; end: number }[]; lcp: number; cls: number };
  }
}

async function measure(page: Page): Promise<Run> {
  await page.addInitScript(() => {
    window.__fsPerf = { longTasks: [], lcp: 0, cls: 0 };
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        window.__fsPerf.longTasks.push({
          start: entry.startTime,
          end: entry.startTime + entry.duration,
        });
      }
    }).observe({ type: 'longtask', buffered: true });
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) window.__fsPerf.lcp = entry.startTime;
    }).observe({ type: 'largest-contentful-paint', buffered: true });
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const shift = entry as PerformanceEntry & { value: number; hadRecentInput: boolean };
        if (!shift.hadRecentInput) window.__fsPerf.cls += shift.value;
      }
    }).observe({ type: 'layout-shift', buffered: true });
  });

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
  await cdp.send('Network.emulateNetworkConditions', NETWORK);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: CPU_SLOWDOWN });

  await page.goto('/login');
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible({
    timeout: 30_000,
  });

  // Wait until a whole quiet window has passed after the last long task.
  const fcp = await page.evaluate(
    () => performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? Number.NaN,
  );
  for (;;) {
    const { now, lastEnd } = await page.evaluate(() => ({
      now: performance.now(),
      lastEnd: Math.max(0, ...window.__fsPerf.longTasks.map((t) => t.end)),
    }));
    if (now - Math.max(fcp, lastEnd) >= QUIET_MS) break;
    await page.waitForTimeout(500);
  }

  const perf = await page.evaluate(() => window.__fsPerf);
  const after = perf.longTasks.filter((t) => t.end > fcp);
  const tti = Math.max(fcp, ...after.map((t) => t.end));
  const tbt = after
    .filter((t) => t.start < tti)
    .reduce((sum, t) => sum + Math.max(0, t.end - Math.max(t.start, fcp) - 50), 0);
  await cdp.detach();
  return { fcp, lcp: perf.lcp, tti, tbt, cls: perf.cls };
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)] ?? Number.NaN;
}

test.describe('first visit on 3G with a slowed CPU (MOB-PERF-01 to 05)', () => {
  test.setTimeout(180_000);

  test('[MOB-PERF-01, MOB-PERF-02, MOB-PERF-03, MOB-PERF-04, MOB-PERF-05] meets the 3G targets', async ({
    browser,
  }) => {
    const runs: Run[] = [];
    for (let i = 0; i < RUNS; i += 1) {
      // A first visit on a phone: a fresh profile, no service worker, a small screen.
      const context = await browser.newContext({
        serviceWorkers: 'block',
        viewport: { width: 360, height: 640 },
      });
      runs.push(await measure(await context.newPage()));
      await context.close();
    }
    const result = {
      fcp: median(runs.map((r) => r.fcp)),
      lcp: median(runs.map((r) => r.lcp)),
      tti: median(runs.map((r) => r.tti)),
      tbt: median(runs.map((r) => r.tbt)),
      cls: median(runs.map((r) => r.cls)),
    };
    // The numbers are the evidence; they go to the log whether or not they pass.
    console.log(`MOB-PERF 3G medians of ${String(RUNS)}: ${JSON.stringify(result)}`);
    console.log(`MOB-PERF 3G runs: ${JSON.stringify(runs)}`);
    expect.soft(result.fcp, 'MOB-PERF-01 first contentful paint (ms)').toBeLessThan(TARGETS.fcp);
    expect.soft(result.lcp, 'MOB-PERF-02 largest contentful paint (ms)').toBeLessThan(TARGETS.lcp);
    expect.soft(result.tti, 'MOB-PERF-03 time to interactive (ms)').toBeLessThan(TARGETS.tti);
    expect.soft(result.tbt, 'MOB-PERF-04 total blocking time (ms)').toBeLessThan(TARGETS.tbt);
    expect.soft(result.cls, 'MOB-PERF-05 cumulative layout shift').toBeLessThan(TARGETS.cls);
  });
});
