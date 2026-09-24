// Playwright configuration for the five SRS analyst journeys (TEST-12), run against a deployed
// FraudShield instance.
//
// NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010). There is no
// deployed instance to run against: M6, M7, M8 and M9 are unmerged, and every console route on
// m8/frontend still renders its "not built yet" placeholder.
//
// This config deliberately does not start a dev server. M8's own suite (frontend/e2e) runs the
// console against Vite with mocked APIs, which is the right thing for a component milestone and
// the wrong thing for a verification campaign: a journey that passes against a mock proves the
// console renders, not that the system decides. FS_E2E_BASE_URL points at the deployed console.
//
// Run (after M8 merges, using the Playwright that ships with the frontend package):
//   FS_E2E_BASE_URL=https://<console-host> \
//   pnpm --dir frontend exec playwright test --config ../tests/e2e/playwright.config.ts
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env['FS_E2E_BASE_URL'] ?? 'http://localhost:5391';

// The device matrix of SRS A.8. Desktop browsers carry the gate (Chromium, Firefox and WebKit);
// the emulated devices carry the rows about small screens and touch.
export default defineConfig({
  testDir: '.',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    // A.8: a low-cost Android handset, a small iPhone and a tablet. The journeys each name the
    // rows they cover on these; the matrix row itself is MOB-DEV-01 to MOB-DEV-07.
    { name: 'android-phone', use: { ...devices['Pixel 5'] } },
    { name: 'iphone-se', use: { ...devices['iPhone SE'] } },
    { name: 'ipad-mini', use: { ...devices['iPad Mini'] } },
  ],
});
