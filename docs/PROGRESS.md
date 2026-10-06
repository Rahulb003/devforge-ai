# DevForge AI — Progress

**Last updated:** 2026-10-02
**Phase:** 4 complete (tasks/Kanban/sprints). Phases 0–4 plus the api-gateway are in place;
Phase 2's event backbone is written but has never run against a real broker.

> Status vocabulary: `IMPLEMENTED`, `PARTIALLY_IMPLEMENTED`, `SCAFFOLDED`, `BROKEN`, `MISSING`.
> Nothing in this file is marked verified unless a command was actually run and its exit code observed.

---

## Build Status

| Gate | Command | Result |
|---|---|---|
| Backend compile | `mvn -B -ntp -f backend/pom.xml clean compile` | **PASS** — all 16 modules |
| Backend tests | `mvn -B -ntp -f backend/pom.xml clean test` | **PASS** — 412 tests, 0 failures |
| Frontend install | `npm ci` (in `frontend/`) | **PASS** |
| Frontend lint | `npm run lint` | **PASS** — 0 errors, 0 warnings |
| Frontend tests | `npm test` | **PASS** — 64 unit tests |
| Frontend build | `npm run build` | **PASS** |
| End-to-end tests | `npm run test:e2e` (Playwright, stack running) | **PASS** — 28 tests, through the gateway. Needs a machine not otherwise loaded; see docs/TESTING.md |
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
| 5 | ~~Theme toggle had no accessible name~~ **RESOLVED** (commit `33a649f`). | — | Icon-only controls now all carry accessible names. |
| 6 | ~~Kafka absent / RabbitMQ unused~~ **RESOLVED** (commit `0a2f97d`). RabbitMQ removed; Kafka + outbox + idempotency implemented. **Broker itself UNVERIFIED** (no Docker), and Kafka has no TLS/SASL/ACLs configured yet. | MEDIUM | Production needs broker auth before deploy. |
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
| API gateway routing | `IMPLEMENTED` | Single entry point on 8080; routes auth, organizations/projects and the nested task/sprint paths. Verified live: signup 201, `/auth/me` 200, create org, create project, create task — all through the gateway |
| Correlation ids | `IMPLEMENTED` | Gateway generates one per request, reuses a valid inbound id, replaces an unsafe one; 6 tests |
| CORS | `IMPLEMENTED` | Explicit origin allow-list; a wildcard now fails startup rather than being silently echoed back. 5 tests |
| Kafka / outbox / event envelope | `IMPLEMENTED` | Envelope, outbox, idempotency, DLQ; 10 staging tests **plus 9 against a real in-process broker** — publication, ordering, dedup on redelivery, dead-lettering, and a poison event not blocking its partition. `SKIP LOCKED` still UNVERIFIED (needs PostgreSQL); see docs/EVENT_CATALOG.md §8 |
| Kafka consumers in services | `IMPLEMENTED` | notification-service consumes identity, security and task events in group `notification-service`. The platform's first production consumer, so events now drive behaviour rather than accumulating unread |
| Notifications (§12) | `IMPLEMENTED` | Consumer, per-recipient storage, read/unread/delete API, bell with unread badge and a feed page. 22 backend tests (8 against a real broker) + 12 frontend. Verified live through the gateway |
| Kafka TLS / SASL / ACLs | `MISSING` | Not configured |
| Tasks, Kanban, sprints, comments, labels | `IMPLEMENTED` | task-service, 29 tests. Authorization delegated to project-service |
| Git hosting (§7) | `IMPLEMENTED` | git-service hosts real repositories via JGit: create, browse, commit, branch, diff. 107 tests. Verified live through the gateway |
| GitHub/GitLab integration | `MISSING` | Deliberately separate from the above — it needs provider credentials, and faking it was not an option |
| Code review and quality gates (§8) | `IMPLEMENTED` | review-service: secret detection, credential files, merge-conflict markers, dangerous patterns; severities, a configurable gate, and dismissal with a recorded reason. 83 tests. Verified live against a repository with a planted credential |
| Documentation generation (§10) | `IMPLEMENTED` | documentation-service: repository overview, API surface and doc-coverage documents generated from real content. 31 tests. Not AI-written — every statement is derived from files that exist |
| Code-execution sandbox (§37) | `MISSING` | **Designed, deliberately not built** — see docs/SANDBOX.md. No container, VM or hypervisor is available here, and a sandbox that cannot isolate is worse than none because people trust it |
| IDE, AI, chat, deploy, analytics, RAG, agents | `MISSING` / `SCAFFOLDED` | Health endpoints only — 4 services remain 2-file scaffolds |
| Frontend app shell, routing, theme store | `IMPLEMENTED` | 12 passing tests |
| Frontend auth screens (login/MFA/signup/verify/forgot/reset) | `IMPLEMENTED` | Driven against the live API; verified end-to-end through the dev proxy |
| Frontend organization + project screens | `IMPLEMENTED` | List/create, loading/empty/error states |
| Frontend Kanban board | `IMPLEMENTED` | Board, columns, task create/move; reachable by clicking from a project |
| Frontend notifications | `IMPLEMENTED` | Bell with unread badge, feed page, read/unread/delete, filter. 12 tests |
| Frontend documentation | `IMPLEMENTED` | Generate, tab per document, Markdown rendered as text. Reachable from a repository. 8 tests + 2 e2e |
| Frontend code review | `IMPLEMENTED` | Gate result, severity counts, findings with redacted snippets, and dismissal with a required reason. Reachable from a repository. 10 tests + 4 e2e |
| Frontend code browser | `IMPLEMENTED` | Repository list and create, file tree, file contents with line numbers, commit history, branch switching, and a commit form so a new repository is not a dead end. 20 tests + 6 e2e |
| Frontend IDE/AI screens | `MISSING` | Phases 5+ |
| End-to-end browser tests | `IMPLEMENTED` | 22 Playwright tests through the gateway; `npm run test:e2e` → 22 passed |

