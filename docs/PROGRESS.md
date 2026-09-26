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
| Backend tests | `mvn -B -ntp -f backend/pom.xml clean test` | **PASS** — 1 test, 0 failures |
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
| 1 | **The working directory is not a git repository.** `git status`/`git log` fail. There is no version control, no history, and no rollback. | **HIGH** | Every change so far is backed up to the session scratchpad. `git init` is strongly recommended before further work — this is a decision for the repo owner, so it has not been done unilaterally. |
| 2 | Placeholder secrets remain production-shaped: `changeme`, `smtp.example.com`, `change-this-to-a-very-secure-secret-key...` in `services/auth-service/src/main/resources/application.yml` and `infrastructure/kubernetes/secrets.yaml` | **HIGH** | Phase 0 §16 work, not yet done. No `.env.example` exists. |
| 3 | Docker images unbuildable/unverifiable here; Dockerfiles still run as **root**, have no `HEALTHCHECK`, and each rebuilds the entire reactor | MEDIUM | Phase 0 §15 hardening outstanding. |
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
| Auth: JWT issue/verify | `PARTIALLY_IMPLEMENTED` | Compiles and is type-safe; **no test exercises an actual login round-trip yet** |
| Auth: signup/login/refresh/reset endpoints | `PARTIALLY_IMPLEMENTED` | Controllers exist; behaviour unverified by tests |
| Auth: OAuth | `SCAFFOLDED` | `CustomOAuth2UserService` delegates to the default and persists nothing |
| Auth: MFA/TOTP, session management, rate limiting, account lockout | `MISSING` | No code |
| RBAC enforcement | `SCAFFOLDED` | Roles exist; `@EnableMethodSecurity` on, but no `@PreAuthorize` anywhere |
| Multi-tenancy / organizations | `MISSING` | No `Organization` entity |
| API gateway routing | `MISSING` | — |
| Kafka / outbox / event envelope | `MISSING` | — |
| Projects, tasks, sprints, IDE, AI, Git, review, docs, chat, deploy, analytics, RAG, agents | `MISSING` / `SCAFFOLDED` | Health endpoints only |
| Frontend app shell, routing, theme store | `IMPLEMENTED` | 3 passing tests |
| Frontend auth/project/task/IDE screens | `MISSING` | `auth.api.ts` and `user.api.ts` exist but no screens consume them |

---

## Next Task (exact)

1. `git init` + initial commit (**needs owner's go-ahead**; see Known Issue 1) so subsequent phases have rollback.
2. Finish Phase 0 §16: add `.env.example`, externalise every secret, remove placeholder production values, and split `local`/`dev`/`test`/`staging`/`prod` profiles.
3. Finish Phase 0 §15: harden Dockerfiles — non-root user, `HEALTHCHECK`, and a shared build stage so 14 images do not each rebuild the whole reactor.
4. Write a real auth integration test (signup → verify → login → refresh → logout) against the H2 profile before extending auth further. Phase 1 should not be declared complete on compile-only evidence.
5. Then Phase 2 (Kafka), including the RabbitMQ keep/remove decision required by §18.

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

---

## Documentation Status

Written: `docs/PROGRESS.md` (this file).

Not yet written — required by §7 and still outstanding: `CLAUDE.md`, `docs/ARCHITECTURE.md`,
`docs/DEVELOPMENT_PLAN.md`, `docs/REQUIREMENTS_MATRIX.md`, `docs/TEST_PLAN.md`,
`docs/THREAT_MODEL.md`, `docs/API_CONTRACTS.md`, `docs/EVENT_CATALOG.md`, `docs/SECURITY.md`,
`docs/TESTING.md`. `README.md` and `docs/Setup.md` exist but have **not** been reviewed against
the repaired build and should be assumed stale.
