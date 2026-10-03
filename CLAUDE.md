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
mvn -B -ntp -f backend/pom.xml test                 # 186 tests
mvn -B -ntp -f backend/pom.xml -pl ../services/auth-service -am test   # one module (-am is required)

# Frontend
cd frontend
npm test            # 26 unit tests (vitest)
npm run lint        # prettier runs as an eslint rule; 0 warnings allowed
npm run typecheck
npm run test:e2e    # 22 Playwright tests — needs the stack already running

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

**Version pinning is deliberate, not accidental.** `springdoc` is held at 2.7.0 because 2.8.x
targets Boot 3.5 and throws `PatternParseException` at startup. Spring Cloud is 2024.0.3 — 2024.0.4
does not exist. ESLint stays on 8.x because the config is `.eslintrc.cjs` format.

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

- **Docker and Kubernetes are `UNVERIFIED`.** No daemon has ever been available here. The
  Dockerfiles are hardened on paper only.
- **`config-server` and `discovery-server` do nothing.** They are empty Boot web apps — no
  `@EnableConfigServer`, no `@EnableEurekaServer`, no Spring Cloud dependency. `docker-compose.yml`
  points every service at them through env vars that nothing reads. Either implement them or delete
  them; do not write code that assumes service discovery exists.
- **The standalone profile has no broker.** `devforge.kafka.enabled=false`, so consumers are not
  registered and the notification feed stays empty when you run `openapp.bat`. That is a limitation
  of running without Kafka, not a bug.
- **OAuth is `UNVERIFIED`** — it needs real provider credentials.
- **`FOR UPDATE SKIP LOCKED` is `UNVERIFIED`** — H2 does not support it, so tests run with it off.
  Production depends on it: without it, concurrent outbox publishers duplicate every event.
