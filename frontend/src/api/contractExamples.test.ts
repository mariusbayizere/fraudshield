import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';
import * as schemas from './generated/zod.gen';

interface Parser {
  safeParse: (data: unknown) => { success: boolean; error?: unknown };
}

// contracts/tests/test_openapi_examples.py validates every example in schema-examples.yaml
// against its schema, so each one is a valid request or response. A generated schema that
// rejects one is wrong, and every check made with it in development and tests (validate.ts) would
// fail on real data. Read from the contract itself, so the examples the mocks withhold
// (ApiKeyCreated) are covered too. The inline examples in fraudshield-api.yaml are not covered.
const file = join(import.meta.dirname, '../../../contracts/openapi/schema-examples.yaml');
const examples = (parse(readFileSync(file, 'utf8')) as { examples: Record<string, unknown[]> })
  .examples;
const named = Object.entries(examples);
const parsers = schemas as unknown as Record<string, Parser | undefined>;
const cases = named.flatMap(([name, list]) =>
  list.map((example, i) => [`${name}[${String(i)}]`, parsers[`z${name}`], example] as const),
);

describe('the generated Zod schemas (H.4)', () => {
  it('have a schema for every example in the contract', () => {
    expect(
      named.filter(([name]) => parsers[`z${name}`] === undefined).map(([name]) => name),
    ).toEqual([]);
    expect(cases).toHaveLength(named.reduce((sum, [, list]) => sum + list.length, 0));
  });

  it.each(cases)('accept the contract example %s', (_name, parser, example) => {
    const result = parser?.safeParse(example);
    expect(result?.success, JSON.stringify(result?.error)).toBe(true);
  });
});
