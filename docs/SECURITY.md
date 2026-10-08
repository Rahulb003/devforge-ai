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
| TOTP second factor (RFC 6238) | Implemented, verified against the published test vectors | `TotpService` |
| MFA recovery codes, single-use | Implemented | `MfaBackupCode` |
| Per-account rate limiting on password and MFA attempts | Implemented, time-windowed | `LoginAttemptService` |
| Per-device sessions, individually revocable | Implemented | `/api/v1/auth/sessions` |
| OAuth sign-in | **UNVERIFIED** — needs real provider credentials | conditional on `ClientRegistrationRepository` |

Lockout is **time-windowed, not sticky**: a rolling window of failures locks the account for a
period rather than permanently. A permanent lock is a denial-of-service primitive — anyone who knows
an email address can disable the account.

Email verification is **not enforced**. An account can sign in as soon as it is created. This is a
deliberate product decision; it means an unverified address is an account that works, and any future
feature that trusts "verified" must check the flag rather than assuming.

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

Other headers and settings are inherited from Spring Security defaults. A deliberate CSP, HSTS and
frame-ancestors policy has **not** been written — see §6.

---

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
single-page app's HTML is not served by the gateway, and whatever serves it needs its own CSP.

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
2. **Kafka has no authentication.** TLS, SASL and ACLs are unconfigured. Any process that can reach
   the broker can read every tenant's events.
4. **No secret-management integration.** Secrets come from environment variables; there is no vault,
   and no rotation story.
5. **Docker and Kubernetes hardening is UNVERIFIED.** The images are non-root with healthchecks on
   paper; none has ever been built or run here.
6. **No automated dependency or container scanning** in CI.
7. **Audit logging exists for auth events only.** There is no audit trail for project, task or
   notification changes.

---

## Reporting

This is not a deployed product and has no security contact. If you are reading this in a fork that
*is* deployed, replace this section with a real disclosure address before shipping.
