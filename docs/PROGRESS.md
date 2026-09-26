# DevForge AI — Progress

**Last updated:** 2026-09-26
**Phase:** 0 (Foundation Stabilization) — build/test/CI gates complete; config hygiene outstanding.

> Status vocabulary: `IMPLEMENTED`, `PARTIALLY_IMPLEMENTED`, `SCAFFOLDED`, `BROKEN`, `MISSING`.
> Nothing in this file is marked verified unless a command was actually run and its exit code observed.

---

## Build Status

| Gate | Command | Result |
|---|---|---|
| Backend compile | `mvn -B -ntp -f backend/pom.xml clean compile` | **PASS** — all 16 modules |
| Backend tests | `mvn -B -ntp -f backend/pom.xml clean test` | **PASS** — 87 tests, 0 failures |
| Frontend install | `npm ci` (in `frontend/`) | **PASS** |
| Frontend lint | `npm run lint` | **PASS** — 0 errors, 0 warnings |
| Frontend tests | `npm test` | **PASS** — 3 tests |
| Frontend build | `npm run build` | **PASS** |
| YAML validity | js-yaml parse of all 24 YAML files | **PASS** — 0 invalid |
| Docker image builds | `docker build ...` | **UNVERIFIED** — no Docker daemon in this environment |
| Testcontainers tests | — | **UNVERIFIED** — requires Docker |
| CI workflow end-to-end | GitHub Actions | **UNVERIFIED** — not executed here |

Toolchain used: Node 24.18.0, npm 11.16.0, Temurin JDK 21.0.11, Maven 3.9.9. Docker absent.

---

## Completed (this session)

### The repository did not build at all when work started

The single largest finding: a previous mechanical rewrite script (`scripts/fix-dockerfiles.py`,
`scripts/rewrite-dockerfiles.ps1`) had corrupted config files across the repo by writing literal
two-character escape sequences instead of real characters.

| Symptom | Files | Fix |
|---|---|---|
| `<version>0.1.0</version></version>\n    <relativePath>...` — stray closing tag and literal `\n` | 12 of 15 module POMs | Repaired; Maven could not parse these at all |
| `ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]` — escaped quotes, invalid exec form | 14 Dockerfiles | Unescaped |

Repaired via a scripted, reviewed pass (`scratchpad/repair.mjs`); a full backup of all 176 files was
taken first because **this directory is not a git repository** (see Known Issues).

### Backend defects found and fixed

| # | Defect | Resolution |
|---|---|---|
| 1 | `mapstruct:1.6.0.Final` does not exist on Maven Central (versions jump `1.6.0.RC1` → `1.6.0`) | Pinned `1.6.3`, verified against Central metadata |
| 2 | `common-library` imports springdoc and JPA but declared neither | Added both; springdoc compile-scope, JPA `optional` |
| 3 | `AuthService` used `javax.servlet.http` on Spring Boot 3 | Migrated to `jakarta.servlet.http` |
| 4 | `JwtTokenProvider` written against JJWT 0.11 API while POM pins 0.12.6 (`Jwts.claims()`, `setClaims`, `parserBuilder`) | Rewritten for the 0.12 API |
| 5 | `Cookie.setSameSite(...)` does not exist on `jakarta.servlet.http.Cookie` | Switched to Spring `ResponseCookie` + explicit `Set-Cookie` header |
| 6 | springdoc 2.8.17 is built against Boot 3.5; on Boot 3.4.0 it threw `PatternParseException` at startup | Aligned to springdoc 2.7.0 (built against Boot 3.4.0) |
| 7 | `oauth2Login()` called unconditionally → `NoSuchBeanDefinitionException: ClientRegistrationRepository`. **The service could not start in any environment without OAuth credentials** | Made conditional on configured registrations; password login now works standalone |
| 8 | `AuthService.clearRefreshCookie` called by `AuthController` but never defined | Implemented |
| 9 | `spring-boot-maven-plugin` had no managed version (BOM import does not manage plugins) | Added `pluginManagement` to the root POM |
| 10 | `UserPrincipal` used but not imported in `AuthService` | Import added |

### Security improvements (beyond repair)

- **Token-type confusion prevented.** Access and refresh tokens now carry a distinct `typ` claim and
  verification is type-aware. Previously the two were structurally identical, so a stolen 14-day
  refresh token could be presented directly as an API access token.
- **Issuer is now verified** on parse (`requireIssuer`), and every token carries a `jti`.
- **Cookie flags are configuration-driven** (`cookie-secure`, `cookie-same-site`) and default to
  `Secure` + `SameSite=Strict`; only the test profile relaxes them, because tests run over plain HTTP.

### Frontend defects found and fixed

