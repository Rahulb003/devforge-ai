# DevForge AI — Progress

**Last updated:** 2026-10-10 (end of session)
**Phase:** Phases 0-4 and §7-§14 built, except what needs a model API key (AI, RAG, agents) or a
container runtime (the §37 sandbox, deployments). The whole stack runs in CI under docker compose
and on Kubernetes (kind) against real PostgreSQL, Kafka and Redis.

> Status vocabulary: `IMPLEMENTED`, `PARTIALLY_IMPLEMENTED`, `SCAFFOLDED`, `BROKEN`, `MISSING`.
> Nothing in this file is marked verified unless a command was actually run and its exit code observed.

---

## Build Status

| Gate | Command | Result |
|---|---|---|
| Backend tests | `mvn -B -ntp -f backend/pom.xml test` | **PASS** — 574 tests, 0 failures, locally and in CI |
| Frontend lint, typecheck, unit tests, build | `npm run lint`, `typecheck`, `test`, `build` | **PASS** — 0 warnings; 90 unit tests |
| Browser suite, standalone | CI `e2e` job, `npm run test:e2e` locally | **PASS** — 52 Playwright tests, failing on any CSP violation and on serious axe findings |
| Images | CI `docker` job | **PASS** — eleven images build, run as non-root, start healthy; Trivy blocks on fixable high/critical advisories in every one |
| Full stack under compose | CI `compose` job | **PASS** — every DevForge container read-only and without capabilities (checked on the running containers), each in its own container on real PostgreSQL, Kafka (SASL, per-service ACLs, refusals checked) and Redis; the browser suite with real events; outboxes drain; Prometheus scrapes all ten; six alert rules load; a stopped service's ServiceDown alert is emailed |
| Kubernetes | CI `kubernetes` job on kind | **PASS** — the generated manifests deploy with read-only root filesystems and the default seccomp profile; signup through nginx and the gateway; events reach the audit log and its hash chain verifies on PostgreSQL; git push and clone with the git CLI; Prometheus scrapes all ten and has its Alertmanager |
| Dependency and secret scanning | CI `security` job | **PASS** — npm audit (high), Trivy over the Maven and npm trees and for committed secrets, blocking |

Not verified anywhere: a production cluster (ingress, NetworkPolicy, autoscaling, failover), SCRAM
and certificates from a real PKI, OAuth with real providers, contention between several outbox
publishers of a service other than task-service (they share the same publisher code).

Toolchain: Node 24, Temurin JDK 21, Maven 3.9. No Docker daemon locally: everything container-based
is verified in CI only.

---

## Completed (this session)

### The repository did not build at all when work started

