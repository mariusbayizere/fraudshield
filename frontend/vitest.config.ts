import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}'],
      // Enforced front-end thresholds (build prompt E.11).
      thresholds: { lines: 90, functions: 90, branches: 85, statements: 90 },
    },
  },
});