| # | Defect | Resolution |
|---|---|---|
| 1 | `package.json` was **invalid JSON** — unescaped quotes in the `lint` script | Rewritten |
| 2 | `eslint-plugin-jsx-a11y@^7.2.0` — no such major (max 6.10.2). `npm install` could not resolve | → `^6.10.2` |
| 3 | `vitest@^0.36.0` — does not exist (0.34.6 is the last 0.x) | → `^1.6.1`, coherent with Vite 5 |
| 4 | `@typescript-eslint/parser` + plugin required by `.eslintrc.cjs` but absent from `package.json` | Added at `^7.18.0` (ESLint 8 compatible) |
| 5 | `jsdom` missing although `vitest.config.ts` sets `environment: 'jsdom'` | Added |
| 6 | `clsx` / `tailwind-merge` imported by `src/lib/utils.ts` but never declared; `classnames` declared but unused | Swapped |
| 7 | `shadcn-ui@^0.0.1` declared as a runtime dependency (it is a scaffolding CLI) | Removed |
| 8 | No lockfile → `npm ci` in CI could never succeed | `package-lock.json` generated and required |
| 9 | Test used `toBeInTheDocument()` with `setupFiles: []` — matcher never registered | Added `src/tests/setup.ts` |
| 10 | `App.test.tsx` asserted `getByText(/DevForge AI/i)`, which matches 2 nodes → always threw | Rewritten as 3 role-based tests incl. 404 routing |
| 11 | 248 lint errors, all CRLF-vs-LF | Pinned `endOfLine: "lf"`, auto-fixed |

### CI/CD fixed

- `npm ci` now works (lockfile committed).
- `npm test -- --runInBand` was a **Jest** flag passed to Vitest, and bare `vitest` watches forever in
  CI. `npm test` is now `vitest run`.
- Backend step was `package -DskipTests`; now `mvn verify` (surefire + failsafe). Tests are not skipped.
- **Backend Docker builds could never have worked:** each service Dockerfile runs
  `mvn -f backend/pom.xml -pl <module> -am`, which needs the whole repo as context, but CI passed the
  service directory as context. Now built from the repo root with `-f <path>/Dockerfile`.
- Split the awkward `[frontend, backend]` matrix (which installed both toolchains for each) into
  separate `frontend`, `backend`, `docker` and `security` jobs, with dependency caching, test-report
  artifacts, Trivy filesystem and image scanning.

### Testing approach established

`services/auth-service/src/test/resources/application.yml` runs the **real Flyway migrations** against
in-memory H2 in PostgreSQL mode, then sets `ddl-auto: validate` so Hibernate asserts the JPA entity
mappings match the migrated schema. This catches entity/migration drift on every build without
requiring a Docker daemon. (H2 support ships inside `flyway-core` 10.20.1, so no extra dependency.)

---

## Known Issues

| # | Issue | Severity | Notes |
|---|---|---|---|
| 1 | ~~Not a git repository~~ **RESOLVED.** Repo initialised on `main` with `.gitignore` + `.gitattributes`; two commits so far. Note the commits are authored as the machine's global identity (`Rahulb003 <rbhowmik003@gmail.com>`), which may not be intended. | — | Change with `git config user.name` / `user.email` and amend if wrong. |
| 2 | ~~Placeholder secrets in tracked config~~ **RESOLVED** (commit `b49d7f7`). All credentials are environment-driven with no fallbacks outside the `local` profile; `.env.example` added; k8s `secrets.yaml` is now a template. | — | The k8s Secret still needs wiring to a real secret store (External Secrets / Sealed Secrets) before any cluster deploy. |
| 3 | Docker images still **UNVERIFIED**: no Docker daemon here. Images are now non-root with healthchecks and a single shared build stage, but none of it has been executed. | MEDIUM | First run of the `docker` CI job will confirm. |
| 4 | `api-gateway` is a plain `spring-boot-starter-web` app — not Spring Cloud Gateway, no routes, no filters | MEDIUM | Routing is entirely `MISSING`. |
| 5 | Theme-toggle button in `AppLayout.tsx` has no accessible name | LOW | Real a11y defect; `jsx-a11y` does not catch it. |
| 6 | Kafka is entirely absent. Messaging is RabbitMQ (`spring-boot-starter-amqp` in auth-service only) | — | Phase 2, by design not yet started. |
| 7 | 13 of 15 services are health-endpoint skeletons with `placeholder.txt` | — | Expected; Phases 3+. |

---

## Feature Status (honest baseline)

