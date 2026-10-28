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
    coverage: {
      provider: 'v8',
      reporter: ['text'],
      // SPA source only: test files themselves are not production code,
      // and scripts/ + e2e/ run under their own runners, not vitest.
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}'],
      // Frontend coverage gate (backlog #247, mirroring the backend
      // JaCoCo >= 80% gate from #28 and the #165 ratchet discipline):
      // measured 2026-10-28 on head d6f294b (80 files, 970/970 tests):
      // statements 93.32, branches 89.29, functions 90.32, lines 93.32.
      // Each threshold below is that measurement floored to a whole
      // percent so an identical rerun cannot fail on rounding. Raise
      // them only on a real measured gain — never set a threshold the
      // tree cannot already meet.
      thresholds: {
        lines: 93,
        functions: 90,
        branches: 89,
        statements: 93,
      },
    },
  },
});
