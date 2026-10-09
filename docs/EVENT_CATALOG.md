# DevForge AI — Event Catalog

**Last updated:** 2026-09-26
**Transport:** Apache Kafka (KRaft mode, no ZooKeeper)
**Status:** envelope, outbox and idempotency `IMPLEMENTED` and tested. Publication to a live broker
is `UNVERIFIED` — no Docker daemon in the development environment, so no broker has been run.

---

## 1. Why Kafka, and why RabbitMQ was removed

RabbitMQ was declared in `auth-service/pom.xml`, `docker-compose.yml`, the Kubernetes manifests and
`.env.example`, but had **zero lines of Java using it** — no `RabbitTemplate`, no `@RabbitListener`,
no exchange or queue declarations. It was infrastructure nobody called.

§18 asks for a justification to keep it. There is none: DevForge's messaging needs are domain event
streaming (many independent consumers, replayable history, ordered per tenant), which is Kafka's
model. RabbitMQ's advantage is competing-consumer work distribution with per-message
acknowledgement, and nothing here needs that today. Keeping both would mean operating two brokers
and teaching every contributor which to use.

It has been removed from all five places. If a genuine work-queue requirement appears later
(for example dispatching sandboxed code-execution jobs, §37), that is the point to reconsider —
and the decision should be recorded here.

---

## 2. Envelope

Every event, on every topic, uses one shape. Defined in
`common-events/.../EventEnvelope.java`.

```json
{
  "eventId": "6f1c...",
  "eventType": "UserRegistered",
  "version": 1,
  "timestamp": "2026-09-26T10:15:30Z",
  "source": "auth-service",
  "tenantId": null,
  "actorId": "a71f...",
  "correlationId": "req-8821",
  "payload": { }
}
```

| Field | Notes |
|---|---|
| `eventId` | Unique per event; **the consumer deduplication key**. Never reused, including across publish retries. |
| `eventType` | From `EventTypes`. Constants, not literals — a typo in a producer or a consumer filter is otherwise a silent no-op. |
| `version` | Payload schema version. See §6. |
| `timestamp` | When the event *occurred*, not when it was published. Those differ whenever the outbox lags. |
| `source` | Emitting service, from `spring.application.name`. If unset, events are stamped `unknown-service` and become untraceable. |
| `tenantId` | Owning organization, or null for platform-level events. **Consumers must treat this as the authorization boundary.** |
| `actorId` | User who caused the event; null for system-initiated. |
| `correlationId` | Read from MDC, ties an event back to the HTTP request that caused it. |
| `payload` | Event-specific. **Never credentials** — see §7. |

Unknown envelope fields are ignored on read, so a producer on a newer version can add fields
without breaking older consumers. That plus `version` is what makes rolling deploys survivable.

**Partition key** is `tenantId` when present, else `eventId`. All of one organization's events
therefore land on one partition and are consumed in order. Ordering *across* tenants is not
guaranteed and is not needed.

---

## 3. Topics

Grouped by bounded context, not one topic per event type — a topic per type multiplies partitions
and consumer groups for no ordering benefit.

| Topic | Contents |
|---|---|
| `devforge.identity.v1` | User lifecycle: registration, verification, password and MFA changes |
| `devforge.projects.v1` | Organizations, projects, membership |
| `devforge.tasks.v1` | Tasks and sprints (Phase 4, not yet produced) |
| `devforge.security.v1` | Security signals other services act on |
| `devforge.notifications.v1` | Notification fan-out (Phase 12, not yet produced) |

Dead-letter topics are the topic name plus `.dlt`, e.g. `devforge.identity.v1.dlt`.

The `.v1` suffix is deliberate: a breaking payload change ships as a **new topic** that old and new
consumers can straddle, rather than an in-place change that breaks whoever deploys second.

---

## 4. Events

### Produced today (`auth-service`)

| Event | Topic | Payload | Trigger |
|---|---|---|---|
| `UserRegistered` | identity | `userId`, `username`, `email`, `status`, `emailVerified` | Signup succeeds |
| `UserVerified` | identity | `userId`, `email` | Email verification token accepted |
| `UserPasswordReset` | identity | `userId` | Password changed via reset token |
| `UserMfaEnabled` | identity | `userId` | TOTP enrolment confirmed |
| `UserMfaDisabled` | identity | `userId` | MFA turned off |
| `RefreshTokenReuseDetected` | security | `userId`, `action` | A rotated refresh token was replayed; all sessions revoked |

### Produced today (`task-service`)

Every task event carries `taskId`, `projectId`, `taskNumber`, `title`, `assigneeId` and
`reporterId`, plus the fields below. The envelope's `tenantId` is the organization and `actorId` is
the user who performed the action.

| Event | Topic | Extra payload | Trigger |
|---|---|---|---|
| `TaskCreated` | tasks | `status`, `priority`, `type` | A task is created |
| `TaskAssigned` | tasks | `previousAssigneeId` | A task gains an assignee, or is reassigned |
| `TaskMoved` | tasks | `status`, `previousStatus` | A task changes column |
| `TaskCompleted` | tasks | `completedAt`, `storyPoints` | A task reaches a terminal status |