The single largest finding: a previous mechanical rewrite script (`scripts/fix-dockerfiles.py`,
`scripts/rewrite-dockerfiles.ps1`) had corrupted config files across the repo by writing literal
two-character escape sequences instead of real characters. (Both scripts have since been deleted:
running either would overwrite the compose file, the Dockerfiles and the ingress again.)

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
| 1 | AI features (§5, RAG, agents, AI review) are not built | — | Blocked: needs a model API key. Nothing is stubbed in their place. |
| 2 | The §37 sandbox and deployment pipelines are not built | — | Blocked: needs a container runtime or hypervisor. Designed in `docs/SANDBOX.md`. |
| 3 | Kafka uses PLAIN over TLS, and each stack's own CA | LOW | SCRAM is supported but not exercised; a deployment should issue the broker certificate from its PKI (cert-manager). |
| 4 | Secrets come from environment variables and a Kubernetes Secret | MEDIUM | No vault or rotation. `create-secrets.sh` generates random values for a fresh cluster. |
| 5 | The audit chain is tamper-evident, not tamper-proof | LOW | A database writer can recompute a whole chain. Chain heads are written to the log stream hourly; that catches a rewrite only if the logs are shipped off the database host. |
| 6 | Alerts go to Mailpit | — | Correct for compose and kind; a deployment must point Alertmanager at a real receiver. |
| 7 | Git over SSH and GitHub/GitLab integration do not exist | — | Git over HTTP does. The integration needs provider credentials. |

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
| Multi-tenancy / organizations | `IMPLEMENTED` | Organizations, projects and their members; 23 tenant-isolation/IDOR tests. Teams form by email invitation, accepted only by the verified owner of the address; owners and admins manage roles and members, the last owner is protected, and leaving removes project access. Project members are managed from the project. Verified in the browser with two users on both CI stacks |
| API gateway routing | `IMPLEMENTED` | Single entry point on 8080; routes auth, organizations/projects and the nested task/sprint paths. Verified live: signup 201, `/auth/me` 200, create org, create project, create task — all through the gateway |
| Correlation ids | `IMPLEMENTED` | Gateway generates one per request, reuses a valid inbound id, replaces an unsafe one; 6 tests |
| CORS | `IMPLEMENTED` | Explicit origin allow-list; a wildcard now fails startup rather than being silently echoed back. 5 tests |
| Kafka / outbox / event envelope | `IMPLEMENTED` | Envelope, outbox, idempotency, DLQ; 10 staging tests **plus 9 against a real in-process broker** — publication, ordering, dedup on redelivery, dead-lettering, and a poison event not blocking its partition. `SKIP LOCKED` verified under contention in CI: two publishers, 200 events, each published once |
| Kafka consumers in services | `IMPLEMENTED` | Two now: notification-service (identity, security, tasks) and analytics-service (tasks, repositories), each in its own consumer group so neither can starve the other |
| Notifications (§12) | `IMPLEMENTED` | Consumer, per-recipient storage, read/unread/delete API, bell with unread badge and a feed page. 22 backend tests (8 against a real broker) + 12 frontend. Verified live through the gateway |
| Kafka SASL / ACLs | `IMPLEMENTED` | One identity per service, deny-by-default ACLs; refusals checked in compose, the setup Job runs on kind. SASL_SSL only, each stack's own CA, a plaintext client refused (checked in compose) |
| Tasks, Kanban, sprints, comments, labels | `IMPLEMENTED` | task-service, 29 tests. Authorization delegated to project-service |
| Git hosting (§7) | `IMPLEMENTED` | git-service hosts real repositories via JGit: create, browse, commit, branch, diff, pull requests with merge rules, and clone/push over HTTP with personal access tokens (default branch protected, merge rule enforced on push). 149 tests; push and clone with the git CLI verified on kind |
| GitHub/GitLab integration | `MISSING` | Deliberately separate from the above — it needs provider credentials, and faking it was not an option |
| Code review and quality gates (§8) | `IMPLEMENTED` | review-service: secret detection, credential files, merge-conflict markers, dangerous patterns; severities, a configurable gate, and dismissal with a recorded reason. 83 tests. Verified live against a repository with a planted credential |
| Chat (§11) | `IMPLEMENTED` | chat-service: one channel per project, post/list/edit/delete, author-only edits, deletes erase the text. 8 tests. Live delivery over Server-Sent Events, verified across two browser windows through the dev proxy and the gateway. 10 backend tests. **Single-instance fan-out only, no presence** |
| Analytics (§14) | `IMPLEMENTED` | With a UI: totals, a daily chart and a screen-reader table, plus the server's completeness note shown verbatim. analytics-service consumes task and repository events into daily per-project counters, with a read API. The platform's second production consumer. 14 tests, 5 against a real broker |
| Documentation generation (§10) | `IMPLEMENTED` | documentation-service: repository overview, API surface and doc-coverage documents generated from real content. 31 tests. Not AI-written — every statement is derived from files that exist |
| Code-execution sandbox (§37) | `MISSING` | **Designed, deliberately not built** — see docs/SANDBOX.md. No container, VM or hypervisor is available here, and a sandbox that cannot isolate is worse than none because people trust it |
| AI file explanation and pull request review | `IMPLEMENTED` (live call **UNVERIFIED**) | ai-service reads the file as the caller and asks Claude, treating the content as untrusted data; off without a key, and the UI says so. 6 tests against a stand-in API; CI checks the unconfigured state in the browser |
| IDE, deploy, RAG, agents, AI generation and review | `MISSING` / `SCAFFOLDED` | deployment-service is a health endpoint (needs a container runtime); the rest need the model key to be verified |
| Frontend app shell, routing, theme store | `IMPLEMENTED` | 12 passing tests |
| Frontend auth screens (login/MFA/signup/verify/forgot/reset) | `IMPLEMENTED` | Driven against the live API; verified end-to-end through the dev proxy |
| Frontend organization + project screens | `IMPLEMENTED` | List/create, loading/empty/error states |
| Frontend Kanban board | `IMPLEMENTED` | Board, columns, task create/move; reachable by clicking from a project |
| Frontend notifications | `IMPLEMENTED` | Bell with unread badge, feed page, read/unread/delete, filter. 12 tests |
| Frontend documentation | `IMPLEMENTED` | Generate, tab per document, Markdown rendered as text. Reachable from a repository. 8 tests + 2 e2e |
| Frontend code review | `IMPLEMENTED` | Gate result, severity counts, findings with redacted snippets, and dismissal with a required reason. Reachable from a repository. 10 tests + 4 e2e |
| Frontend code browser | `IMPLEMENTED` | Repository list and create, file tree, file contents with line numbers, commit history, branch switching, and a commit form so a new repository is not a dead end. 20 tests + 6 e2e |
| Frontend IDE/AI screens | `MISSING` | Phases 5+ |
| End-to-end browser tests | `IMPLEMENTED` | 50 Playwright tests, run in CI against the standalone stack and again against the full compose stack |
| Audit trail | `IMPLEMENTED` | Every published change recorded from the events, per-project SHA-256 hash chain, verify on demand from the UI; verified on PostgreSQL in CI |
| Monitoring and alerting | `IMPLEMENTED` | Prometheus metrics behind a scrape credential, Grafana dashboard, outbox and dead-letter meters, six alert rules, Alertmanager email; delivery checked in CI |
| Kubernetes manifests | `IMPLEMENTED` | Generated from one table by `infrastructure/kubernetes/generate.cjs`; deployed and smoke-tested on kind in CI |
| Personal access tokens | `IMPLEMENTED` | For git only: hashed, shown once, expiring, revocable; exchanged internally for a five-minute token |

