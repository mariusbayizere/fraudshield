import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { generateApi, OUTPUT } from '../../scripts/generate-api';

function tree(root: string, dir = root): Map<string, string> {
  const files = new Map<string, string>();
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) for (const [k, v] of tree(root, path)) files.set(k, v);
    else files.set(relative(root, path), readFileSync(path, 'utf8'));
  }
  return files;
}

describe('the generated API client (ADR 0080 R2)', () => {
  it('is exactly what the contract generates today', async () => {
    const fresh = mkdtempSync(join(tmpdir(), 'fs-api-'));
    try {
      await generateApi(fresh);
      const expected = tree(fresh);
      const committed = tree(OUTPUT);
      expect([...committed.keys()].sort()).toEqual([...expected.keys()].sort());
      for (const [file, content] of expected) {
        expect(committed.get(file), `${file} differs; run pnpm api`).toBe(content);
      }
    } finally {
      rmSync(fresh, { recursive: true, force: true });
    }
  }, 60_000);
});
