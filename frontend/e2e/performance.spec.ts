import { devices, expect, test, type Page } from '@playwright/test';

/**
 * MOB-PERF-01 to 05 on the SRS's 3G (Moto G4) targets, measured rather than inferred from bundle
 * size (ADR 0080 §10, amendment 2026-10-19). A first visit to the sign-in page, the page every
 * analyst loads first, from a production build with the cache and the service worker out of play.
 *
 * Conditions: MOB-NET-02 puts 3G at 1-5 Mbps and 100-200 ms without naming a point in that range,
 * so two are measured and both reported (owner, 2026-10-19): the slow end, 1 Mbps, and 1.6 Mbps,
 * the downlink of Lighthouse's usual 3G profile. Both keep the slow end's 200 ms round trip, so
 * only the bandwidth differs. The CPU is slowed four times, as MOB-PERF-01 specifies for the
 * Moto G4. Each condition is gated on the same 3G targets.
 *
 * Time to interactive follows Lighthouse's rule on the main thread only: the end of the last long
 * task (over 50 ms) before the first 5 s window after first contentful paint with none, and never
 * before DOMContentLoaded. Lighthouse also requires that window to have at most two requests in
 * flight; this measurement does not check the network, so it can read earlier than Lighthouse
 * would. Layout shift is the sum of every shift, not Lighthouse's largest session window, which
 * reads high, the safe side. The device is Playwright's Moto G4 profile (a phone viewport, touch,
 * its pixel ratio); the runner's CPU slowed four times is not calibrated to a real Moto G4.
 */

interface Network {
  offline: boolean;
  latency: number;
  downloadThroughput: number;
  uploadThroughput: number;
}

function threeG(megabits: number): Network {
  return {
    offline: false,
    latency: 200,
    downloadThroughput: (megabits * 1024 * 1024) / 8,
    uploadThroughput: (512 * 1024) / 8,
  };
}

const CONDITIONS = [
  { name: '1 Mbps', network: threeG(1) },
  { name: '1.6 Mbps', network: threeG(1.6) },
] as const;
const CPU_SLOWDOWN = 4;
const QUIET_MS = 5_000;
/** How long to watch for a quiet window before reporting that there was none. */
const WATCH_MS = 30_000;
const RUNS = 3;

/** MOB-PERF-01 to 05, 3G column. */
const TARGETS = { fcp: 2_500, lcp: 4_000, tti: 5_000, tbt: 300, cls: 0.1 };

interface Run {
  fcp: number;
  lcp: number;
  tti: number;
  tbt: number;
  cls: number;
  /** What was fetched and when, to find what first paint waits for. */
  waterfall: string[];
}

declare global {
  interface Window {
    __fsPerf: { longTasks: { start: number; end: number }[]; lcp: number; cls: number };
  }
}

