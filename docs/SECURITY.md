# Security

**Last updated:** 2026-10-03

The controls that are implemented, how to verify them, and what is still missing. Threats and
reasoning live in `docs/THREAT_MODEL.md`.

Nothing below is described as working unless a test or a recorded manual check covers it. Items
marked **UNVERIFIED** have been written but never executed in this environment.

---

## 1. Authentication

| Control | Status | Where |
|---|---|---|
| Passwords hashed with BCrypt | Implemented | `PasswordEncoder` bean, auth-service |
| Access/refresh separated by a `typ` claim | Implemented, tested both directions | `JwtTokenVerifier` |
| Refresh-token rotation | Implemented | `RefreshTokenService` |
| Reuse of a rotated refresh token revokes the session family | Implemented | `SessionRevocationService` |
| Refresh cookie `HttpOnly` + `Secure` + `SameSite=Strict` | Implemented; relaxed only in the test profile, which runs over plain HTTP | auth-service config |
| Refresh cookie accepted **only** at `/api/v1/auth/refresh` | Implemented | auth-service security config |
| Access token in an `HttpOnly` cookie for the browser, never in script-readable storage or a browser response body | Implemented; verified in the browser suite | `JwtTokenProvider`, `AccessCookieFilter` |
| Cookie-authenticated writes require `X-Requested-With` (CSRF) | Implemented, refresh included | `AccessCookieFilter` |
| TOTP second factor (RFC 6238) | Implemented, verified against the published test vectors | `TotpService` |
| MFA recovery codes, single-use | Implemented | `MfaBackupCode` |
| Per-account rate limiting on password and MFA attempts | Implemented, time-windowed | `LoginAttemptService` |
| Per-device sessions, individually revocable | Implemented | `/api/v1/auth/sessions` |
| OAuth sign-in | **UNVERIFIED** — needs real provider credentials | conditional on `ClientRegistrationRepository` |
| Personal access tokens for git: 256 random bits, stored as SHA-256, shown once, always expiring, revocable, stopped at once by locking the account | Implemented, tested | `PersonalAccessTokenService` |
| A personal token works for git only: exchanged for a 5-minute access token on an internal endpoint that refuses proxied requests, and never returned to the client | Implemented; unreachability from outside checked on kind, path tricks included | `TokenExchangeController`, `GitBasicAuthenticationFilter` |

A personal token has the owner's project roles for git, so a leaked one can read and push wherever
they can. It cannot call the API, change the account or mint more tokens. It survives a password
change, as GitHub's do; revoking it, or locking the account, is how it is stopped.

Lockout is **time-windowed, not sticky**: a rolling window of failures locks the account for a
period rather than permanently. A permanent lock is a denial-of-service primitive — anyone who knows
an email address can disable the account.

Email verification is **required by default** (`DEVFORGE_REQUIRE_EMAIL_VERIFICATION`, true in the
production profile): an account cannot sign in until it follows the link sent to its address. Access
tokens carry `email_verified`, and anything that acts on the address checks it - an organization
invitation is shown to, and accepted by, only an account whose token carries the invited address as
verified, so registering under someone else's address does not get you their invitations.

Turning verification off (local development and CI do) marks new accounts verified at signup. That
deployment then trusts every address as typed, invitations included; do not turn it off where signup
is open to the public.

---

## 2. Authorization

| Control | Status |
|---|---|
| Every protected endpoint requires an authenticated principal | Implemented (`@PreAuthorize("isAuthenticated()")` at controller level) |
| Tenant scope resolved from membership rows server-side | Implemented |
| Cross-tenant access returns 404, not 403 | Implemented, 23 tests in project-service |
| Cross-user access returns 404, not 403 | Implemented, 3 tests in notification-service |
| No endpoint accepts a user id to scope a read | Implemented by design |
| Cross-service authorization forwards the caller's own token | Implemented, fails closed (503) if the authority is unreachable |
| Project roles enforced on writes in every service | Implemented: VIEWER is read-only everywhere; deleting a repository needs ADMIN or TEAM_LEAD. A VIEWER test in each of task, git, review, documentation and chat |

**Roles were checked only by project-service until this was fixed.** Every other service asked
project-service "may this caller see the project?" and treated yes as permission to do anything,
so a VIEWER - defined as read-only - could create and delete tasks, commit code, merge pull requests
and delete repositories. The requirements matrix even claimed task-service enforced roles; it did
not. `ProjectAccessClient` now takes the access level a call needs and reads the caller's role from
the same project-service response that proves membership, failing closed on a missing or unknown
role. A member refused for their role gets 403, which reveals nothing: they can see the project.

