// @ts-check
import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import storybook from 'eslint-plugin-storybook';
import tseslint from 'typescript-eslint';
import { hexColour, physicalProperty } from './eslint-rules/restrictions.js';

export default defineConfig(
  { ignores: ['dist/', 'coverage/', 'node_modules/', 'storybook-static/', '!.storybook'] },
  js.configs.recommended,
  tseslint.configs.strictTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  reactHooks.configs.flat['recommended-latest'],
  // FR-04-12: accessible by construction, caught at lint time before axe sees a render.
  jsxA11y.flatConfigs.strict,
  // The plugin types `files` as possibly undefined, which exactOptionalPropertyTypes rejects;
  // the value itself is a valid flat config.
  /** @type {import('eslint').Linter.Config[]} */ (
    /** @type {unknown} */ (storybook.configs['flat/recommended'])
  ),
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      // Build prompt H.4: no `any`, no unexplained non-null assertions.
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-non-null-assertion': 'error',
      // D-37 (no raw hex colours) and ADR 0080 (logical properties only), with their own tests
      // in src/lint.test.ts.
      'no-restricted-syntax': ['error', ...hexColour, ...physicalProperty],
    },
  },
  {
    // The token sources, the contrast arithmetic and the tests of both rules are where hex
    // values and physical properties belong; physical properties stay banned there.
    files: [
      'src/design-system/tokens.ts',
      'src/design-system/color/**',
      'src/design-system/tokens.test.ts',
      'src/lint.test.ts',
    ],
    rules: { 'no-restricted-syntax': ['error', ...physicalProperty] },
  },
  {
    files: ['src/lint.test.ts'],
    rules: { 'no-restricted-syntax': 'off' },
  },
);
