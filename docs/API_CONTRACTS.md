# API contracts

**Last updated:** 2026-10-03

Every endpoint that exists today: 68 real endpoints across five services, plus a health endpoint on
each of the eleven. Generated from the controllers and checked against them; if this disagrees with
the code, the code is right and this is stale.

Interactive documentation is served per service at `/swagger-ui.html` when it is running.

---

## 1. Conventions that apply to everything

**Base URL.** Everything goes through the gateway on `:8080`. Service ports are an implementation
detail and not a supported entry point.

**Envelope.** Every response is wrapped:

```json
{ "success": true, "data": { }, "message": "Optional human-readable note" }
```

An error is a **different shape** — `ApiError`, not the envelope — and never echoes an exception
message:

```json
{
  "timestamp": "2026-10-03T12:34:56Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/auth/signup",
  "traceId": "b7c1…",
  "details": ["email: must be a valid email"]
}
```

`details` is a list of strings, not a field-keyed object, so a client wanting to attach messages to
form fields has to parse them. That is a wart worth fixing before any external consumer depends on it.

For an unhandled failure, `message` is generic and `traceId` is the only way to correlate with the
server-side log — the exception message is deliberately never sent (`AD-8`).

**Authentication.** `Authorization: Bearer <access token>` on everything except the public auth
endpoints listed below. The refresh token travels as an `HttpOnly` cookie and is accepted **only** at
`POST /api/v1/auth/refresh`.

**Status codes.**

| Code | Meaning here |
|---|---|
| 200 | success |
| 201 | created |
| 204 | success, no body |
| 400 | validation failure; `errors` names the fields |
| 401 | missing, malformed, expired or wrong-type token |
| 403 | authenticated but not permitted — **not** used for another tenant's or user's data |
| 404 | not found, **or** found but not yours. The two are deliberately indistinguishable |
| 409 | conflict — duplicate email, illegal state transition |
| 503 | a required downstream authority is unreachable |

**404 rather than 403 for other people's data is a security property, not an inconsistency.** A 403
confirms the id exists, which turns the endpoint into an oracle for enumerating ids.

**Paging.** Spring's `Pageable`: `?page=0&size=20`. Responses carry `content`, `totalElements`,
`totalPages`, `number`, `size`.

**Correlation.** Send `X-Correlation-Id` to have it threaded through the logs and any events the
request produces. It must match `^[A-Za-z0-9_-]{1,64}$` or the gateway replaces it. One is generated
if absent, and returned on the response either way.

---

## 2. auth-service

### Public

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/auth/signup` | 201. `firstName`, `lastName`, `username`, `email`, `password`, `organization`. 409 on duplicate email or username |
| POST | `/api/v1/auth/login` | `usernameOrEmail` + `password`. Either identifier works. Returns an access token, or an MFA challenge if enrolled |
| POST | `/api/v1/auth/login/mfa` | completes a challenge with a TOTP or recovery code |
| POST | `/api/v1/auth/refresh` | the **only** endpoint that reads the refresh cookie. Rotates it; a replayed token revokes the session family |
| POST | `/api/v1/auth/forgot-password` | always 200, identical whether or not the account exists (§27) |
| POST | `/api/v1/auth/reset-password` | `token` + `password` |
| POST | `/api/v1/auth/verify-email` | verification is **not enforced**; an account can sign in before it |
| POST | `/api/v1/auth/resend-verification` | |

### Authenticated

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/auth/me` | the current user |
| PATCH | `/api/v1/auth/me` | first and last name, time zone (IANA id), language (`en`, `en-GB`); only the fields given change. Username and email are not editable here |
| POST | `/api/v1/auth/password` | `currentPassword`, `newPassword` (signup rules, must differ). A wrong current password counts toward the sign-in lockout. Signs out every other session and notifies the owner |
| POST | `/api/v1/auth/logout` | revokes the refresh token server-side |
| GET | `/api/v1/auth/mfa/status` | |
| POST | `/api/v1/auth/mfa/enrol` | returns a secret and otpauth URI; enrolment is two-step |
| POST | `/api/v1/auth/mfa/confirm` | confirms with a code; only now is MFA active |
| POST | `/api/v1/auth/mfa/disable` | requires a current code |
| POST | `/api/v1/auth/mfa/backup-codes` | regenerates; single-use, shown once |
| GET | `/api/v1/auth/sessions` | one row per device |
| DELETE | `/api/v1/auth/sessions/{sessionId}` | |
| POST | `/api/v1/auth/sessions/revoke-others` | keeps the current session |
| GET | `/api/v1/auth/tokens` | the caller's personal access tokens: name, prefix, dates. Never the token |
| POST | `/api/v1/auth/tokens` | 201. `name` (1-100), `expiresInDays` (1-365, default 90). Returns the token **once**; at most 50 active |
| DELETE | `/api/v1/auth/tokens/{tokenId}` | revokes; 404 for another user's token or one already revoked |