`AuthenticatedUser` carries **identity only** — no organization or project id. A tenant id taken from
a token the client supplies is a value the client controls.

---

## 3. Transport and browser controls

**Only the gateway applies a CORS policy.** Behind it, a service never talks to a browser
directly — it receives a forwarded request that still carries the browser's `Origin` header.
A service with no configured allow-list therefore registers **no CORS filter at all**, which is
the safer of the two options: CORS only ever *grants* cross-origin access, so a service without
it is one a browser cannot read cross-origin.

The first version of this hardening got that wrong, and the mistake is worth recording. An empty
allow-list does not mean "no cross-origin access" to a `CorsFilter`; it means "reject anything
carrying an `Origin` header". Since `CorsConfig` is on every service's classpath and only the
gateway set the property, **every forwarded browser request was answered 403** — while `curl`,
which sends no `Origin`, worked perfectly. Six live smoke tests passed against the broken build.
Only the browser suite caught it. Two tests now assert the filter is absent when unconfigured.

**CORS takes an explicit origin allow-list, and a `*` entry throws at startup.** This was a real
vulnerability, not a style preference: the original configuration used
`addAllowedOriginPattern("*")` together with `setAllowCredentials(true)`. A *pattern* makes Spring
echo the caller's `Origin` back in the response, which sidesteps the browser rule forbidding a
wildcard with credentials — so **any website could read a signed-in user's data**. `CorsConfig` is on
every service's classpath, so it applied to all of them. Five tests cover it, including that a
wildcard refuses to boot.

Response headers are set at the gateway — see below.

### Access token cookie and CSRF

The access token used to sit in `localStorage`, so any injected script could read it and use it
from anywhere for its 15-minute life. It is now an `HttpOnly` cookie, path `/api`, that script
cannot read. auth-service sets it; the gateway's `AccessCookieFilter` copies it into the
`Authorization` header, so no service and no service-to-service call changed.

A cookie the browser attaches by itself brings CSRF back, so the same filter refuses any
non-GET request that carries either auth cookie but not `X-Requested-With: XMLHttpRequest`. Another
site cannot add that header: a form cannot, and a cross-origin fetch needs a preflight the CORS
allow-list refuses. `SameSite` on the cookies is the first layer, this the second.

The token is also kept out of response bodies for the browser, or the cookie would protect
nothing: a script could call `/refresh` and read the new token. The SPA sends the header, and
auth-service omits the token from the body when it is present. A script that leaves the header off
to get the token back is refused by the gateway first, because refresh always carries the refresh
cookie. API clients that send no header and no cookie get the token in the body as before.

What this does **not** do: an XSS can still make requests as the user while the page is open. It
can no longer take the credential away. The SPA's CSP (below) is the layer that makes injecting
one harder.

---

### Kafka authentication

Every Kafka client — producer, consumer and admin — authenticates with SASL when
`devforge.kafka.sasl.username` and `.password` are set. The default is SCRAM-SHA-512 over TLS
(`SASL_SSL`); PLAIN over plain TCP would send the password in the clear. One
`EnvironmentPostProcessor` in common-events applies it to every service, and builds the JAAS line
itself, escaped, so a quote in a password cannot add options to it.

`devforge.kafka.require-authentication=true` makes a service refuse to start without credentials
instead of connecting anonymously; the Kubernetes ConfigMap sets it. Verified against a real
broker running with SASL required: valid credentials publish and consume, no credentials and a
wrong password are refused, and a real service jar refuses to start when credentials are missing.

The first version of that test passed against a broker with **no** authentication: Spring's
embedded broker silently replaced the SASL listener with PLAINTEXT, and the "refused" case
failed for an unrelated reason. The test now sets the listener per node and asserts *why* the
unauthenticated client fails.

The compose and Kubernetes brokers accept only SASL over TLS. Each stack generates its own CA and
broker certificate (`infrastructure/docker/kafka/certs.sh`, `create-secrets.sh`) and discards the
CA's private key after signing, so nothing can mint another certificate it would accept; no private
key is shared or committed. Services trust that CA through `devforge.kafka.ssl.truststore-location`
with hostname verification on. `KafkaTlsIntegrationTest` shows a client trusting the CA connects, one
that does not fails the handshake, and a plaintext client gets nowhere; CI checks the plaintext
refusal against the compose broker.

