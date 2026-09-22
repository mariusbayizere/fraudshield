// @ts-check
import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import storybook from 'eslint-plugin-storybook';
import tseslint from 'typescript-eslint';

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
      // D-37: every colour comes from design tokens. A hex literal in a component is a colour
      // the contrast test has never measured.
      'no-restricted-syntax': [
        'error',
        {
          selector: 'Literal[value=/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/]',
          message: 'Use a design token (palette.* or var(--fs-*)), not a raw hex colour (D-37).',
        },
        {
          selector: 'TemplateElement[value.raw=/#[0-9a-fA-F]{6}\\b/]',
          message: 'Use a design token (palette.* or var(--fs-*)), not a raw hex colour (D-37).',
        },
      ],
    },
  },
  {
    // The token sources, the contrast arithmetic and its tests are where hex values belong.
    files: [
      'src/design-system/tokens.ts',
      'src/design-system/color/**',
      'src/design-system/tokens.test.ts',
    ],
    rules: { 'no-restricted-syntax': 'off' },
  },
);
