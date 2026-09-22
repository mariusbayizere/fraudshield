import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { defineConfig, type Plugin } from 'vite';
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

const brand = tokens.color.light.brand;

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    mockWorkerInDevelopment(),
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
