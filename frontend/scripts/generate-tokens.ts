// Writes the CSS generated from design-tokens/*.json. Run with `pnpm tokens`; the token test
// fails when the committed output is stale, so a hand edit to either file is caught.
import { writeFileSync } from 'node:fs';
import { renderCssVariables, renderTailwindTheme } from '../src/design-system/css.ts';
import { tokens } from '../src/design-system/tokens.ts';

const out = new URL('../src/design-system/generated/', import.meta.url);
writeFileSync(new URL('tokens.css', out), renderCssVariables(tokens));
writeFileSync(new URL('tailwind-theme.css', out), renderTailwindTheme(tokens));
console.log('wrote src/design-system/generated/tokens.css and tailwind-theme.css');