---

## Next Task (exact)

1. ~~git init~~ **DONE** (commit `75548a6`).
2. ~~Auth integration tests~~ **DONE** (commit `093d648`, 15 tests).
3. ~~Phase 0 §16 secrets/config~~ **DONE** (commit `b49d7f7`).
4. ~~Phase 0 §15 Dockerfile hardening~~ **DONE** (commit `8e27355`, UNVERIFIED — no Docker here).
5. ~~Phase 1~~ **COMPLETE** (commits `5adfb5b`, `aa6713a`): rotation, throttling, audit trail, MFA/TOTP, per-device sessions, OAuth persistence. OAuth remains UNVERIFIED without provider credentials.
6. ~~RBAC enforcement~~ **DONE** (commit `4d6caa7`): enforced in project-service via membership-derived checks + `@PreAuthorize`. ~~Phase 2~~ **DONE** (commit `0a2f97d`). ~~Phase 4~~ **DONE** (commit `03c07b0`). ~~Kanban board UI~~ **DONE** (commit `030bf70`).
7. ~~api-gateway~~ **DONE**: routes, correlation ids, CORS allow-list. The frontend now talks to one origin and the Vite proxy no longer duplicates the service map.
8. ~~Verify the event backbone against a real broker~~ **DONE**: 9 integration tests on an
   in-process Kafka prove publication, ordering, deduplication on redelivery and dead-lettering.
   The §18 RabbitMQ decision was already recorded (removed; `docs/EVENT_CATALOG.md` §1), and the
   two READMEs that still advertised it have been corrected.
9. ~~The first production consumer~~ **DONE**: notification-service consumes identity, security
   and task events and turns them into per-recipient notifications, with a bell and feed in the UI.
   Two defects in task-service's event publishing were found and fixed on the way — `actorId` was
   the reporter rather than the acting user, and `TaskCompleted` carried no `assigneeId`.
10. **NEXT:** the §7 documentation set, which is 2 of 11 files written. `CLAUDE.md` and
   `ARCHITECTURE.md` matter most: the architectural decisions are currently recorded only in this
   file's AD table and in code comments.
11. ~~git-service~~ **DONE**: real repositories via JGit, with browse, commit, branch and diff.
    Found and fixed a Windows portability bug on the way — git writes loose objects read-only,
    and Windows refuses to delete a read-only file, so repository deletion half-succeeded with
    only a warning.
12. ~~A frontend code browser over git-service~~ **DONE**: repositories are now reachable by
    clicking, including committing a file, so the domain is usable end to end.
    **This is also where the CORS regression was caught** — see AD-23. The application had been
    returning 403 to every browser request since the gateway commit, while every `curl` check
    passed, because `curl` sends no `Origin` header.
