import { defineConfig, devices } from '@playwright/test';

const PORT = 5392;

/**
 * MOB-PERF-01 to 05 measured on a production build, not the dev server (ADR 0080 §10, amendment
 * 2026-10-19). Chromium only: network and CPU throttling go through the DevTools protocol.
 */
export default defineConfig({
  testDir: 'e2e',
  testMatch: 'performance.spec.ts',
  forbidOnly: !!process.env['CI'],
  // A timing that fails is a finding, not a flake: no retries.
  retries: 0,
  workers: 1,
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL: `http://localhost:${String(PORT)}` },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm exec vite preview --port ${String(PORT)} --strictPort`,
    port: PORT,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
