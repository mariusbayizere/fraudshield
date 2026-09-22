import { defineConfig, devices } from '@playwright/test';

const PORT = 5391;

/**
 * Browser journeys (M8 gate: Chromium, Firefox and WebKit). Runs against the dev server with the
 * development mocks, because M5–M7 are not merged yet (ADR 0080 §4).
 */
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  workers: 1,
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL: `http://localhost:${String(PORT)}`, trace: 'retain-on-failure' },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: {
    command: `pnpm dev --port ${String(PORT)} --strictPort`,
    port: PORT,
    // Never reuse a server already on the port: it may be running another configuration.
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
