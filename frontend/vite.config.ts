import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  // The design tokens live at the repository root (design-tokens/), outside this package; the dev
  // server may read them and nothing else above it.
  server: { fs: { allow: ['.', '../design-tokens'] } },
});
