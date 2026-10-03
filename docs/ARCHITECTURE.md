# Architecture

**Last updated:** 2026-10-03

What the system is, how the pieces fit, and why each significant choice was made. Status per feature
lives in `docs/PROGRESS.md`; the numbered decision table there (`AD-1`…`AD-18`) is the canonical
record and this document references rather than repeats it.

---

## 1. Shape of the system

```
                    browser (React 19 / Vite)
                             │
                   one origin, one port
                             │
                    ┌────────▼────────┐
                    │   api-gateway   │  :8080   single entry point
                    └────────┬────────┘          correlation ids, CORS
          ┌──────────────────┼──────────────────┬─────────────────┐
          │                  │                  │                 │
   ┌──────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐   ┌──────▼─────────┐
   │ auth-service│    │project-svc  │    │ task-service│   │notification-svc│
   │    :9001    │    │   :9002     │    │    :9003    │   │     :9011      │
   └──────┬──────┘    └──────┬──────┘    └──────┬──────┘   └──────┬─────────┘
          │                  │       ▲          │                 │
          │                  └───────┘          │                 │
          │              authorization check     │                 │
          │              (caller's own token)    │                 │
          │                                      │                 │
          └──────── outbox ──────────────────────┴───── Kafka ──────┘
                                                        (consumes)
```

Four services carry behaviour. Seven more exist as two-file scaffolds with a health endpoint:
ai, git, review, documentation, chat, deployment, analytics.

Each service owns its own schema. There are no foreign keys across a service boundary — a
cross-service reference is a plain `UUID` column, and the owning service is asked when a decision
depends on it.

### Things in the tree that do not do what their name says

- **`config-server` and `discovery-server`** are empty Spring Boot web apps. No
  `@EnableConfigServer`, no `@EnableEurekaServer`, no Spring Cloud dependency in either pom.
  `docker-compose.yml` sets `SPRING_CLOUD_CONFIG_URI` and `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` on
  every service, and **nothing reads either variable.** There is no service discovery and no central
  config; the gateway resolves services from static URLs. Treat them as `SCAFFOLDED` and do not
  write code that assumes otherwise.
- **Redis** appears in `docker-compose.yml` and `.env.example` but no code uses it.

---

## 2. Why a gateway, and why gateway-mvc

The frontend used to proxy to three services by path prefix, with the route map in
`vite.config.ts`. That worked only because Vite's dev server did the routing — nothing deployed has
a dev server, so the mapping had to live somewhere real. The gateway now owns it, and the browser
sees one origin, which is also what lets the HttpOnly refresh cookie work without cross-site
exemptions.

It uses `spring-cloud-starter-gateway-mvc`, not the reactive gateway: `common-library` is on every
service's classpath and brings `spring-boot-starter-web`, and mixing that with the reactive starter
leaves Boot unable to decide which web stack to start (`AD-11`).

**Route order is load-bearing.** Tasks and sprints are addressed *under* the project path but served
by task-service, so their patterns are declared before the broader `/api/v1/organizations/**` route.
Router functions are evaluated in declaration order, so reversing them silently sends task traffic
to project-service (`AD-12`).

Notifications are deliberately **not** nested under an organization: a notification can concern the
account itself — a password change, a disabled second factor — which belongs to no tenant.

---

## 3. Identity and tokens

Access and refresh tokens are both JWTs, separated by a `typ` claim. Without that distinction a
refresh token is a bearer token with a fortnight's life, and replaying it as an access token is
trivial (`AD-5`). Tests assert the confusion is rejected in both directions.

The refresh cookie is `HttpOnly`, `Secure`, `SameSite=Strict`, and **authenticates only at
`/api/v1/auth/refresh`** (`AD-9`). An ambient cookie accepted on every endpoint, with CSRF disabled,
is a CSRF vector.

Refresh tokens rotate on use. A replayed one is treated as theft: the whole session family is
revoked and a `RefreshTokenReuseDetected` event is raised. Revocation runs in a separate
`REQUIRES_NEW` transaction in its own bean — the first implementation revoked and then threw in the
same transaction, so the revocation rolled back with it, and Spring ignores propagation on
self-invocation.

Verification of tokens lives in `common-security` (`JwtTokenVerifier`,
`BearerTokenAuthenticationFilter`), so every resource server validates signature, issuer, expiry and
type identically. Services verify; only auth-service issues.

Email verification exists but is **not enforced** — an account can sign in as soon as it is created.
That was a deliberate product decision, not an oversight.

---

## 4. Multi-tenancy and authorization

Organization → projects → tasks. **Membership rows are the only authorization authority.** Not a
token claim, not a path parameter, not a request body — all of those are values the client controls.
`AuthenticatedUser` therefore carries identity only and deliberately has no organization id.

Two rules that are tested rather than assumed:

- A resource in another tenant returns **404, not 403**. A 403 confirms the id exists, which makes
  the endpoint an oracle for enumerating ids.