**UNVERIFIED:** SCRAM, and certificates issued by a real PKI rather than the stack's own CA.

### Gateway rate limiting

The unauthenticated auth endpoints (login, MFA, signup, password reset, resend) are limited **per
client address**, 30 a minute by default, answering 429 with `Retry-After`. auth-service already
limits per *account*, which stops guessing one password but not spraying one password across
thousands of accounts, nor a signup or reset-email flood; this covers those. Verified live.

Two limitations, stated: the count is per gateway instance, and `X-Forwarded-For` is ignored unless
`devforge.rate-limit.trust-forwarded-for` is set, because a client can write that header itself and
would otherwise choose its own bucket. Behind a load balancer, enable it only if the balancer
overwrites the header. The standalone profile raises the limit to 1000, since every browser test
signs in from 127.0.0.1.

### Response headers

Every gateway response carries `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`
and `Cache-Control: no-store`, plus HSTS when served over TLS. These are for API responses; the
single-page app's HTML is not served by the gateway; it has its own policy.

### The single-page app's CSP

`default-src 'self'; script-src 'self'; style-src 'self' 'nonce-…'; img-src 'self' data:;
font-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri
'self'; form-action 'self'` — no `'unsafe-inline'` and no `'unsafe-eval'` anywhere, so an
injected `<script>` or inline handler does not run.

The style nonce exists for the code editor: CodeMirror injects `<style>` elements, which the
policy otherwise blocks. nginx puts its per-request `$request_id` in the header and, through
`sub_filter`, into a `<meta>` tag in `index.html`, which is never cached; the editor hands it to
CodeMirror. A fresh value per response means injected markup cannot know it in advance. The
browser suite's one CSP exemption is narrow and stated in `e2e/fixtures.ts`: Chrome's editing
engine tries to add a style attribute when typed text replaces a selection inside the editor;
the policy blocks it and nothing is lost. It lives in `frontend/security-headers.conf`, which
nginx includes and `vite preview` reads, so there is one copy.

It is verified, not assumed. The browser suite runs against the production build under this
policy, and any test in which the browser reports a CSP violation fails. CI also starts the
real nginx image and checks the headers on `/`, `/index.html`, a client-side route and an asset.

Two defects found on the way. The previous nginx config set the headers only at server level;
nginx drops inherited `add_header` in any location that sets its own, so by that rule
`index.html` and the assets carried no CSP at all (never observed — the image had never run).
And the app loaded its font from Google Fonts, which the old policy also blocked, so production
silently fell back to system fonts. The font is now self-hosted, which also stops every visitor's
address going to Google; a test asserts it loads.

Two defects found by checking the live response rather than the unit test: the 429 carried none of
these headers, because the rate limiter answered before the headers filter ran; and proxied
responses carried each header twice, once from the gateway and once from the service's Spring
Security. A repeated `X-Frame-Options` can be treated as invalid and ignored. Both are fixed: the
headers filter runs first, and these header names replace rather than append.

## 4. Data handling

- **Events carry no credential material.** `UserPasswordReset` carries only the user id and the
  fact a reset happened. An event is copied to every consumer and retained by its topic, so a secret
  in a payload is a secret in a log-shaped store.
- **The catch-all exception handler returns a `traceId`, never `ex.getMessage()`.** Echoing the
  message leaked raw Hibernate internals to clients; details are logged server-side against the same
  id (`AD-8`).
- **Password reset does not reveal whether an account exists** (§27). The response is identical
  either way.
- **Correlation ids from clients are validated, not trusted.** The value reaches log files, so an
  unconstrained one is a log-injection and unbounded-growth risk; anything not matching
  `^[A-Za-z0-9_-]{1,64}$` is replaced.
- **No secrets in the repository.** `.env.example` is tracked; real env files, `*.pem`, `*.key`,
  `*.p12` and `*.jks` are ignored. The `standalone` profile's signing key is a known development
  value and is labelled as such everywhere it appears.

---

## 5. Verification

```bash
mvn -B -ntp -f backend/pom.xml test     # 186 tests, including the security cases above
cd frontend && npm run test:e2e         # 22 browser tests against a running stack
```

