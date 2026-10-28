# Setup

Two ways to run DevForge AI locally. Both use the real application code; they differ in what
infrastructure sits underneath.

## Requirements

- Java 21
- Maven 3.9+
- Node.js 22.22+, 24.15+ or 26+ (see `engines` in `frontend/package.json`) and npm
- For the full stack only: Docker Engine with Docker Compose

## Option 1: standalone, no Docker (Windows)

```bat
openapp.bat
```

Builds and starts the backend services on ports 9001–9011 with the standalone profile, starts the
frontend on http://localhost:4173 and opens a browser. Everything runs hidden in the background, in
one launcher window, with each service's output in `logs\<service>.log`; closing the window leaves
DevForge running. Stop everything with `stopapp.bat`.

The standalone profile runs real Flyway migrations against an embedded H2 database in `.\data\`,
so no PostgreSQL, Kafka, Redis or SMTP server is needed. What that costs:

- There is no broker, so Kafka consumers are not registered and the notification feed stays empty.
- Email verification links are printed to the auth-service log instead of being sent; the
  development mailbox page in the frontend shows them.
- The JWT signing key is a known development value and refresh cookies are not `Secure`.
  This mode is for development only.

## Option 2: the full stack under Docker Compose

```bash
cp .env.example .env        # local development values; read the comments in it
docker compose up --build
```

Every service runs in its own container with its own PostgreSQL database, real Kafka and Redis,
and nginx in front of the gateway.

| What | URL |
|---|---|
| Frontend | http://localhost:4173 (`FRONTEND_PORT` to change) |
| API gateway | http://localhost:8080 |
| Mailpit (outgoing email) | http://localhost:8025 |

Grafana, Prometheus, Jaeger and Kafka UI are also in `docker-compose.yml`; their ports are listed
there. Backup and restore for this stack are described in `docs/BACKUP.md`.

This stack is exercised in CI on every push; there is no Docker daemon on the machine most of this
repository was developed on, so local compose runs are less well trodden than the CI ones.

## Building and testing

```bash
mvn -B -ntp -f backend/pom.xml test                                   # whole backend
mvn -B -ntp -f backend/pom.xml -pl ../services/auth-service -am test  # one module; -am is required

cd frontend
npm install
npm test && npm run lint && npm run typecheck
npm run test:e2e   # needs the stack already running
```

Always build the backend through `backend/pom.xml`: the shared libraries are not installed to the
local Maven repository, so building a service's own POM tries to fetch them from Maven Central and
fails.

## Things that need credentials

- **Google sign-in** needs `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `.env`.
- **Code execution** needs `DEVFORGE_SANDBOX_ENABLED=true` and a running Docker engine. Read
  `docs/SANDBOX.md` first: it gives deployment-service the power to start containers.
- **AI features** need `ANTHROPIC_API_KEY`; without it the UI says AI assistance is not
  configured, and nothing is simulated in its place.
