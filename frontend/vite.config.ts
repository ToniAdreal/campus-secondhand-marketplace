import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// vitest runs in node with jsdom; see `npm test`
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
});
