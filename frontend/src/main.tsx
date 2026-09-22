import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { rememberedLanguage } from './app/preferences';
import { createAppRouter } from './app/router';
import { directionOverride } from './i18n/direction';
import { createI18n } from './i18n/i18n';
import { preferredLanguage } from './i18n/languages';
import { regionFor } from './region/packs';
import './styles/app.css';

async function start(root: HTMLElement): Promise<void> {
  // The deployment names its country; there is no default country (ADR 0023).
  const region = regionFor(import.meta.env['VITE_FS_COUNTRY'] as string | undefined);
  const i18n = await createI18n(rememberedLanguage() ?? preferredLanguage(navigator.languages));
  if (import.meta.env.MODE === 'development' && import.meta.env['VITE_FS_MOCKS'] !== 'off') {
    // Development only: this branch, and the mocks with it, do not exist in a production build
    // (ADR 0080 R1). It keys on the build mode, not on DEV: DEV follows NODE_ENV, and a
    // production-mode build run with NODE_ENV=test would otherwise ship the mocks.
    const { startMocks } = await import('./mocks/browser');
    await startMocks();
  }
  createRoot(root).render(
    <StrictMode>
      <App
        i18n={i18n}
        region={region}
        router={createAppRouter()}
        directionOverride={directionOverride(location.search)}
      />
    </StrictMode>,
  );
}

const root = document.getElementById('root');
if (root !== null) {
  start(root).catch((error: unknown) => {
    // Nothing can render without a region and catalogues; say why in plain text.
    root.textContent = `FraudShield could not start: ${error instanceof Error ? error.message : String(error)}`;
    root.setAttribute('role', 'alert');
  });
}
