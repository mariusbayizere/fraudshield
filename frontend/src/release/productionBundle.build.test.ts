import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { gzipSync } from 'node:zlib';
import { build } from 'vite';
import { MOCK_MARKER } from '../mocks/handlers';
import { PACKS } from '../region/packs';

const ROOT = join(import.meta.dirname, '../..');
/**
 * D-39 allows 200 KB of initial JavaScript, gzipped. The console holds itself to 170 KB, so a
 * screen cannot spend the last of the budget and leave nothing for the next one (ADR 0080 §10).
 */
const INITIAL_JS_BUDGET = 170 * 1024;
/** No single lazily loaded chunk may exceed this, gzipped (ADR 0080 §10). */
const CHUNK_BUDGET = 120 * 1024;

let out = '';
let hostile = '';
const nodeEnv = process.env['NODE_ENV'];
const files = new Map<string, string>();
const hostileFiles = new Map<string, string>();

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}
const text = (pattern: RegExp, from = files) => [...from].filter(([name]) => pattern.test(name));

async function productionBuild(target: Map<string, string>): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), 'fs-build-'));
  await build({
    root: ROOT,
    configFile: join(ROOT, 'vite.config.ts'),
    mode: 'production',
    logLevel: 'silent',
    build: { outDir: dir, emptyOutDir: true },
  });
  for (const path of walk(dir)) {
    const name = relative(dir, path);
    const readable = /\.(js|css|html|json|webmanifest)$/.test(name);
    target.set(name, readable ? readFileSync(path, 'utf8') : '');
  }
  return dir;
}

beforeAll(async () => {
  // A real production build of the console, for a country named by the packs, not by code.
  process.env['VITE_FS_COUNTRY'] = Object.keys(PACKS)[0];
  // A production-mode build with vitest's NODE_ENV=test still set, which makes DEV true: the
  // mocks must stay out even then (R1 only; React's development build makes its size moot).
  hostile = await productionBuild(hostileFiles);
  // As a release build runs.
  process.env['NODE_ENV'] = 'production';
  out = await productionBuild(files);
}, 600_000);

afterAll(() => {
  process.env['NODE_ENV'] = nodeEnv;
  rmSync(out, { recursive: true, force: true });
  rmSync(hostile, { recursive: true, force: true });
});

/** The entry script and everything index.html preloads with it: what a first visit downloads. */
function initialScripts(): string[] {
  const html = files.get('index.html') ?? '';
  return [
    ...html.matchAll(/<script type="module"[^>]*src="\/([^"]+)"/g),
    ...html.matchAll(/<link rel="modulepreload"[^>]*href="\/([^"]+)"/g),
  ].map((m) => m[1] ?? '');
}

describe('the production build (ADR 0080)', () => {
  it.each([
    ['a release build', files],
    ['a production-mode build with NODE_ENV=test', hostileFiles],
  ])('R1: %s contains no mock service worker, handler or MSW code', (_what, built) => {
    expect(built.size).toBeGreaterThan(0);
    expect([...built.keys()].filter((f) => f.includes('mockServiceWorker'))).toEqual([]);
    for (const [name, content] of text(/\.(js|html)$/, built)) {
      for (const needle of [MOCK_MARKER, 'mockServiceWorker', 'setupWorker', '[MSW]']) {
        expect(content.includes(needle), `${needle} in ${name}`).toBe(false);
      }
    }
  });

  it('H.4: leaves the generated response schemas out', () => {
    for (const [name, content] of text(/\.js$/)) {
      expect(content.includes('tok_[A-Za-z0-9]{24,64}'), name).toBe(false);
    }
  });

  it('R3: self-hosts Inter, Latin and Latin Extended only, and names no font CDN', () => {
    const fonts = [...files.keys()].filter((f) => /\.(woff2?|ttf|otf)$/.test(f));
    expect(fonts.map((f) => f.replace(/-[\w-]{8}\.woff2$/, '')).sort()).toEqual([
      'assets/inter-latin-ext-wght-normal',
      'assets/inter-latin-wght-normal',
    ]);
    for (const [name, content] of text(/\.(js|css|html)$/)) {
      expect(content, name).not.toMatch(/fonts\.(googleapis|gstatic|bunny)\.|use\.typekit/);
    }
  });

  it("R4: has no Tailwind Preflight (D-37), checked against Preflight's own text", () => {
    const preflight = readFileSync(join(ROOT, 'node_modules/tailwindcss/preflight.css'), 'utf8');
    expect(preflight).toContain('::file-selector-button');
    for (const [name, content] of text(/\.css$/)) {
      expect(content.includes('::file-selector-button'), name).toBe(false);
    }
  });

  it('R8: the service worker precaches the shell and caches no API response', () => {
    const sw = files.get('sw.js') ?? '';
    expect(sw).toContain('precacheAndRoute');
    expect(sw.match(/registerRoute\(/g)).toHaveLength(1);
    expect(sw).toMatch(/NavigationRoute\([^)]*\)[^;]*denylist:\[\/\^\\\/api\\\/\/\]/);
  });

  it('D-39: no lazily loaded chunk is larger than its budget, gzipped', () => {
    // The entry and its preloads are measured together, by the initial-bundle budget below.
    const initial = new Set(initialScripts());
    const oversized = [...files]
      .filter(([name]) => name.endsWith('.js') && !initial.has(name))
      .map(([name, content]) => [name, gzipSync(content).length] as const)
      .filter(([, size]) => size > CHUNK_BUDGET)
      .map(([name, size]) => `${name}: ${String(Math.round(size / 1024))} KB`);
    expect(oversized).toEqual([]);
  });

  it('D-39: keeps the initial JavaScript inside the budget, gzipped', () => {
    const initial = initialScripts();
    expect(initial.length).toBeGreaterThan(0);
    const gzipped = initial.reduce((sum, f) => sum + gzipSync(files.get(f) ?? '').length, 0);
    expect(gzipped).toBeLessThanOrEqual(INITIAL_JS_BUDGET);
  });
});
