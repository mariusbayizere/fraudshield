import { readFileSync } from 'node:fs';
import { OUTPUT, renderExamples, WITHHELD } from '../../scripts/generate-mock-examples';

describe("the mocks' contract examples (ADR 0080 §4)", () => {
  it('are exactly the examples the contract holds today', () => {
    expect(readFileSync(OUTPUT, 'utf8'), 'run pnpm mock-examples').toBe(renderExamples());
  });

  it('leave out every example with a credential-shaped placeholder (.gitleaks.toml)', () => {
    const committed = readFileSync(OUTPUT, 'utf8');
    expect(committed).not.toMatch(/\b(fsk|whsec)_/);
    for (const schema of WITHHELD) {
      expect(Object.keys(JSON.parse(committed) as object)).not.toContain(schema);
    }
  });
});
