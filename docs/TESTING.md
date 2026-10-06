# Testing

**Last updated:** 2026-10-03

How the suites are built and why they are built that way. What is planned but unwritten is in
`docs/TEST_PLAN.md`.

---

## 1. Current state

| Suite | Command | Count | Result |
|---|---|---|---|
| Backend | `mvn -B -ntp -f backend/pom.xml test` | 426 | PASS |
| Frontend unit | `cd frontend && npm test` | 64 | PASS |
| Browser end-to-end | `cd frontend && npm run test:e2e` | 28 | PASS |
| Testcontainers | — | 0 | **UNVERIFIED** — needs Docker |

Backend, by module:

| Module | Tests |
|---|---|
| common-library | 6 |
| common-events | 9 |
| api-gateway | 10 |
| auth-service | 92 |
| project-service | 23 |
| task-service | 29 |
| git-service | 107 |
| review-service | 83 |
| documentation-service | 31 |
| analytics-service | 14 |
| notification-service | 22 |

Three services have no tests because they have no behaviour — they are two-file scaffolds.

git-service's 107 are mostly validation: 76 cases covering paths, repository names and refs,
because that is where attacker-supplied text meets the filesystem and the object database.

---

## 2. The principle this suite is built on

**Every significant defect in this repository was found by a test against real infrastructure, not
by reading code.** That is not a slogan; it is the observed history:

- Five auth endpoints returned 500 while looking entirely correct. The causes were a detached
  Hibernate entity, a fabricated principal, and an `instanceof` that never matched.
- Jars were not executable — no `Main-Class` — which would have failed every Docker image with "no
  main manifest attribute". Nothing in the source said so.
- The app threw on every data-driven screen after sign-in while the unit suite stayed green, because
  the tests supplied a `QueryClientProvider` the real entry point did not have.
- Task events named the wrong actor. Invisible until something consumed them.
- Every browser request to the API returned 403 while every `curl` check returned 201. The CORS
  allow-list was configured only on the gateway, and an empty list means "reject anything with an
  `Origin` header" rather than "allow no cross-origin access". `curl` sends no `Origin`, so six
  live smoke tests passed against a build in which signing up was impossible.
- A review of a repository whose content could not be read left no trace at all. The `FAILED` row
  was saved inside the same transaction that then rethrew, so it rolled back with everything
  else — and an outage became indistinguishable from nobody having asked for a review. Caught by
  asserting the row exists, not by asserting the status code.
- A line containing a run of `x` characters suppressed a real credential, because the
  placeholder check ran against the whole line instead of the matched value. A false negative
  in a secret scanner is the worst kind, and only a test with a long minified line found it.
- Deleting a repository half-succeeded on Windows: git writes loose object files read-only, and
  Windows refuses to delete a read-only file. The row vanished, the objects stayed, and the only
  trace was a warning. Found by asserting the directory was gone rather than that the call
  returned 200.

So the working order is: write the test against real infrastructure, let it find the defect, fix the
root cause, record the reasoning. Not: write the code, then write a test that agrees with it.

---

## 3. Backend

### Real migrations, real schema

Tests run the **actual Flyway migrations** against H2 in PostgreSQL mode, then `ddl-auto: validate`
asserts the JPA mappings match what the migrations produced (`AD-3`). `create-drop` would generate the
schema from the entities, which means the migrations are never executed and a mapping can drift from
them unnoticed. This arrangement tests both, together, with no Docker dependency.

Its limit, stated plainly: H2 in PostgreSQL mode is not PostgreSQL. It has no
`FOR UPDATE SKIP LOCKED`, so the outbox tests run with the skip-locked claim disabled — and
**production depends on it**, because without it concurrent publishers duplicate every event. That
path is `UNVERIFIED`.

### Real tokens, not mock principals

Tests authenticate through the real filter chain with real signed JWTs (`TestTokens`), not
`@WithMockUser`. A mock principal bypasses token verification entirely, so signature, issuer, expiry
and token-type checks would never run — and in notification-service the token subject *is* the
authorization boundary.

Each service's `TestTokens` can also mint deliberately wrong tokens — refresh type, wrong signing
key, expired — because "the right token works" is only half the assertion.

### A real Kafka broker

