import * as schemas from './generated/zod.gen';
import examples from '../mocks/generated/examples.json';

interface Parser {
  safeParse: (data: unknown) => { success: boolean; error?: unknown };
}

// The contract tests validate every example against its schema, so each one is a valid response
// or request. A generated schema that rejects one is wrong, and every check made with it in
// development and tests (validate.ts) would fail on real data.
const cases = Object.entries(examples as Record<string, unknown[]>).flatMap(([name, list]) => {
  const parser = (schemas as Record<string, unknown>)[`z${name}`] as Parser | undefined;
  return parser === undefined
    ? []
    : list.map((example, i) => [`${name}[${String(i)}]`, parser, example] as const);
});

describe('the generated Zod schemas (H.4)', () => {
  it('cover the contract examples', () => {
    expect(cases.length).toBeGreaterThan(50);
  });

  it.each(cases)('accept the contract example %s', (_name, parser, example) => {
    const result = parser.safeParse(example);
    expect(result.success, JSON.stringify(result.error)).toBe(true);
  });
});
