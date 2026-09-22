import { Linter } from 'eslint';
import { hexColour, physicalProperty } from '../eslint-rules/restrictions.js';

const linter = new Linter();
const config: Linter.Config[] = [
  {
    languageOptions: { parserOptions: { ecmaFeatures: { jsx: true } } },
    rules: { 'no-restricted-syntax': ['error', ...hexColour, ...physicalProperty] },
  },
];
const lint = (code: string) => linter.verify(code, config).map((m) => m.message);

describe('the style restrictions ESLint enforces', () => {
  it.each([
    'const sx = { marginLeft: 8 };',
    'const sx = { paddingRight: 4 };',
    'const sx = { borderLeftWidth: 3 };',
    'const sx = { left: 0 };',
    'const sx = { ml: 2 };',
    "const sx = { 'margin-left': 2 };",
    "const sx = { textAlign: 'right' };",
    "const sx = { float: 'left' };",
  ])('rejects the physical property in %s (ADR 0080)', (code) => {
    expect(lint(code)).toEqual([expect.stringContaining('logical property')]);
  });

  it.each([
    'const sx = { marginInlineStart: 8 };',
    'const sx = { borderInlineStartWidth: 3 };',
    'const sx = { insetInlineEnd: 0 };',
    "const sx = { textAlign: 'start' };",
    'const position = { top: 0 };',
  ])('accepts %s', (code) => {
    expect(lint(code)).toEqual([]);
  });

  it.each(["const c = '#D97706';", 'const c = `1px solid #0A2540`;'])(
    'rejects the raw hex colour in %s (D-37)',
    (code) => {
      expect(lint(code)).toEqual([expect.stringContaining('design token')]);
    },
  );
});