`spring-kafka-test` starts an in-process broker in KRaft mode, so the producer configuration,
serialisation, error handler and dead-letter recoverer are genuinely exercised (`AD-14`). Mocking the
broker would have verified only that the code calls the methods it calls.

What it cannot show: broker failover. `acks=all` exists to survive a leader change, and one embedded
broker has no leader to lose. That needs Testcontainers.

Two things to know when writing broker tests:

- **Give each test its own topic.** Topics are append-only with no per-test truncation, so a fresh
  consumer group reading from `earliest` replays what earlier tests left behind. "Exactly one event
  arrived" silently becomes "three did" (`AD-15`). This was a real failure, not a hypothetical.
- **`KafkaTestUtils.consumerProps` defaults the key deserializer to Integer.** Our keys are tenant
  UUIDs, so both deserializers must be set explicitly or every poll fails with "Size of data received
  by IntegerDeserializer is not 4".

### Mocks

Used for the network **between** services only — `ProjectAccessClient` is mocked in task-service's
tests so they exercise task logic rather than two services' HTTP. Never for the database or the
broker.

### Authorization tests are mandatory

Any endpoint that touches tenant or user data needs a test proving another tenant or user cannot
reach it, and that the refusal is **404 rather than 403**. §32 asks for this explicitly. Existing
examples: `TenantIsolationTest` (23), `NotificationApiTest.CrossUser` (3).

---

## 4. Frontend

Vitest + Testing Library, jsdom.

**Queries are by role and accessible name**, not by test id. A test that passes while the control is
unreachable to a screen reader has verified the wrong thing — `getByRole('button', { name: 'Delete
notification: …' })` asserts the accessible name and the behaviour at once.

**Tests must not supply providers the real entry point lacks.** This is the lesson from the
`QueryClientProvider` incident: `App.test.tsx` deliberately renders `App` with no provider, because
`App` owns them. A component test may create its own `QueryClient` — with `retry: false` and
`gcTime: 0`, so a cached result does not leak into the next test and an error-state assertion does not
wait out a backoff.

**Every data-driven screen needs loading, empty and error coverage.** An empty state that renders a
bare blank panel is a bug, and a failure with no retry leaves the user reloading the page.

---

## 5. End-to-end

**This is the suite that catches what the others cannot.** The CORS regression above is the
clearest example: unit tests passed, live `curl` checks passed, and the application was completely
unusable in a browser. Anything that depends on headers a browser sends and a CLI does not —
`Origin`, cookies, preflight — is only testable here.

Playwright, Chromium, against a stack that is **already running** (`openapp.bat`). The suite
deliberately does not start the stack: five JVMs take a while to boot, and a test run that silently
launches background processes is hard to reason about when it fails.

Serial, one worker: the tests create real accounts and organizations against one shared database, so
parallel workers would interfere.

Timeouts are generous on purpose — 45s per test, 20s per expectation. The first run after a cold
start has five JVMs warming up behind an extra gateway hop, and two tests raced a 10s budget while
passing in isolation. A flaky suite is worse than a slow one. `retries` is 0 locally and 1 in CI,
where a pass-on-retry must be visible rather than smoothed over.

**Run it on a machine that is not otherwise busy.** Six JVMs, a dev server and a browser already
saturate a laptop; running the unit suite or a production build alongside it pushed signup past
the 20s expectation and failed two tests that passed on their own moments later. The timeouts
are not the problem and raising them further would only hide load. `retries: 1` in CI covers
the same risk there.

Run it with `npm run test:e2e`, not a bare `playwright test` — the config path is explicit because a
bare invocation globs the Vitest specs and reports "No tests found".

---

## 6. What is not tested

Stated so none of it is mistaken for covered:

- **Docker image builds and Kubernetes manifests** — no daemon available. Entirely `UNVERIFIED`.
- **`FOR UPDATE SKIP LOCKED`** under concurrent publishers.
- **Broker failover**, and therefore `acks=all`.
- **Retry/backoff timing** under a transient outage. Only the non-retryable path is exercised.
- **Consumer lag, rebalance and replay.**
- **OAuth sign-in** — needs real provider credentials.
- **Real email delivery.** The dev mailbox is used throughout.
- **Load, soak and performance.** No budgets are defined.
- **Accessibility beyond role-based queries.** No axe run, no keyboard-navigation suite.
- **Visual regression.**
