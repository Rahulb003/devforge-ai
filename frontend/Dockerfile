# ---------------------------------------------------------------------------
# DevForge AI frontend: Vite build served by nginx.
# Build context is frontend/ — this image is self-contained.
# ---------------------------------------------------------------------------

FROM node:24-alpine AS build
WORKDIR /app

# Lockfile first so dependency installs cache independently of source edits.
COPY package.json package-lock.json ./
# `npm ci` installs exactly the locked tree and fails if package.json and the
# lockfile disagree.
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .

# VITE_* values are inlined into the bundle at build time and are therefore
# PUBLIC. Never pass a secret this way.
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

# --- Runtime ---------------------------------------------------------------
# nginx-unprivileged runs as a non-root user and listens on 8080, so the
# container needs no privileged port bind. The stock nginx image runs its
# master process as root.
# Stable nginx on a pinned, current Alpine. 1.27-alpine sat on Alpine 3.21 with 43 high/critical
# OS-package advisories, found the first time CI scanned this image.
FROM nginxinc/nginx-unprivileged:1.30-alpine3.24 AS runtime

# Apply Alpine's security updates at build time instead of waiting for a new base image: even the
# current tag shipped libtiff 4.7.1 with a high advisory fixed in 4.7.2. The image switches back to
# the unprivileged user below.
USER root
RUN apk upgrade --no-cache

# Single-page app: unknown paths must fall through to index.html or a deep link
# like /projects/42 returns 404 from nginx before React Router ever sees it.
# A template: the image's entrypoint substitutes environment variables into it at start and
# writes /etc/nginx/conf.d/default.conf. Only variables that are set are substituted, so nginx's
# own $uri and $host are left alone.
COPY --chown=nginx:nginx nginx.conf /etc/nginx/templates/default.conf.template
# Where /api is proxied. The compose and Kubernetes service name; override per environment.
ENV API_GATEWAY_UPSTREAM=http://api-gateway:8080
COPY --chown=nginx:nginx security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build --chown=nginx:nginx /app/dist /usr/share/nginx/html

USER nginx
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