13. ~~review-service~~ **DONE**: real static analysis over real repository content, with a
    quality gate. Chosen ahead of the sandbox because reading code is not executing it, so §8
    needed no sandbox — and it establishes how findings are modelled before AI review reuses
    that shape.
14. ~~§37 sandbox~~ **DESIGNED, NOT BUILT** — `docs/SANDBOX.md` specifies the twelve guarantees
    and the escape-attempt suite any implementation must pass. It cannot be built here: there is
    no container runtime, VM or hypervisor (checked), and a sandbox that cannot isolate is worse
    than none because people trust it.
15. **NEXT:** a frontend surface for reviews, so a gate result is visible beside a repository
    rather than only over the API. Then documentation-service (§10), which needs no sandbox
    either.
16. Then project-service should stage the events its `EventTypes` constants already declare, so
    "you were added to a project" becomes possible.

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
| AD-10 | task-service delegates project authorization to project-service, forwarding the **caller's own** token | Replicating membership would create a second source of truth, and a stale replica in an authorization path is a cross-tenant leak waiting to happen. A service credential would instead grant task-service blanket access to every project. The call fails closed: an unreachable authority yields 503, never access. |
| AD-11 | The gateway uses `spring-cloud-starter-gateway-mvc`, not the reactive gateway | `common-library` is on every service's classpath and brings `spring-boot-starter-web`. Mixing that with the reactive gateway leaves Boot unable to decide which web stack to start. |
| AD-12 | Task and sprint routes are declared **before** the organizations route | Those paths are nested under the project path but served by task-service. Declared after the broader `/api/v1/organizations/**` route they would never match. |
| AD-13 | CORS takes an explicit origin list; `*` throws at startup | `addAllowedOriginPattern("*")` with `allowCredentials(true)` makes Spring echo the caller's Origin back, sidestepping the browser's wildcard-with-credentials rule. Any site could then read a signed-in user's data. Failing to boot is the correct response to that configuration. |
| AD-14 | The event backbone is verified with an **in-process Kafka**, not Testcontainers | It is a real broker, so the producer config, serialisation, error handler and DLT recoverer are genuinely exercised with no Docker daemon available. Testcontainers stays the right tool for broker failover and multi-replica behaviour — `acks=all` exists to survive a leader change, which a single embedded broker cannot demonstrate. |
| AD-15 | Each broker test publishes to its own topic | Kafka topics are append-only and there is no per-test truncation. A fresh consumer group reading from `earliest` on a shared topic replays what earlier tests left behind, so "exactly one event arrived" becomes a false pass. This was a real failure during development, not a hypothetical. |
| AD-16 | Notifications are one row per recipient, never a shared row with a recipient list | Read state is per-person, so a shared row needs a join table that everyone who reads mutates, and authorization stops being a single column comparison. One row each keeps the authorization check to `recipient_id = token subject`. |
| AD-17 | No notification endpoint accepts a user id | The recipient comes from the verified token. An endpoint like `/users/{id}/notifications` makes the id something the client sends, and then every method has to remember to check it — the exact shape of the IDOR bug §32 asks to be tested for. A notification belonging to someone else returns 404, not 403, so the API is not an oracle for enumerating ids. |
| AD-23 | Only the gateway applies a CORS policy; services behind it register no CORS filter | An empty allow-list makes `CorsFilter` **reject** anything carrying an `Origin` header, which is what a gateway forwards — not "allow no cross-origin access". The first CORS hardening left every service with an empty list, so the whole app answered 403 to browsers while passing every `curl` check, because `curl` sends no `Origin`. Omitting the filter is safer: CORS only ever *grants* access. |
| AD-24 | The repository browser keeps path, ref and open file in the **query string** | A link to a file is then one someone else can open, Back walks up the tree, and a reload lands in the same place. Component state would make all three fail. |
| AD-30 | `GitContentClient` and `RepositoryFile` live in common-library | review-service and documentation-service both read repository content. The parts worth getting right — the fetch bounds and failing closed when content is unreadable — are exactly the parts that rot when copied. Conditional on `devforge.services.git-service-url`, so services that never read content do not fail to start for want of a property they have no reason to set. |
| AD-32 | Generated documents are rendered as preformatted text, never parsed to HTML | The content is derived from repository files, which are attacker-supplied. A crafted README reaches the page through the overview document, so rendering it as HTML would turn it into stored XSS. Plain text cannot. |
| AD-31 | A generator produces no document rather than an empty one | A document that says nothing is indistinguishable from one whose generator failed. Absence is honest; an empty page is not. |
| AD-25 | review-service asks git-service for content; it never opens a repository itself | git-service owns the object database and is the single place paths and refs are validated. A second reader is a second place that validation can drift, and path validation is exactly where a traversal bug lives. |
| AD-26 | A review that cannot read the code is recorded `FAILED`, never `PASS` | "No problems found" when the content was unreachable is indistinguishable from a clean repository, and would be trusted as a pass. The failure row is written by a separate bean in its own transaction — saving it inline and rethrowing rolled it back, leaving an outage with no trace at all. |
| AD-27 | Findings never quote the credential they found | A finding is stored, returned by the API and written to logs. Echoing the secret would create three new copies of it, turning a detection into a leak. Only the first four characters survive, which is enough to know which key to rotate. |
| AD-28 | Dismissing a finding does not change the review's gate | The gate records what the analysis found at the time. Letting a dismissal rewrite it would make the history useless and turn the gate into something anyone can clear. Dismissals carry a required reason for the same reason. |
| AD-29 | Gateway route precedence is declared with `@Order`, not left to method order | Tasks and repositories nest under the project path and reviews nest under a repository, so a broader pattern matched first silently reroutes traffic to a service with no such endpoint. Spring sorts these beans with `AnnotationAwareOrderComparator`; declaration order happened to work but was never guaranteed. Now asserted by `RoutePrecedenceTest`. |
| AD-19 | git-service uses JGit, never a git binary | Shelling out would build command lines from branch names, paths and commit messages — all attacker-supplied in a product hosting other people's repositories. A library call takes them as arguments, so there is no shell to inject into, and the image needs no git installed. |
| AD-20 | Repository storage paths are derived from ids, never from names | `<root>/<organizationId>/<repositoryId>.git`. A name-derived path makes repository creation a filesystem write addressed by user input; the id is already unique, so the name buys nothing and costs a traversal surface. A rename also moves no files. |
| AD-21 | Repositories are hard-deleted, row and objects together | `BaseEntity`'s soft delete would leave a row claiming the repository exists while its files are gone — and keeping the files means storage grows for ever with data the user believes they deleted. |
| AD-22 | `ProjectAccessClient` lives in common-security, gated on a property | Three services now delegate project authorization to it. Duplicated, a correction would be applied to one copy and missed in the others. It is `@ConditionalOnProperty` because every service scans `com.devforge.ai`, and project-service — the authority itself — has no such property and would fail its context. |
| AD-18 | The notification bell polls; it does not hold a socket | A WebSocket delivers faster but is a connection per signed-in tab to maintain and reconnect, and a count up to a minute stale costs the user nothing. When chat (§11) brings a real-time channel, this should move onto it rather than keeping its own. |

