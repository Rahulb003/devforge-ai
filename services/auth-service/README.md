# Auth Service

Identity for the DevForge AI platform: accounts, credentials, tokens and sessions.

## What it does

- Signup and sign-in, by username **or** email
- JWT access and refresh tokens, separated by a `typ` claim so a refresh token cannot be
  replayed as an access token
- Refresh-token rotation with reuse detection, which revokes the session family
- TOTP two-factor (RFC 6238) with recovery codes
- Per-device sessions: list, revoke one, revoke the others
- Password reset, and per-account rate limiting on password and MFA attempts
- OAuth sign-in, wired conditionally so the service still boots without provider credentials
- Domain events staged through the transactional outbox

92 tests cover these paths. Note that email verification is deliberately **not** enforced:
an account can sign in as soon as it is created.

## Running locally

```bash
cd services/auth-service
./mvnw spring-boot:run
```

## Docker

```bash
docker build -t devforge-ai/auth-service .
```
