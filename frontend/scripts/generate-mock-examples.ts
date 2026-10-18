// Copies the contract's schema examples into the console, for the development mocks and the test
// fixtures (ADR 0080 §4). contracts/tests/test_openapi_examples.py validates every example
// against its schema, so a mock built from them is generated from the contract, not invented.
// Run with `pnpm mock-examples`; src/mocks/examplesDrift.test.ts regenerates and fails on any
// difference from the committed output.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';

export const EXAMPLES = join(import.meta.dirname, '../../contracts/openapi/schema-examples.yaml');
export const OUTPUT = join(import.meta.dirname, '../src/mocks/generated/examples.json');

/**
 * Examples left out: their placeholders have the shape of real credentials (an API key, a webhook
 * signing secret). .gitleaks.toml allows those exact values in the contract's file only, so a
 * copy here would fail the secret scan. A screen that needs one builds its fixture at run time.
 */
export const WITHHELD = new Set(['ApiKeyCreated']);

export function renderExamples(): string {
  const document = parse(readFileSync(EXAMPLES, 'utf8')) as { examples: Record<string, unknown> };
  const kept = Object.fromEntries(
    Object.entries(document.examples).filter(([schema]) => !WITHHELD.has(schema)),
  );
  return `${JSON.stringify(kept, null, 2)}\n`;
}

if (process.argv[1] === import.meta.filename) {
  writeFileSync(OUTPUT, renderExamples());
  console.log(`wrote ${OUTPUT}`);
}