- No endpoint accepts a user id to scope a read to. The subject comes from the token
  (`AD-17`). `/users/{id}/notifications` would make the id client-supplied, and then every method
  has to remember to check it.

`project-service` has 23 tenant-isolation tests; `notification-service` has three cross-user tests.

### Cross-service authorization

task-service does not know who may touch a project. It asks project-service, **forwarding the
caller's own bearer token** (`AD-10`). Two alternatives were rejected:

- *Replicating membership into task-service* — two sources of truth, and a stale replica in an
  authorization path is a cross-tenant leak waiting to happen.
- *A service credential* — would grant task-service blanket access to every project, reducing the
  check to one nobody enforces.

The call **fails closed**: an unreachable project-service yields 503, never access. 401, 403 and 404
from project-service are all collapsed into "project not found", so the error does not leak which
of the three it was.

---

## 5. Events

Kafka is the event backbone. RabbitMQ was removed: it was declared in five places and called from
none — see `docs/EVENT_CATALOG.md` §1 for the §18 justification.

**Transactional outbox.** A service writes the event to its own `outbox_events` table inside the
business transaction, so the state change and the event commit or roll back together. A scheduled
publisher drains the table to Kafka afterwards. Publishing inline instead means either losing the
event when the broker is briefly unavailable after a successful commit, or announcing a change that
was rolled back.

The guarantee is therefore **at-least-once**, and consumers must deduplicate. They do, on
`(eventId, consumerGroup)` in a `processed_events` table, with the marker written in the *same*
transaction as the handler so a failing handler leaves the event retryable rather than recorded as
done.

Other properties, and why:

| Choice | Reason |
|---|---|
| `acks=all`, idempotent producer | with `acks=1` a leader crash after acknowledging loses an event the outbox has already marked published |
| Partition key is the tenant | one organization's events stay ordered; ordering across tenants does not matter |
| Topic per bounded context, not per event type | a topic per type multiplies partitions for no ordering benefit |
| Version in the topic name (`devforge.tasks.v1`) | a breaking payload change ships as a new topic that old and new consumers can straddle |
| `FOR UPDATE SKIP LOCKED` to claim rows | without it, two publishers duplicate every event |
| Bounded retry then dead-letter | an unbounded retry on a poison message blocks its partition, so one bad event stalls every event behind it |

Verified against a real in-process broker: publication, ordering, deduplication on redelivery,
dead-lettering, and that a healthy event queued behind a poison one still gets processed.

---

## 6. Frontend

React 19, TypeScript, Vite, TanStack Query for server state, Zustand for client state, Tailwind.

The split is deliberate: TanStack Query owns anything that came from the API, including its loading
and error states; Zustand holds only what the client itself knows (theme, session). Mixing them
means server data with no cache invalidation story.

`App.tsx` owns `QueryClientProvider`. It previously lived in neither `App.tsx` nor `main.tsx`, so
every screen calling `useQuery` threw on mount — and the unit tests passed because they wrapped
`App` in their own provider, which is precisely the composition the real entry point lacked. The
tests now deliberately supply no provider, so that gap cannot reopen.

The axios layer refreshes on 401 — except on the auth endpoints themselves. A wrong password used to
trigger a refresh attempt, which failed, cleared storage and hard-redirected to `/login`, discarding
the error the form had just set; the user saw a blank form reload with no explanation.

---

## 7. Configuration and profiles

| Profile | Database | Broker | Purpose |
|---|---|---|---|
| default | PostgreSQL via `${DEVFORGE_DB_URL}`, no fallback | required | real deployments |
| `local` | PostgreSQL on localhost | localhost:9092 | development with real infrastructure |
| `standalone` | H2 file in `./data/` | **none** | `openapp.bat`; no PostgreSQL, Kafka or Docker needed |
| test | H2 in-memory, PostgreSQL mode | in-process, where needed | the suite |

The default profile has **no fallback values for credentials**. A wrong or missing address should
refuse to start rather than silently route nowhere, which is harder to diagnose.

Every profile runs the **real Flyway migrations** with `ddl-auto: validate`, including the test
profile (`AD-3`). That validates the migrations and the JPA mappings against each other on every
run, with no Docker dependency. Testcontainers remains the right tool for true PostgreSQL behaviour.

`standalone` is for convenience and is clearly marked unsafe: the JWT signing key is a known
development value, refresh cookies are not `Secure`, and mail is written to a dev mailbox instead of
being sent.

---

## 8. What is not here yet

The AI platform (§6, §15, §16) — generation, review, RAG, agents — is the largest remaining body of
work and nothing of it exists. Nor do git, review, documentation, chat, deployment or analytics
beyond their health endpoints.

Two gaps worth naming because they are prerequisites rather than features:

- **No code-execution sandbox** (§37). Nothing currently executes developer code anywhere, which is
  the correct state, but the IDE and AI phases cannot do anything real until there is a sandbox that
  is not the application host.
- **No broker authentication.** Kafka TLS, SASL and ACLs are unconfigured.