`assigneeId` is on every task event rather than only on `TaskAssigned`, because a consumer deciding
who to tell needs to know who holds the task — and `TaskCompleted` previously carried no way to
find out.

### Produced today (`git-service`)

Topic `devforge.repositories.v1`. Tenant is the organization; actor is the user who acted.

| Event | Payload | Trigger |
|---|---|---|
| `RepositoryCreated` | `repositoryId`, `projectId`, `name`, `defaultBranch` | A repository is created |
| `RepositoryDeleted` | `repositoryId`, `projectId`, `name` | A repository is deleted |
| `RepositoryPushed` | `repositoryId`, `projectId`, `branch`, `commitId`, `path`, `paths` | A commit is written through the API; `paths` lists every file in a multi-file commit, `path` is its first |
| `PullRequestOpened` | `repositoryId`, `projectId`, `pullRequestId`, `number`, `sourceBranch`, `targetBranch` | A pull request is opened |
| `PullRequestMerged` | same | A pull request is merged; the merge commit is on the target branch |
| `PullRequestClosed` | same | A pull request is closed without merging |

`RepositoryPushed` is named for what it will mean rather than only what it does today: the API
commits one file at a time, and a real push over HTTP or SSH is a transport this service does not
yet speak. Consumers should treat it as "the repository gained a commit".

No consumer subscribes to this topic yet. That is the normal direction for the asymmetry — an
event with no consumer is inert, whereas a consumer for an event nobody emits is dead code that
reads like a feature.

### Consumed today (`notification-service`)

The platform's first production consumer, in group `notification-service`. It subscribes to
identity, security and tasks, and writes one notification row per recipient.

| Event | Who is notified | Why |
|---|---|---|
| `TaskAssigned` | the new assignee, and the previous one if there was a different one | work moving to or away from someone |
| `TaskCompleted` | the assignee, unless they completed it themselves | |
| `UserPasswordReset` | the account the event concerns | the classic way account takeover is noticed |
| `UserMfaEnabled` / `UserMfaDisabled` | ditto | disabling a second factor is the one that matters |
| `RefreshTokenReuseDetected` | ditto | the user's sessions were ended; they should know why |

Two rules that are easy to get wrong:

- **The actor is never notified of their own action.** This is the single most common way a feed
  becomes noise people learn to ignore. It depends on `actorId` being the acting user, which is why
  task-service passing the *reporter* there was a defect rather than a cosmetic detail.
- **Security alerts go to the subject, not the actor.** A password reset completed through an
  emailed link has no authenticated actor at all, and the whole value of the alert is that it
  reaches the account owner even when someone else triggered it.

An unrecognised event type produces no notifications and does **not** fail. A consumer that
rejected unknown types would start dead-lettering the moment any producer shipped a new one, and
"notifications are down" is a worse outcome than "that event notifies nobody yet".

### Declared, not yet produced

`OrganizationCreated`, `OrganizationDeleted`, `ProjectCreated`, `ProjectUpdated`,
`ProjectArchived`, `ProjectDeleted`, `ProjectMemberAdded`, `ProjectMemberRemoved`,
`UserLoggedIn`, `SecurityIssueDetected`.

Constants exist in `EventTypes`; project-service does not yet stage them. Listing them here without
that caveat would be documenting intent as implementation.

For the same reason notification-service has **no listener** for project events: a handler for an
event nobody emits is dead code that reads like a feature. `ProjectMemberAdded` is the obvious next
one — "you were added to a project" — and it needs the producer first.

---

## 5. Reliability

### Transactional outbox

```
business transaction
  ├── state change      ──┐
  └── outbox_events row ──┘  commit together
                     ↓
            OutboxPublisher (polls)
                     ↓
                   Kafka
```

Publishing inline has two failure modes, and the outbox removes both: an event lost when the broker
is briefly unavailable *after* a successful commit, and an event announcing a change that was then
rolled back. `OutboxEventRecorder` is `Propagation.MANDATORY`, so calling it without a transaction
fails loudly rather than silently degrading to the inline behaviour.

The publisher polls rather than reacting to commits, because the poll is what makes it crash-safe:
anything staged but unsent is picked up on the next tick, including after the process died
mid-publish.

`claimUnpublished` uses `FOR UPDATE SKIP LOCKED`. **This is what makes multiple replicas safe** —
without it every instance races to publish the same rows and duplicates every event. H2 does not
support it, so tests set `devforge.outbox.use-skip-locked=false`; it must stay `true` in production.

### Delivery guarantee

**At-least-once.** A crash between a successful send and the row being marked published resends.
Marking first and sending after would silently *drop* events, which is strictly worse than a
duplicate a consumer can detect.

### Idempotent consumers