---

## Documentation Status

All 11 files required by §7 are written:

| File | Covers |
|---|---|
| `CLAUDE.md` | how to work in this repository; the traps that have cost time |
| `README.md` | what the project is, and honestly what works |
| `docs/ARCHITECTURE.md` | system shape and the reasoning behind each structural choice |
| `docs/PROGRESS.md` | this file: status per feature, decision table, build gates |
| `docs/DEVELOPMENT_PLAN.md` | the order remaining work should be done in, and why |
| `docs/REQUIREMENTS_MATRIX.md` | every spec section with its status and evidence |
| `docs/API_CONTRACTS.md` | all 56 endpoints, conventions, error shape |
| `docs/TEST_PLAN.md` | the bar a feature must meet; gaps in priority order |
| `docs/TESTING.md` | how the suites are built and why |
| `docs/SECURITY.md` | implemented controls, how to verify, known gaps |
| `docs/THREAT_MODEL.md` | assets, trust boundaries, threats, residual risk |
| `docs/EVENT_CATALOG.md` | every event, its payload, its consumers, verification status |

`docs/Setup.md` predates the build repair and has **not** been reviewed; treat it as stale.

Two things these documents record that are easy to lose and were previously only in code comments or
commit messages: the numbered architectural decisions (`AD-1`…`AD-18`, above), and the fact that
`config-server` and `discovery-server` are empty Boot apps with no Spring Cloud dependency, wired up
by `docker-compose.yml` through environment variables nothing reads.