The security-relevant suites specifically:

| Suite | Covers |
|---|---|
| `AuthSecurityControlsTest` | rate limiting, lockout window, token-type confusion |
| `AuthFlowIntegrationTest` | signup, login, refresh, logout, reset end to end |
| `MfaAndSessionTest` | TOTP enrolment and verification, session revocation |
| `TenantIsolationTest` | cross-tenant reads and writes, 404-not-403 |
| `NotificationApiTest` | cross-user reads, writes and deletes |
| `CorsConfigTest` | explicit origins, wildcard refuses to boot, **no filter when unconfigured** |
| `CorrelationIdFilterTest` | untrusted inbound id is replaced |
| `SecretRulesTest` | credentials detected, placeholders ignored, findings never quote the secret |
| `ReviewApiTest` | unreadable content records `FAILED` rather than a pass; dismissal needs a reason |
| `RoutePrecedenceTest` | a nested path is never routed to the wrong service |
| `JwtConfigValidationTest` | a weak or missing signing key refuses to boot |

---

## 6. Known gaps

Ordered by how much they matter.

1. **No code-execution sandbox (§37).** Nothing executes developer code today, which is the correct
   state — but this is a hard prerequisite for the IDE and AI phases, and must never run on the
   application host. Now specified in `docs/SANDBOX.md`: twelve guarantees, each with the escape
   attempt that must fail. Not implemented, because no container runtime or hypervisor exists in
   this environment and a sandbox that cannot isolate is worse than none.
2. **Kafka ACLs cover compose and Kubernetes, not the standalone profile.** Each service has its own
   identity and the broker denies anything not granted in `infrastructure/docker/kafka/setup.sh`,
   which the Kubernetes setup Job runs too; both are exercised in CI, over TLS. The standalone
   profile has no broker.
3. **No secret-management integration.** Secrets come from environment variables; there is no vault,
   and no rotation story.
4. **Container hardening is verified in CI, not against an attacker.** Every image runs as a
   non-root numeric user, and the Kubernetes pods enforce `runAsNonRoot`. Image scans are
   **blocking**: auth-service's high/critical advisories went 64 (Boot 3.4.0) -> 48 (3.4.13) -> 14
   (3.5.16) -> 0, the last step by overriding Jackson, Netty, Tomcat, the PostgreSQL driver and lz4
   ahead of the Boot BOM (`backend/pom.xml`). Two Spring Framework advisories with no 6.2 fix are
   accepted in `.trivyignore`, each with the reason it does not apply here. The containers run
   together against real PostgreSQL, Kafka and Redis under compose and on kind. On Kubernetes every
   DevForge container also has a read-only root filesystem (writable `/tmp` and data volumes only),
   the runtime's default seccomp profile, no capabilities and no privilege escalation, and the kind
   job runs them that way. Compose applies the same - read-only root, writable `/tmp`, no
   capabilities, `no-new-privileges` - and CI checks each running container and that a write to its
   root filesystem is refused. The third-party images (PostgreSQL, Kafka, Redis, Prometheus,
   Grafana) keep their defaults.
5. **Dependency and image scanning blocks.** CI fails on any high or critical npm advisory (build
   tooling included), on any Trivy finding in the Maven and npm trees or a committed secret, and on
   any fixable high or critical advisory in any of the eleven images. Dependabot proposes weekly
   updates. npm audit went from 23 advisories (4 critical) to 0 by upgrading to Vite 8, Vitest 5,
   typescript-eslint 8, react-router 7 and Tailwind 4; Tailwind 4 was checked by pixel-comparing key
   pages before and after.
6. **The audit trail is tamper-evident, not tamper-proof.** Project, membership, task, repository
   and pull request changes are recorded by analytics-service from the events, readable by project
   admins, in a SHA-256 hash chain per project. Re-hashing it (the "Verify integrity" button, or
   `GET .../analytics/audit/verification`) finds an edited, reordered, deleted or truncated entry.
   Someone with write access to the database can still recompute a whole chain: that is caught only
   by comparing the head hash with a copy recorded outside DevForge, and nothing records one
   automatically - there is no external sink or signing key. Auth events stay in auth-service's own
   audit table, which is not chained. Entries from before the chain are reported as unchained.

---

## Reporting

This is not a deployed product and has no security contact. If you are reading this in a fork that
*is* deployed, replace this section with a real disclosure address before shipping.
