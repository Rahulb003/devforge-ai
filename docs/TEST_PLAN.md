# Test plan

**Last updated:** 2026-10-03

What will be tested, to what standard, and in what order. How the existing suites are built is in
`docs/TESTING.md`.

---

## 1. Standard a feature must meet

A feature is not done until all of these exist. This is the checklist used for tasks, notifications
and the gateway, and it is the bar for everything that follows.

| Layer | Required |
|---|---|
| Happy path | integration test through the real filter chain against a migrated schema |
| Validation | every rejected input asserted, with the field named |
| Authorization | another tenant and another user proven unable to reach it, **404 not 403** |
| Authentication | no token, wrong token type, wrong signing key, expired token |
| Persistence | the row is actually there afterwards, with the fields expected |
| Events | staged in the same transaction; rollback leaves none |
| Failure of a dependency | asserted to fail **closed**, never open |
| Frontend | loading, empty and error states, each asserted |
| Accessibility | queried by role and accessible name, not by test id |
| Idempotency | where the operation claims it — asserted twice |

A pull request that adds an endpoint without the authorization row is incomplete regardless of how
well the happy path works.

---

## 2. Test levels and what belongs at each

| Level | Tool | Belongs here | Does not belong here |
|---|---|---|---|
| Unit | JUnit, Vitest | pure logic: TOTP, password rules, envelope validation, notification mapping | anything needing a database |
| Integration | `@SpringBootTest` + H2 with real migrations | controllers, services, repositories, security, transactions | cross-service HTTP |
| Messaging | `@EmbeddedKafka` | producer config, serialisation, dedup, dead-lettering, ordering | broker failover |
| Contract | **not yet written** | that a producer's payload still satisfies its consumers | |
| End-to-end | Playwright | the composition the user actually meets | per-field validation already covered below |
| Load | **not yet written** | throughput and latency budgets | |

Mocks are for the network **between** services. Never for a database or a broker.

---

## 3. Gaps, in the order they should be closed

### Priority 1 — correctness risks that production depends on

1. **`FOR UPDATE SKIP LOCKED` with concurrent publishers.** Needs PostgreSQL. Without it concurrent
   outbox publishers duplicate every event, and the test suite currently runs with it **disabled**
   because H2 cannot do it. This is the largest untested dependency in the system.
2. **Broker failover and `acks=all`.** Needs a multi-broker Testcontainers cluster. The setting
   exists specifically to survive a leader change, and nothing has ever exercised one.
3. **Contract tests between producers and consumers.** `TaskCompleted` shipped without `assigneeId`
   and nothing failed, because nothing asserted what consumers need. A contract test would have
   caught it at the producer.

### Priority 2 — things believed to work but never run

4. **Docker image builds**, then a Compose stack brought up and smoke-tested. The `repackage`
   omission means "the jar builds" and "the image runs" have been different things here before.
5. **Kubernetes manifests** applied to a throwaway cluster.
6. **The CI pipeline itself**, which has never executed.
7. **OAuth sign-in**, with real provider credentials against a test tenant.
8. **Real email delivery** against a live SMTP server.

### Priority 3 — hardening

9. **Retry and backoff timing** under a transient outage. Only the non-retryable path is exercised
   today, so the exponential backoff is unproven.
10. **Consumer lag, rebalance and replay** — including that a deliberate replay produces no duplicate
    notifications, which the code defends against but no test asserts.
11. **Accessibility audit** — axe on every shipped screen, plus a keyboard-only navigation pass.
12. **Load and soak**, once budgets are defined. No budget exists yet, so there is nothing to assert.
13. **Dependency and container scanning** in CI.

---

## 4. Tests to write with each feature not yet built

Stated now so they are not an afterthought.

**git-service.** Repository content is attacker-supplied. Required: a repository whose filenames,
branch names and commit messages contain shell metacharacters, path traversal (`../`), absolute paths,
null bytes and extremely long names — asserted to be handled as data, never interpolated into a
command or a filesystem path.

**AI features (§6, §15, §16).** Required: a prompt-injection suite in which repository content
instructs the model to exfiltrate secrets, escalate privilege or ignore its instructions, asserted to
have no effect on authorization or on what the feature does. Also: model output treated as untrusted
input everywhere downstream, and a cost/token ceiling asserted to hold.

**Code execution (§37).** Required before the feature ships: proof that submitted code cannot reach
the network, the host filesystem, other tenants' data, or any ambient credential; and that CPU, memory
and wall-clock limits terminate a runaway. A sandbox without an escape-attempt suite is not a sandbox.

**deployment-service.** Required: that a deployment credential is never logged, never returned by an
API, and never reachable from code the platform is asked to analyse; and that a rollback actually
restores the previous state.

**chat-service.** Required: that a user cannot read a channel they are not a member of — the same
cross-user proof as notifications, over a WebSocket, where the authorization check is easier to forget.

---

## 5. Entry and exit criteria

**A change may merge when:** the full backend suite, the frontend unit suite, lint, typecheck and the
production build all pass, and the new behaviour has the rows from §1 that apply to it.

**A release would additionally require** — none of which is satisfied today — the Priority 1 and
Priority 2 gaps closed, a load test against a defined budget, and a security review of anything
touching authentication, authorization or code execution.

---

## 6. Environments

| Environment | Database | Broker | Used for |
|---|---|---|---|
| Test suite | H2 in-memory, PostgreSQL mode, real migrations | in-process | every automated run |
| `standalone` | H2 file | none | manual exploration, `openapp.bat` |
| `local` | PostgreSQL | localhost | development against real infrastructure |
| CI | as the test suite | in-process | **UNVERIFIED** — never executed |

There is no staging environment, and no environment where PostgreSQL and Kafka have both been
exercised together. That is why Priority 1 reads the way it does.
