# CLAUDE.md

Working notes for anyone — human or AI — picking this repository up. Read this before changing code.

`docs/PROGRESS.md` is the source of truth for **what is built**. This file is about **how to work
here**, and the handful of traps that have already cost real time.

---

## The three rules that matter most

1. **Inspect before changing.** Classify what exists as `IMPLEMENTED`, `PARTIALLY_IMPLEMENTED`,
   `SCAFFOLDED`, `BROKEN` or `MISSING` before you touch it. Several of the "missing" features in
   this repo turned out to be present and broken, and several "working" ones were never executed.
2. **Never fake functionality.** No hard-coded dashboard metrics, no buttons that do nothing, no
   stubbed AI or deployment responses presented as real. A development stand-in is acceptable only
   when it is clearly named as one, isolated, and replaceable — `DevMailboxPage` is the model.
3. **Never claim unverified success.** If you could not run it, write `UNVERIFIED` and say why.
   This file and `docs/PROGRESS.md` both use that word literally.

A feature is done when **Frontend → API → Backend → Database/Kafka → real result** works, with
validation, authorization, error handling, persistence, tests, UI loading/empty/error states, and
documentation. Not before.

---

## Build and test

```bash
# Backend: always through the reactor root, never a service's own pom.
mvn -B -ntp -f backend/pom.xml test                 # 609 tests
mvn -B -ntp -f backend/pom.xml -pl ../services/auth-service -am test   # one module (-am is required)

# Frontend
cd frontend
npm test            # 104 unit tests (vitest)
npm run lint        # prettier runs as an eslint rule; 0 warnings allowed
npm run typecheck
npm run test:e2e    # 54 Playwright tests — needs the stack already running, on an idle machine

# Run the whole stack locally, no Docker/PostgreSQL/Kafka needed
./openapp.bat       # then ./stopapp.bat
```

`-am` is not optional when building a single module: the shared libraries are not installed to the
local repository, so without it Maven tries to resolve `common-library` from Maven Central and fails.

---

## Traps that have already cost time

**The reactor imports the Spring Boot BOM; it does not inherit `spring-boot-starter-parent`.**
Three separate bugs came from this, all fixed in `backend/pom.xml` — if you add a module, check
they still hold:

- `maven-compiler-plugin` needs `<parameters>true</parameters>`, or Spring cannot infer
  `@PathVariable` names and every parameterised route breaks at runtime.
- `spring-boot-maven-plugin` needs its `repackage` execution declared explicitly. Without it the
  jars are plain libraries with no `Main-Class`, and **every Docker image fails with "no main
  manifest attribute"** — which is invisible until you actually run one.
- Plugin versions are unmanaged, so pin them in `pluginManagement`.

**Entity scanning must be widened in every service that uses the outbox.** `@SpringBootApplication`
only widens *component* scanning; JPA entity scanning defaults to the application class's package,
so the shared outbox and processed-event entities are invisible unless the service declares
`@EntityScan` and `@EnableJpaRepositories` over `com.devforge.ai.common`. A service that forgets
this duplicates every redelivered event, silently.

**Entities using `@UuidGenerator` must never have their id assigned in application code.** Assigning
one makes Hibernate treat the instance as detached and turn `persist()` into `merge()`. This broke
signup and login once already.

**A method calling its own class's `@Transactional` method skips the transaction.** The call never
passes through Spring's proxy. The outbox publisher's scheduled entry point did exactly this, so its
`FOR UPDATE SKIP LOCKED` locks ended with the query and two instances published 66 of 200 events
twice; every test called the method through the proxy and passed. Where a transaction boundary
matters, open it with a `TransactionTemplate`, and test the entry point the framework calls.

**Version pinning is deliberate, not accidental.** Boot, Spring Cloud and springdoc move together:
Boot 3.5.16, Cloud 2025.0.3 (Northfields), springdoc 2.8.17. springdoc 2.8 against Boot 3.4 throws
`PatternParseException` at startup, and the tests do not catch that — start a service and fetch
`/v3/api-docs` with a token after any of the three changes. The gateway starter is
`spring-cloud-starter-gateway-server-webmvc`; the old `-gateway-mvc` name is deprecated in 2025.0.
ESLint stays on 8.x because the config is `.eslintrc.cjs` format.

**CORS belongs to the gateway alone.** `CorsConfig` is conditional on
`devforge.cors.allowed-origins` being non-blank, and only api-gateway sets it. An empty
allow-list does not mean "no cross-origin access" to a `CorsFilter` — it means **reject anything
carrying an `Origin` header**, which is exactly what a gateway forwards. Configuring it on every
service left the whole application answering 403 to browsers while every `curl` check passed,
because `curl` sends no `Origin`. Do not add the property to a service behind the gateway.

