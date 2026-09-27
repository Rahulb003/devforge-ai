import path from 'path';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * DevForge is several services, so the dev server proxies by path prefix rather
 * than pointing at one backend. In a deployed environment the API gateway does
 * this job; until it exists, the mapping lives here.
 *
 * Keys are matched longest-first by Vite, so the ordering below is for readers,
 * not for correctness.
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 4173,
    strictPort: true,
    proxy: {
      // auth-service
      '/api/v1/auth': {
        target: 'http://localhost:9001',
        changeOrigin: true,
        // Cookies must survive the hop: the refresh token is HttpOnly and is
        // what keeps a session alive across reloads.
        cookieDomainRewrite: 'localhost',
      },
      // auth-service also serves the development mailbox, which only exists when
      // it runs with the log mail provider.
      '/api/v1/dev': {
        target: 'http://localhost:9001',
        changeOrigin: true,
      },
      // task-service. Matched before the project-service rule below because
      // Vite resolves the longest matching prefix, and tasks and sprints are
      // nested under the project route but served by a different service.
      '^/api/v1/organizations/[^/]+/projects/[^/]+/(tasks|sprints)': {
        target: 'http://localhost:9003',
        changeOrigin: true,
      },
      // project-service
      '/api/v1/organizations': {
        target: 'http://localhost:9002',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4173,
  },
});
