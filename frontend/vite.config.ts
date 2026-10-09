import crypto from 'crypto';
import fs from 'fs';
import path from 'path';

import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

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
  const conf = fs.readFileSync(path.resolve(import.meta.dirname, 'security-headers.conf'), 'utf8');
  const headers: Record<string, string> = {};
  for (const match of conf.matchAll(/^add_header\s+(\S+)\s+"([^"]*)"/gm)) {
    headers[match[1]] = match[2];
  }
  return headers;
}

/**
 * `vite preview` behaving as nginx does: the security headers on everything but the API, with a
 * fresh nonce per response in place of nginx's $request_id, and that nonce written into
 * index.html. Static headers alone could not do it — the nonce must differ per response and match
 * the document it was sent with.
 */
function productionPreview(): Plugin {
  return {
    name: 'devforge-production-preview',
    configurePreviewServer(server) {
      const headers = productionHeaders();
      const indexHtml = path.resolve(import.meta.dirname, 'dist', 'index.html');
      server.middlewares.use((req, res, next) => {
        const url = (req.url ?? '/').split('?')[0];
        // API responses carry the gateway's own headers, as they do behind nginx.
        if (url.startsWith('/api/')) return next();

        const nonce = crypto.randomBytes(16).toString('hex');
        for (const [name, value] of Object.entries(headers)) {
          res.setHeader(name, value.replaceAll('$request_id', nonce));
        }
        if (url.startsWith('/assets/') || path.extname(url)) return next();

        // Any other path is a client-side route: the SPA document, as nginx's fallback serves it.
        const html = fs.readFileSync(indexHtml, 'utf8').replaceAll('__CSP_NONCE__', nonce);
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
        res.end(html);
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), productionPreview()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
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
  // Inherits server.proxy, so preview reaches the gateway the same way dev does. Headers come from
  // productionPreview() above.
  preview: {
    port: 4173,
    strictPort: true,
  },
});