| Area | Status | Evidence |
|---|---|---|
| Backend build/reactor | `IMPLEMENTED` | 16/16 modules compile |
| Auth: entities, repositories, migrations | `IMPLEMENTED` | V1/V2 migrations run; `ddl-auto: validate` passes |
| Auth: JWT issue/verify | `IMPLEMENTED` | Verified end-to-end; token-type confusion covered by tests in both directions |
| Auth: signup/verify/login/refresh/logout/reset | `IMPLEMENTED` | 15 integration tests against the real filter chain and migrated schema. **All five paths were broken before these tests existed** — see commit `093d648` |
| Auth: OAuth | `IMPLEMENTED` | Links on verified email only, provisions new accounts, persists provider identity. **UNVERIFIED end-to-end** — needs real provider credentials |
| Auth: MFA/TOTP | `IMPLEMENTED` | RFC 6238 verified against published vectors; two-step enrolment, recovery codes, throttled |
| Auth: per-device sessions | `IMPLEMENTED` | List/revoke/revoke-others; 7 tests |
| Auth: rate limiting | `IMPLEMENTED` | Per-account rolling window on password and MFA attempts |
| RBAC enforcement | `PARTIALLY_IMPLEMENTED` | Enforced in project-service (org + project roles, server-side, membership-derived). Other services remain skeletons. |
| Multi-tenancy / organizations | `IMPLEMENTED` | Organizations, members, projects, project members; 23 tenant-isolation/IDOR tests |
| API gateway routing | `MISSING` | — |
| Kafka / outbox / event envelope | `MISSING` | — |
| Projects, tasks, sprints, IDE, AI, Git, review, docs, chat, deploy, analytics, RAG, agents | `MISSING` / `SCAFFOLDED` | Health endpoints only |
| Frontend app shell, routing, theme store | `IMPLEMENTED` | 3 passing tests |
| Frontend auth/project/task/IDE screens | `MISSING` | `auth.api.ts` and `user.api.ts` exist but no screens consume them |

---

## Next Task (exact)

1. ~~git init~~ **DONE** (commit `75548a6`).
2. ~~Auth integration tests~~ **DONE** (commit `093d648`, 15 tests).
3. ~~Phase 0 §16 secrets/config~~ **DONE** (commit `b49d7f7`).
4. ~~Phase 0 §15 Dockerfile hardening~~ **DONE** (commit `8e27355`, UNVERIFIED — no Docker here).
5. ~~Phase 1~~ **COMPLETE** (commits `5adfb5b`, `aa6713a`): rotation, throttling, audit trail, MFA/TOTP, per-device sessions, OAuth persistence. OAuth remains UNVERIFIED without provider credentials.
6. ~~RBAC enforcement~~ **DONE** (commit `4d6caa7`): enforced in project-service via membership-derived checks + `@PreAuthorize`. **NEXT:** Phase 2 (Kafka + event envelope + outbox, and the RabbitMQ keep/remove decision), then Phase 4 (tasks/sprints). — `@EnableMethodSecurity` is on but **no endpoint carries an authorization annotation**, so RBAC is currently decorative.
7. Then Phase 2 (Kafka), including the RabbitMQ keep/remove decision required by §18.

---

## Architecture Decisions

| # | Decision | Rationale |
|---|---|---|
| AD-1 | springdoc pinned to **2.7.0**, not latest | 2.7.0's parent is `spring-boot-starter-parent:3.4.0`, matching our Boot version. 2.8.x targets Boot 3.5 and breaks resource-handler pattern parsing at startup. |
| AD-2 | springdoc is compile-scope in `common-library`; JPA is `optional` | `OpenApiConfig` is component-scanned by every consumer, so swagger classes must be present at runtime. `BaseEntity` is a `@MappedSuperclass` that is never scanned, so JPA stays opt-in and infrastructure modules avoid `DataSource` auto-configuration. |
| AD-3 | Tests use H2-in-PostgreSQL-mode running the real migrations, not `create-drop` | Validates migrations *and* entity mappings together, with no Docker dependency. Testcontainers remains the tool for true PostgreSQL behaviour. |
| AD-4 | OAuth2 login is wired conditionally | A platform that cannot boot without third-party credentials is not deployable locally or testable in CI. |
| AD-5 | Access/refresh tokens separated by a `typ` claim | Prevents refresh-token replay as an access token. |
| AD-6 | ESLint kept at 8.x | `.eslintrc.cjs` is eslint-8 format; moving to 9 requires a flat-config rewrite that is not Phase 0 work. |
| AD-7 | Entities with `@UuidGenerator` must not have ids assigned in application code | Assigning one makes Hibernate treat the instance as detached, silently converting `persist()` into `merge()`. This broke signup and login. Entities using a plain assigned `@Id` (the token entities) still set ids explicitly. |
| AD-8 | The catch-all exception handler returns a `traceId`, never the exception message | Echoing `ex.getMessage()` leaked raw Hibernate internals to clients. Details are logged server-side against the same id. |
| AD-9 | The refresh cookie authenticates only at `/api/v1/auth/refresh` | An ambient cookie accepted as a bearer credential on every endpoint, with CSRF disabled, is a CSRF vector. |

---

## Documentation Status

Written: `docs/PROGRESS.md` (this file).

Not yet written — required by §7 and still outstanding: `CLAUDE.md`, `docs/ARCHITECTURE.md`,
`docs/DEVELOPMENT_PLAN.md`, `docs/REQUIREMENTS_MATRIX.md`, `docs/TEST_PLAN.md`,
`docs/THREAT_MODEL.md`, `docs/API_CONTRACTS.md`, `docs/EVENT_CATALOG.md`, `docs/SECURITY.md`,
`docs/TESTING.md`. `README.md` and `docs/Setup.md` exist but have **not** been reviewed against
the repaired build and should be assumed stale.
