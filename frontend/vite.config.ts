import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { defineConfig, type Plugin, type Rollup } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';
import tokens from '../design-tokens/tokens.json' with { type: 'json' };

/**
 * Serves MSW's service worker from the development server only. It is never copied into
 * public/, so a production build cannot contain it (ADR 0080 R1).
 */
function mockWorkerInDevelopment(): Plugin {
  const worker = createRequire(import.meta.url).resolve('msw/mockServiceWorker.js');
  return {
    name: 'fraudshield:mock-worker-in-development',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/mockServiceWorker.js', (_request, response) => {
        response.setHeader('Content-Type', 'text/javascript');
        response.end(readFileSync(worker));
      });
    },
  };
}

/**
 * What a first visit needs, fetched with the entry instead of after it (MOB-PERF-01, ADR 0080
 * §10, amendment 2026-10-19). A first visit has no session, so it lands on sign-in: its route chunk
 * and everything that chunk imports are preloaded, with English's start-up and sign-in catalogues.
 * English is the fallback language, so every visit needs them; another language's catalogues are
 * fetched once the browser has chosen it, one round trip later. Without this, each was a further
 * round trip after the entry had run: 3.3 to 3.6 s to first paint on 3G against 2.5 s, 2.9 s with.
 * The bundle test counts every preload as initial JavaScript, so these bytes are in its budget.
 *
 * Every target must match a chunk, or the build fails: a preload that silently stops matching
 * would bring back the chain of round trips it removed.
 */
export const FIRST_VISIT = [
  /\/src\/auth\/LoginPage\.tsx$/,
  /\/src\/i18n\/locales\/en\/common\.json$/,
  /\/src\/i18n\/locales\/en\/designSystem\.json$/,
  /\/src\/i18n\/locales\/en\/auth\.json$/,
];

function firstVisitPreload(): Plugin {
  let base = '/';
  return {
    name: 'fraudshield:first-visit-preload',
    // The console's own build only: Storybook builds with this config too, and its iframe page
    // has neither the sign-in route nor catalogue chunks to preload.
    apply: (_config, { command }) => command === 'build' && process.env['STORYBOOK'] !== 'true',
    configResolved(config) {
      base = config.base;
    },
    transformIndexHtml: {
      order: 'post',
      handler(html, context) {
        const bundle: Rollup.OutputBundle | undefined = context.bundle;
        // The console's page only, never another HTML entry.
        if (bundle === undefined || context.path !== '/index.html') return html;
        const chunks = Object.values(bundle).filter(
          (item): item is Rollup.OutputChunk => item.type === 'chunk',
        );
        const byName = new Map(chunks.map((chunk) => [chunk.fileName, chunk]));
        const wanted = new Set<string>();
        const visit = (fileName: string) => {
          if (wanted.has(fileName)) return;
          wanted.add(fileName);
          for (const imported of byName.get(fileName)?.imports ?? []) visit(imported);
        };
        for (const target of FIRST_VISIT) {
          const found = chunks.filter((chunk) => target.test(chunk.facadeModuleId ?? ''));
          if (found.length === 0) {
            throw new Error(
              `first-visit preload: no chunk for ${String(target)}; see vite.config.ts`,
            );
          }
          for (const chunk of found) visit(chunk.fileName);
        }
        const links = [...wanted]
          .filter((fileName) => !html.includes(`${base}${fileName}"`))
          .sort()
          .map((fileName) => `    <link rel="modulepreload" crossorigin href="${base}${fileName}">`)
          .join('\n');
        return links === '' ? html : html.replace('</head>', `${links}\n  </head>`);
      },
    },
  };
}

const brand = tokens.color.light.brand;

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    mockWorkerInDevelopment(),
    firstVisitPreload(),
    // Storybook builds with this config and sets STORYBOOK=true. A component catalogue needs no
    // service worker, and Workbox fails on Storybook's own manager bundle, which is over its
    // precache limit.
    ...(process.env['STORYBOOK'] === 'true'
      ? []
      : [
          VitePWA({
            registerType: 'prompt',
            injectRegister: 'script-defer',
            manifest: {
              name: 'FraudShield',
              short_name: 'FraudShield',
              description: 'FraudShield staff console',
              start_url: '/',
              display: 'standalone',
              theme_color: brand.navy,
              background_color: tokens.color.light.surface.background,
              icons: [
                { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
                { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
                {
                  src: '/icon-maskable-512.png',
                  sizes: '512x512',
                  type: 'image/png',
                  purpose: 'maskable',
                },
                { src: '/icon.svg', sizes: 'any', type: 'image/svg+xml' },
              ],
            },
            workbox: {
              // The app shell only. API responses are never cached here: offline data is D-29's
              // masked, encrypted, expiring cache, not the service worker's (ADR 0080 §5, R8).
              globPatterns: ['**/*.{js,css,html,woff2,svg,png}'],
              navigateFallback: 'index.html',
              navigateFallbackDenylist: [/^\/api\//],
              runtimeCaching: [],
            },
          }),
        ]),
  ],
  // The design tokens live at the repository root, outside this package; the dev server may read
  // them and nothing else above it. (Country packs are generated into src/region/generated/.)
  server: { fs: { allow: ['.', '../design-tokens'] } },
});