### Internal (not routed by the gateway)

| Method | Path | Notes |
|---|---|---|
| POST | `/internal/v1/tokens/exchange` | `{token}` to `{accessToken}` valid five minutes, for git-service only. 401 for any unusable token or account, without saying which; 404 if the request carries proxy headers, i.e. came through nginx or the gateway |

### Development only

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/dev/mailbox` | messages the dev mail provider captured. **Only present when that provider is active** |
| DELETE | `/api/v1/dev/mailbox` | empties it |

---

## 3. project-service

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/organizations` | only organizations the caller belongs to |
| POST | `/api/v1/organizations` | 201; the creator becomes owner |
| GET | `/api/v1/organizations/{organizationId}` | |
| DELETE | `/api/v1/organizations/{organizationId}` | owner only |
| GET | `/api/v1/organizations/{organizationId}/projects` | paged |
| POST | `/api/v1/organizations/{organizationId}/projects` | 201 |
| GET | `/api/v1/organizations/{organizationId}/projects/{projectId}` | |
| PATCH | `/api/v1/organizations/{organizationId}/projects/{projectId}` | partial update |
| POST | `/api/v1/organizations/{organizationId}/projects/{projectId}/archive` | reversible; distinct from delete |
| DELETE | `/api/v1/organizations/{organizationId}/projects/{projectId}` | |
| GET | `/api/v1/organizations/{organizationId}/projects/{projectId}/members` | |
| POST | `/api/v1/organizations/{organizationId}/projects/{projectId}/members` | |
| DELETE | `/api/v1/organizations/{organizationId}/projects/{projectId}/members/{userId}` | |

The organization id in the path is **not** trusted as proof of access — membership is checked
server-side on every call. It is in the path so a lookup is scoped by both ids and an id from another
tenant cannot resolve.

---

## 4. task-service

All under `/api/v1/organizations/{organizationId}/projects/{projectId}`. task-service does not hold
project membership; it asks project-service, forwarding the caller's own token. If project-service is
unreachable the call fails **closed** with 503.

| Method | Path | Notes |
|---|---|---|
| GET | `…/tasks` | paged |
| POST | `…/tasks` | 201. Allocates a per-project `taskNumber` starting at 1 |
| GET | `…/tasks/board` | the whole board in one request, so a drag needs no round trip per column |
| GET | `…/tasks/{taskId}` | |
| PATCH | `…/tasks/{taskId}` | |
| DELETE | `…/tasks/{taskId}` | |
| POST | `…/tasks/{taskId}/assign` | `assigneeId`, or null to unassign. Unassigning raises no event |
| POST | `…/tasks/{taskId}/move` | `status` + optional `position`; renumbers both columns |
| GET | `…/tasks/{taskId}/comments` | |
| POST | `…/tasks/{taskId}/comments` | |
| DELETE | `…/tasks/{taskId}/comments/{commentId}` | |
| POST | `…/tasks/{taskId}/labels` | |
| DELETE | `…/tasks/{taskId}/labels?label=<name>` | the label is a query parameter |
| GET | `…/sprints` | |
| POST | `…/sprints` | |
| POST | `…/sprints/{sprintId}/start` | 409 if another sprint is active |
| POST | `…/sprints/{sprintId}/complete` | |