---

## Next Task (exact)

Earlier entries in this list are in the git history; everything they named is done or recorded
above. What remains, in order:

1. **Blocked on a model API key:** AI assistance (§5), AI code review on top of review-service's
   findings model, RAG over repositories, agents. Each needs the authorization and failure model
   written first (docs/API_CONTRACTS.md §8).
2. **Blocked on a container runtime:** the §37 sandbox to docs/SANDBOX.md's twelve guarantees, then
   deployment pipelines (deployment-service is a health endpoint).
3. **Unblocked:** nothing outstanding from this list; see Known Issues for what is accepted.

---

## Architecture Decisions

| # | Decision | Rationale |
|---|---|---|
| AD-1 | Boot, Spring Cloud and springdoc upgraded together (3.5.16 / 2025.0.3 / 2.8.17) | springdoc 2.8 targets Boot 3.5 and breaks at startup on 3.4, so they were held at 3.4 / 2.7.0 until the Boot 3.4 line stopped receiving fixes. Moved together in one change; verified by starting every service and fetching its OpenAPI document. |
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
| AD-35 | Live chat uses Server-Sent Events read by `fetch`, not WebSockets or `EventSource` | Delivery only flows server-to-client, and SSE crosses the gateway as an ordinary HTTP response (verified unbuffered). `EventSource` cannot send an Authorization header, and the alternative, the token in the URL, would leak it into access logs. |
| AD-36 | Each stream opens with an immediate comment event | Spring does not commit an SSE response until the first event, so a quiet channel sent no headers and looked hung. A curl probe missed this because it posted a message, which forced the flush. Only the browser test, which waits for the stream before posting, exposed it. |
| AD-37 | Streams close at the access-token lifetime | Authorization is checked once, when the stream opens. An unbounded stream would keep delivering after the token expired or the user left the project; reconnecting re-runs the check. |
| AD-33 | Analytics stores pre-aggregated day buckets, not one row per event | The questions it answers are all "how much happened, and when" at day resolution. A row per event would grow without bound to serve a query that never needs it, and the events themselves remain in Kafka for anything that does. |
| AD-34 | Activity is bucketed by the event's own timestamp, not by when it was consumed | Otherwise every consumer outage leaves a visible spike on the wrong date, and a replay rewrites history. |
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
`config-server` and `discovery-server` were empty Boot apps with no Spring Cloud dependency, wired up
by `docker-compose.yml` through environment variables nothing read. They have since been deleted:
services find each other by DNS name and are configured by environment, ConfigMap and Secret.