**nginx resolves a hostname in `proxy_pass` once, at start.** When the gateway container
restarted it came back on a new address and the frontend answered 502 to every API call until
nginx was restarted too. `frontend/nginx.conf` now resolves per request through a variable and
`resolver`; keep it that way, and keep the Kubernetes upstream fully qualified, because nginx's
resolver does not apply search domains.

**Never background a build-then-start chain.** `mvn package ... && java -jar ... &` backgrounds the
whole chain, so the JVM can start while the jar is still being repackaged — which reproduces the
"no main manifest attribute" failure above and looks exactly like a code bug. Build in the
foreground, check `Start-Class` is in the manifest, then start.

**`String.trim()` strips NUL**, because it removes every character at or below U+0020. Validate
control characters *before* trimming, or `"main\0"` quietly becomes the valid ref `"main"`.

**The dev shell collapses backslash escapes.** `perl`, `sed` and heredocs containing `\\` get
mangled. For anything involving backslashes or Windows paths, write a small Node script to a
scratch directory and run that instead.

---

## Conventions

- **Comments explain *why*, never *what*.** The code says what it does. A comment earns its place
  by recording a decision, a constraint, or a bug that is not visible from the code — see
  `ProjectAccessClient` or `CorsConfig` for the tone.
- **Responses are wrapped** in `ApiResponse<T>` (`success`, `data`, `message`).
- **Cross-tenant and cross-user access returns 404, never 403.** A 403 confirms the id exists,
  which turns the endpoint into an oracle for enumerating other people's ids.
- **Authorization is derived from membership rows server-side.** Never from a token claim, a path
  parameter, or a request body — those are all values the client controls.
- **A service never reaches into another service's database.** Foreign keys stop at the service
  boundary; cross-service ids are plain UUID columns. When task-service needs to know whether the
  caller may touch a project, it asks project-service, forwarding the caller's own bearer token.
- **Tests run against real infrastructure where it is possible without Docker:** real Flyway
  migrations on H2 in PostgreSQL mode with `ddl-auto: validate`, and a real in-process Kafka broker.
  Mocks are for the network between services, not for the database or the broker.

---

## How features get found to be broken

Every significant defect in this repo was found by writing a test against real infrastructure, not
by reading the code. Five auth endpoints returned 500 while looking perfectly correct; the app threw
on every data-driven screen while the unit tests passed, because they supplied a provider the real
entry point lacked; task events named the wrong actor, which only surfaced once something consumed
them.

So: write the integration test first, let it find the defect, fix the root cause, and record the
reasoning in a comment and the commit message. Do not fix the symptom.

---

## What not to assume works

- **The whole stack runs under docker compose and on Kubernetes (kind), in CI only.** There is no
  daemon locally. CI builds every image, starts each alone, then starts `docker-compose.yml` - every
  service in its own container with its own PostgreSQL database, real Kafka and Redis, nginx in
  front - runs the browser suite against it, and checks every outbox drained and the consumers
  processed events. The first such run found snappy compression could not load on Alpine, so no
  event had ever left a container. The kind job deploys the manifests `generate.cjs` writes - edit
  the generator, never the YAML - and smoke-tests signup through to the audit log. Its first runs
  found consumers dying for good on one authorization refusal, then sitting idle for five minutes
  beside topics created after they joined. Kind is one node: no ingress controller, no
  NetworkPolicy enforcement, no node failure, and it is not a production cluster.
  Adding a reactor module means adding it to the root `Dockerfile` too; forgetting that broke
  the first image build ever run.
- **CI on Linux runs test classes in a different order from Windows.** Shared-context test
  classes must not depend on each other's cleanup; auth-service's did, and CI was red for weeks
  while every local run passed. Reproduce with `-Dsurefire.runOrder=reversealphabetical`.
- **There is no service discovery or config server.** The empty `config-server` and
  `discovery-server` apps were deleted; services resolve each other by DNS name and take their
  configuration from the environment. Do not add Eureka or Spring Cloud Config back without a need
  the platform's DNS and ConfigMaps cannot meet.
- **The standalone profile has no broker.** `devforge.kafka.enabled=false`, so consumers are not
  registered and the notification feed stays empty when you run `openapp.bat`. That is a limitation
  of running without Kafka, not a bug.
- **OAuth is `UNVERIFIED`** — it needs real provider credentials.
- **`FOR UPDATE SKIP LOCKED` is verified only end to end.** H2 does not support it, so the unit and
  integration tests run with it off. The compose job in CI runs a second task-service on the same
  outbox, creates 200 tasks and reads the topic: every event exactly once, and both instances
  publishing. Its first run found the claim's locks were being released immediately (see the
  `@Transactional` self-call trap above).