Statuses: `BACKLOG`, `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `BLOCKED`, `DONE`.
Priorities: `LOWEST` … `HIGHEST`. Types: `TASK`, `BUG`, `STORY`, `EPIC`.

---

## 5. git-service

All under `/api/v1/organizations/{organizationId}/projects/{projectId}/repositories`. Project
membership is checked with project-service, forwarding the caller's own token; if it is
unreachable the call fails **closed** with 503.

These are **self-hosted** repositories, served by JGit. This is not a GitHub or GitLab
integration — that needs provider credentials and does not exist yet.

| Method | Path | Notes |
|---|---|---|
| POST | `…/repositories` | 201. `name`, optional `description` and `initialBranch` (default `main`). 409 on a duplicate name within the project |
| GET | `…/repositories` | paged |
| GET | `…/repositories/{id}` | `empty` is true until the first commit |
| PATCH | `…/repositories/{id}` | description only |
| DELETE | `…/repositories/{id}` | removes the row **and** the git objects |
| GET | `…/repositories/{id}/branches` | |
| POST | `…/repositories/{id}/branches` | 201. `name`, optional `fromRef`. 409 if it exists |
| GET | `…/repositories/{id}/commits?ref=` | newest first, paged |
| GET | `…/repositories/{id}/tree?ref=&path=` | one directory level, directories first |
| GET | `…/repositories/{id}/blob?ref=&path=` | `binary` true means `content` is null rather than mangled; `truncated` true past the size limit |
| GET | `…/repositories/{id}/diff?from=&to=` | per-file change type and line counts |
| POST | `…/repositories/{id}/files` | 201. Commits one file: `path`, `content`, `message`, optional `branch` |

### Git's smart HTTP protocol

`git clone https://<host>/api/v1/git/{organizationId}/{projectId}/{repositoryId}.git`, with any
username and a personal access token as the password (HTTP Basic). The token is exchanged at
auth-service for a short-lived access token, and from there the rules are the REST API's:

- clone and fetch need project membership; push needs a role that can write (not VIEWER), else 403;
- a repository outside the caller's projects is 404, the same as one that does not exist;
- no credentials, or a token that is unknown, revoked, expired or whose account is locked: 401
  with a `WWW-Authenticate: Basic` challenge, so git prompts;
- only `refs/heads/*` and `refs/tags/*` are writable, branch names follow the same rules as the API;
- the default branch cannot be deleted or force-pushed, and while the repository requires approvals
  it changes only through a merged pull request; other branches may be force-pushed and deleted;
- a push is at most 100 MB, an object at most 50 MB (`devforge.git.max-push-bytes`,
  `max-object-bytes`), and every received object is checked;
- the "dumb" protocol is off, so repository files (config, hooks) are never served directly;
- each pushed branch publishes one `RepositoryPushed` with `via: "git"` and `commitCount`.

**Refs and paths are query parameters, not path segments.** A file path contains slashes, so a
path segment would need a wildcard mapping or encoding that Spring normalises before the handler
sees it — both of which make traversal checks harder to reason about.

Validation is strict and **rejects rather than sanitises**, because sanitising invites the bypass
where stripping `../` from `....//` yields `../`. A 400 is returned for traversal (`../`, `..`,
`./`, empty segments), git internals (`.git/…`, any case), absolute and drive-letter paths,
control characters, and refs using git's own syntax (`^`, `~`, `..`, `@{}`, a leading `-`).
A leading `/` is treated as repository-relative rather than rejected.

## 6. notification-service

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/notifications` | `?unreadOnly=true`, paged, newest first |
| GET | `/api/v1/notifications/unread-count` | `{ "unread": 3 }`. Separate from the list because the UI polls it far more often |
| POST | `/api/v1/notifications/{notificationId}/read` | idempotent — a second call does not move the timestamp |
| POST | `/api/v1/notifications/{notificationId}/unread` | |
| POST | `/api/v1/notifications/read-all` | `{ "marked": 2 }` |
| DELETE | `/api/v1/notifications/{notificationId}` | |

**No endpoint takes a user id, by design.** The recipient is the token subject, so there is no
parameter to tamper with. Responses do not include `recipientId` either — nothing should teach a
client that it is a value it can send.

`category` is `SECURITY`, `TASK`, `PROJECT` or `SYSTEM`. `link` is a **relative** in-app path, so a
stored link cannot point off-origin.

---

## 7. Health

| Method | Path | Notes |
|---|---|---|
| GET | `/actuator/health` | per service. Details are shown only when authorized, except in `standalone` |
| GET | `/api/v1/system/health` | present on all eleven services. **On the seven scaffolded ones this is the only endpoint there is** |

Scaffolded services, which answer nothing else: ai (9004), review (9006), documentation (9007),
chat (9008), deployment (9009), analytics (9010).

---

## 8. Not yet designed

No contract exists for AI, review, documentation, chat, deployment or analytics, nor for git over
SSH or integrating a third-party git provider. When one is
written it must state its authorization model and its failure behaviour before any endpoint is
implemented — those are the two things that are expensive to retrofit.
