import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      globals: true,
      // Logic tests run in Node; only component tests pay for a DOM.
      projects: [
        {
          extends: true,
          test: {
            name: 'unit',
            environment: 'node',
            include: ['src/**/*.test.ts'],
            exclude: ['src/**/*.build.test.ts'],
          },
        },
        {
          // One production build, checked for what must never ship (ADR 0080).
          extends: true,
          test: {
            name: 'build',
            environment: 'node',
            include: ['src/**/*.build.test.ts'],
            testTimeout: 300_000,
          },
        },
        {
          extends: true,
          test: {
            name: 'components',
            environment: 'jsdom',
            setupFiles: ['src/test/setup.ts'],
            include: ['src/**/*.test.tsx'],
          },
        },
      ],
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          'src/**/*.test.{ts,tsx}',
          'src/**/*.stories.tsx',
          'src/test/**',
          'src/api/generated/**',
          'src/**/*.d.ts',
          // Start-up glue and development-only mocks; the shell they start is tested, and the
          // Playwright journeys run main.tsx itself.
          'src/main.tsx',
          'src/mocks/**',
        ],
        // Enforced front-end thresholds (build prompt E.11).
        thresholds: { lines: 90, functions: 90, branches: 85, statements: 90 },
      },
    },
  }),
);
