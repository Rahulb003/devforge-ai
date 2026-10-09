import fs from 'fs';
import path from 'path';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * Everything goes through the API gateway.
 *
 * The dev server used to route by path prefix to three separate services. That
 * worked only because Vite was doing the routing, so nothing deployed behaved
 * the same way — and the mapping had to be kept in step by hand every time a
 * service gained a route. The gateway now owns that mapping, and this proxy
 * exists purely to keep the browser on one origin so the HttpOnly refresh
 * cookie is sent.
 *
 * Set VITE_GATEWAY_URL to point at a gateway somewhere other than localhost.
 */

/**
 * The production nginx headers, read from the file nginx itself includes, so `vite preview` serves
 * the built app under exactly the policy it will run under. The browser suite runs against preview
 * in CI; a CSP that breaks the app fails there rather than in production.
 */
function productionHeaders(): Record<string, string> {
  const conf = fs.readFileSync(path.resolve(__dirname, 'security-headers.conf'), 'utf8');
  const headers: Record<string, string> = {};
  for (const match of conf.matchAll(/^add_header\s+(\S+)\s+"([^"]*)"/gm)) {
    headers[match[1]] = match[2];
  }
  return headers;
}

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
      '/api': {
        target: process.env.VITE_GATEWAY_URL ?? 'http://localhost:8080',
        changeOrigin: true,
        // The refresh token is HttpOnly and is what keeps a session alive
        // across reloads, so the cookie must survive the hop.
        cookieDomainRewrite: 'localhost',
      },
    },
  },
  // Inherits server.proxy, so preview reaches the gateway the same way dev does.
  preview: {
    port: 4173,
    strictPort: true,
    headers: productionHeaders(),
  },
});