async function measure(page: Page, network: Network): Promise<Run> {
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
  await cdp.send('Network.emulateNetworkConditions', network);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: CPU_SLOWDOWN });

  await page.goto('/login');
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible({
    timeout: 30_000,
  });
  // A visible heading can precede the browser's paint record of it; wait for the record itself.
  await page
    .waitForFunction(
      () => performance.getEntriesByName('first-contentful-paint').length > 0,
      null,
      {
        timeout: 30_000,
      },
    )
    .catch(() => undefined);

  // Wait until a whole quiet window has passed after the last long task.
  const { fcp, dcl } = await page.evaluate(() => ({
    fcp: performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? Number.NaN,
    dcl:
      (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)
        ?.domContentLoadedEventEnd ?? 0,
  }));
  // Without a paint there is nothing to wait from; report it rather than wait for the timeout.
  if (Number.isNaN(fcp)) {
    return { fcp, lcp: Number.NaN, tti: Number.NaN, tbt: Number.NaN, cls: 0, waterfall: [] };
  }
  let quiet = false;
  for (;;) {
    const { now, lastEnd } = await page.evaluate(() => ({
      now: performance.now(),
      lastEnd: Math.max(0, ...window.__fsPerf.longTasks.map((t) => t.end)),
    }));
    if (now - Math.max(fcp, lastEnd) >= QUIET_MS) {
      quiet = true;
      break;
    }
    if (now - fcp > WATCH_MS) break;
    await page.waitForTimeout(500);
  }

  const perf = await page.evaluate(() => window.__fsPerf);
  if (!quiet) {
    // No 5 s window without a long task within 30 s: time to interactive is not reached.
    console.log(
      `MOB-PERF no quiet window: fcp ${String(fcp)} ms, ${String(perf.longTasks.length)} long tasks, last ending ${String(Math.max(0, ...perf.longTasks.map((t) => t.end)))} ms`,
    );
  }
  const after = perf.longTasks.filter((t) => t.end > fcp);
  const tti = quiet ? Math.max(fcp, dcl, ...after.map((t) => t.end)) : Number.NaN;
  const tbt = after
    // With no quiet window, every long task after first paint counts.
    .filter((t) => !quiet || t.start < tti)
    .reduce((sum, t) => sum + Math.max(0, t.end - Math.max(t.start, fcp) - 50), 0);
  const waterfall = await page.evaluate(() => {
    const at = (ms: number) => String(Math.round(ms)).padStart(5);
    const navigation = performance.getEntriesByType('navigation')[0] as
      PerformanceNavigationTiming | undefined;
    const lines = [
      `${at(0)} ${at(navigation?.responseEnd ?? 0)} document ${String(navigation?.transferSize ?? 0)} B`,
    ];
    for (const entry of performance.getEntriesByType('resource') as PerformanceResourceTiming[]) {
      lines.push(
        `${at(entry.startTime)} ${at(entry.responseEnd)} ${entry.name.replace(location.origin, '')} ${String(entry.transferSize)} B`,
      );
    }
    return lines;
  });
  await cdp.detach();
  return { fcp, lcp: perf.lcp, tti, tbt, cls: perf.cls, waterfall };
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)] ?? Number.NaN;
}

test.describe('first visit on 3G with a slowed CPU (MOB-PERF-01 to 05)', () => {
  test.setTimeout(180_000);

  for (const { name, network } of CONDITIONS) {
    test(`[MOB-PERF-01, MOB-PERF-02, MOB-PERF-03, MOB-PERF-04, MOB-PERF-05] meets the 3G targets at ${name}`, async ({
      browser,
    }) => {
      const runs: Run[] = [];
      for (let i = 0; i < RUNS; i += 1) {
        // A first visit on a phone: a fresh profile, no service worker, the Moto G4's profile.
        const context = await browser.newContext({
          ...devices['Moto G4'],
          serviceWorkers: 'block',
        });
        runs.push(await measure(await context.newPage(), network));
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
      console.log(`MOB-PERF 3G ${name} medians of ${String(RUNS)}: ${JSON.stringify(result)}`);
      console.log(
        `MOB-PERF 3G ${name} runs: ${JSON.stringify(runs.map((run) => ({ fcp: run.fcp, lcp: run.lcp, tti: run.tti, tbt: run.tbt, cls: run.cls })))}`,
      );
      // The first run's requests, start and end in ms from navigation, to see what paint waits for.
      console.log(
        `MOB-PERF 3G ${name} waterfall (first run):\n${(runs[0]?.waterfall ?? []).join('\n')}`,
      );
      expect.soft(result.fcp, 'MOB-PERF-01 first contentful paint (ms)').toBeLessThan(TARGETS.fcp);
      expect
        .soft(result.lcp, 'MOB-PERF-02 largest contentful paint (ms)')
        .toBeLessThan(TARGETS.lcp);
      expect.soft(result.tti, 'MOB-PERF-03 time to interactive (ms)').toBeLessThan(TARGETS.tti);
      expect.soft(result.tbt, 'MOB-PERF-04 total blocking time (ms)').toBeLessThan(TARGETS.tbt);
      expect.soft(result.cls, 'MOB-PERF-05 cumulative layout shift').toBeLessThan(TARGETS.cls);
    });
  }
});
