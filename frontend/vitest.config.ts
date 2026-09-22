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
          test: { name: 'unit', environment: 'node', include: ['src/**/*.test.ts'] },
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
        exclude: ['src/**/*.test.{ts,tsx}', 'src/**/*.stories.tsx', 'src/test/**'],
        // Enforced front-end thresholds (build prompt E.11).
        thresholds: { lines: 90, functions: 90, branches: 85, statements: 90 },
      },
    },
  }),
);
