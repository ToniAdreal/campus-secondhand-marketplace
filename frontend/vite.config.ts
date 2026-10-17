import react from '@vitejs/plugin-react';
import { configDefaults, defineConfig } from 'vitest/config';

// vitest runs in node with jsdom; see `npm test`
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  // The Playwright e2e smoke (backlog #181) serves the BUILT SPA through
  // `vite preview`, so preview needs the same /api proxy shape as the dev
  // server (and as nginx in the compose stack) — without it every API call
  // from the previewed bundle 404s at the static server.
  preview: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    // Playwright specs live in e2e/ and run under their own runner
    // (`npm run e2e`, CI only); vitest must never collect them.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