`processed_events` is keyed on `(eventId, consumerGroup)` — composite so two consumer groups each
process an event once. `IdempotentEventProcessor` writes the marker **in the same transaction as
the handler**, so a failing handler rolls the marker back and the event retries. Recording
completion first would turn any handler failure into a permanently skipped event.

Retention of these markers must comfortably exceed the topic's own retention. If a marker is pruned
while the event can still be redelivered, duplicate protection silently lapses.

### Retry and dead-lettering

Consumers use exponential backoff (1s → 30s, capped at 2 minutes total), then publish to the
topic's `.dlt`. The bound matters: unbounded retry on a poison message **blocks its partition**, so
one bad event stalls every event behind it.

Deserialization and payload-shape failures can never succeed on retry, so they bypass the backoff
and dead-letter immediately.

### Producer settings

`acks=all`, `enable.idempotence=true`, `max.in.flight=5`, unbounded retries within a 120s delivery
timeout. `acks=1` would lose events on a leader failover *after* the outbox had already marked them
published.

---

## 6. Schema evolution

| Change | How |
|---|---|
| Add an optional payload field | In place. Consumers ignore unknown fields. |
| Add a required field | New `version`, with consumers handling both until producers have all moved. |
| Remove or retype a field | **Breaking.** New topic (`.v2`), consumers migrated, then the old topic retired. |
| Rename an event type | New constant; emit both during transition. |

JSON with an explicit `version` is deliberate rather than Avro or Protobuf with a Schema Registry.
A registry is another service to run, secure and back up, and §23 warns against introducing it for
complexity alone. Revisit when cross-language consumers or payload size make it pay for itself.

---

## 7. Security

- **No credentials in payloads, ever.** An event is copied to every consumer and retained by its
  topic, so anything in a payload is effectively broadcast and persisted. Password hashes, tokens,
  TOTP secrets and recovery codes are excluded; a test asserts the signup payload contains neither
  the password nor its hash.
- **`tenantId` is an authorization boundary.** A consumer must not widen it — reading an event for
  tenant A must never produce a write visible to tenant B.
- The local broker is `PLAINTEXT` with no authentication. Production needs TLS plus SASL and
  per-service ACLs; that is **not yet configured** and is tracked in `docs/PROGRESS.md`.
- `auto.create.topics.enable` is **false** in Kubernetes, so a typo in a topic name fails loudly
  instead of silently creating a topic nobody consumes. It is `true` in local Compose for
  convenience.

---

## 8. Verification status

| Behaviour | Status |
|---|---|
| Envelope construction, validation, partition key | **Verified** — unit tested |
| Event staged atomically with the state change | **Verified** — rollback leaves no event |
| Recording without a transaction is refused | **Verified** |
| Payload carries no credential material | **Verified** |
| Duplicate handled once per consumer group | **Verified** |
| Failing handler leaves the event retryable | **Verified** |
| Publication to a real broker | **Verified** — `OutboxPublicationIntegrationTest`, in-process Kafka |
| Envelope survives the round trip (incl. `Instant` precision) | **Verified** — consumed and re-parsed |
| Partition key is the tenant; platform events fall back to event id | **Verified** |
| Ordering within a tenant | **Verified** — staged, drained and consumed in order on one partition |
| Duplicate delivery runs the handler once | **Verified** — against a broker, not just the guard in isolation |
| Non-retryable failure is dead-lettered | **Verified** — record lands on `<topic>.dlt` after one attempt |
| A poison event does not block its partition | **Verified** — the healthy event behind it is still processed |
| `SKIP LOCKED` behaviour with concurrent publishers | **UNVERIFIED** — needs PostgreSQL |
| Retry/backoff timing under a transient outage | **UNVERIFIED** — only the non-retryable path is exercised |
| Consumer lag, rebalance and replay | **UNVERIFIED** |
| Broker TLS, SASL and ACLs | **UNVERIFIED** — not configured; see §7 |

### How this is verified without Docker

`spring-kafka-test` starts a **real Kafka broker in-process** (KRaft mode), so these tests exercise
the actual producer configuration, serialisation, error handler and dead-letter recoverer. Nothing
is mocked. Testcontainers remains the right tool for broker-failover and multi-replica behaviour,
which an embedded single-broker cluster cannot show — notably `acks=all`, whose whole purpose is
surviving a leader change.

Two caveats worth keeping in view:

- The tests set `devforge.outbox.use-skip-locked=false`, because H2 has no
  `FOR UPDATE SKIP LOCKED`. Production runs on PostgreSQL with the skip-locked claim, and
  **without it concurrent publishers would duplicate every event** — so that path is still
  unverified.
- Each test publishes to a topic of its own. Kafka topics are append-only with no per-test
  truncation, and a fresh consumer group reading from `earliest` on a shared topic replays
  everything earlier tests left behind, which turns "exactly one event arrived" into a false pass.

No *production* consumer exists yet. The consuming side is proven to work, but the only subscriber
today is the test listener; real ones arrive with Phase 12 (notifications) and Phase 14 (analytics).
