# Requirements matrix

**Last updated:** 2026-10-03

Every numbered section of the specification, with its honest status and the evidence for it. This is
the compliance view; `docs/PROGRESS.md` is the working view with more detail per feature.

Status vocabulary: `IMPLEMENTED`, `PARTIALLY_IMPLEMENTED`, `SCAFFOLDED`, `BROKEN`, `MISSING`.
**Nothing is marked implemented unless a command was run and its result observed.**

---

## Summary

| Status | Sections |
|---|---|
| `IMPLEMENTED` | 32 |
| `PARTIALLY_IMPLEMENTED` | 11 |
| `SCAFFOLDED` | 3 |
| `MISSING` | 92 |

**~25% complete, weighted by effort rather than section count.** The weighting matters: §6, §15 and
§16 (the AI platform) are a larger body of work than everything built so far combined, so a flat
section count would overstate progress.

---

## Foundation and process

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | Vision, scope, tagline | `IMPLEMENTED` | `README.md` |
| 2 | Inspect before changing; classify every feature | `IMPLEMENTED` | this file and `docs/PROGRESS.md` exist and are maintained |
| 3 | Never fake functionality | `IMPLEMENTED` | no hard-coded metrics; the dashboard reads real queries; the dev mailbox is the only stand-in and is named as one |
| 4 | Never claim unverified success | `IMPLEMENTED` | `UNVERIFIED` used literally throughout |
| 5 | Phased execution | `PARTIALLY_IMPLEMENTED` | phases 0–4 and §12 done; 5–20 outstanding |
| 7 | Documentation set (11 files) | `IMPLEMENTED` | all 11 present as of this commit |
| 15 | Container images | `IMPLEMENTED` | All images build in CI, run non-root, and each service container starts healthy; frontend container serves its security headers. Multi-container runtime with real infrastructure **UNVERIFIED** |
| 16 | Secrets and configuration hygiene | `IMPLEMENTED` | no fallbacks for credentials in the default profile; secrets gitignored |
| 17 | Build must be green | `IMPLEMENTED` | 465 backend + 74 frontend + 38 e2e, all passing |
| 18 | RabbitMQ removed unless justified | `IMPLEMENTED` | removed from five places; justification in `EVENT_CATALOG.md` §1 |

## Identity and access

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 19 | Signup, sign-in, sessions | `IMPLEMENTED` | 92 auth tests |
| 20 | JWT access/refresh with rotation | `IMPLEMENTED` | `typ` claim, reuse detection, family revocation |
| 21 | Two-factor authentication | `IMPLEMENTED` | TOTP verified against RFC 6238 vectors; recovery codes |
| 22 | OAuth / social sign-in | `PARTIALLY_IMPLEMENTED` | implemented and conditional; **UNVERIFIED** without provider credentials |
| 23 | Per-device session management | `IMPLEMENTED` | list, revoke one, revoke others |
| 24 | Rate limiting on authentication | `IMPLEMENTED` | per-account, time-windowed |
| 25 | RBAC | `PARTIALLY_IMPLEMENTED` | enforced in project-service and task-service; the scaffolded services have nothing to enforce |
| 26 | Audit trail | `PARTIALLY_IMPLEMENTED` | auth events only; no audit for project, task or notification changes |
| 27 | Password reset must not reveal account existence | `IMPLEMENTED` | identical response either way |
| 32 | Prove user A cannot reach user B's data | `IMPLEMENTED` | 23 tenant-isolation + 3 cross-user tests, 404-not-403 |

## Platform

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 28 | Multi-tenancy | `IMPLEMENTED` | membership-derived authorization throughout |
| 29 | Projects | `IMPLEMENTED` | CRUD, archive, members |
| 30 | Tasks, board, sprints | `IMPLEMENTED` | 29 tests |
| 31 | API gateway as single entry point | `IMPLEMENTED` | verified live for all four services |
| 33 | Event backbone (Kafka) | `IMPLEMENTED` | outbox + consumer, 9 tests against a real broker |
| 34 | Correlation across services | `IMPLEMENTED` | generated at the gateway, validated, carried into events |
| 35 | Observability | `PARTIALLY_IMPLEMENTED` | health and metrics endpoints, structured logs; no tracing backend, no dashboards verified |
| 36 | Error handling and problem responses | `IMPLEMENTED` | `ApiError` with a traceId, never an exception message |
| 37 | Code-execution sandbox | `MISSING` | **nothing executes developer code today.** Designed in `docs/SANDBOX.md` with twelve required guarantees and an escape-attempt suite; not built, because no container runtime or hypervisor is available here. Hard prerequisite for §6 and the IDE phases |
| 38 | Kafka security (TLS/SASL/ACL) | `PARTIALLY_IMPLEMENTED` | SASL client authentication for every service, SCRAM over TLS by default, fail-closed when required; tested against a real SASL broker. No ACLs or per-service identities. Production TLS UNVERIFIED |

## Product surface

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 6, 15, 16 | AI: generation, review, explanation, RAG, agents | `SCAFFOLDED` | ai-service is a health endpoint. **Largest remaining item** |
| 7 (svc) | Git: repos, branches, commits, diffs, PRs | `PARTIALLY_IMPLEMENTED` | Real self-hosted repositories via JGit — create, browse, commit, branch, diff; 107 tests, verified live. **No** pull requests, no push over HTTP/SSH, and no GitHub/GitLab integration |
| 8 | Code review and quality gates | `IMPLEMENTED` | Secret detection, credential files, conflict markers, dangerous patterns; severities, configurable gate, dismissal with a recorded reason. 83 tests, verified live. **Not** an AI reviewer and not a general SAST engine — a focused, high-signal rule set |
| 9 | IDE / editor | `PARTIALLY_IMPLEMENTED` | A read-only code browser plus a single-file commit form exists over git-service. No editor, no execution — §37 gates both |
| 10 | Documentation generation | `IMPLEMENTED` | Repository overview, API surface and documentation coverage, generated from real file content. 31 tests. Pattern-based, not AI-written, and each document states its own limits |
| 11 | Chat and collaboration | `IMPLEMENTED` | Per-project channel over REST with polling, author-only edit/delete, 8 tests. Live delivery over Server-Sent Events. Single-instance fan-out, no presence |
| 12 | Notifications | `IMPLEMENTED` | consumer + API + bell + feed; 22 backend, 12 frontend tests |
| 13 | Deployments and pipelines | `SCAFFOLDED` | deployment-service is a health endpoint |
| 14 | Analytics | `IMPLEMENTED` | Daily per-project counters built by consuming task and repository events, with a read API. Counts only what was published after it began consuming, which the response states |

## Frontend

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 39 | App shell, routing, theme | `IMPLEMENTED` | 26 unit tests |
| 40 | Auth screens | `IMPLEMENTED` | verified end-to-end in a browser |
| 41 | Organization and project screens | `IMPLEMENTED` | loading, empty and error states |
| 42 | Kanban board | `IMPLEMENTED` | reachable by clicking from a project |
| 43 | Notification surface | `IMPLEMENTED` | bell with unread badge, feed page |
| 44 | Loading/empty/error states everywhere | `IMPLEMENTED` for shipped screens | asserted per screen in tests |
| 45 | Accessibility | `PARTIALLY_IMPLEMENTED` | role-based queries, accessible names on icon-only controls, status not conveyed by colour alone. No axe run, no keyboard-navigation suite |

## Operations

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 46 | Docker Compose | `SCAFFOLDED` | present; six wrong port mappings fixed; **UNVERIFIED** |
| 47 | Kubernetes manifests | `SCAFFOLDED` | `infrastructure/kubernetes/`; **UNVERIFIED** |
| 48 | CI pipeline | `PARTIALLY_IMPLEMENTED` | `ci.yml` runs lint, test and build; **never executed here** |
| 49 | Config server / service discovery | `SCAFFOLDED` — **misleading** | both are empty Boot apps with no Spring Cloud dependency. Compose sets env vars nothing reads |
| 50 | Monitoring stack | `SCAFFOLDED` | Prometheus config and Grafana in compose; **UNVERIFIED** |

---

## The remaining ~93 sections

Everything not listed above is `MISSING`, and falls into four groups:

1. **The AI platform** — generation, explanation, refactoring, test generation, review, RAG over a
   codebase, agent orchestration, prompt-injection defence, model routing and cost control.
2. **The five scaffolded service domains** — documentation, chat, deployment, analytics, and the
   IDE surface that fronts several of them. Plus, for git: pull requests, push over
   HTTP/SSH, and third-party provider integration.
3. **Operational maturity** — tracing, dashboards, alerting, backups, disaster recovery,
   performance budgets, load testing, dependency and container scanning.
4. **Enterprise concerns** — SSO/SAML, SCIM provisioning, data residency, retention and deletion
   policy, compliance evidence.

Group 1 is the largest, and group 2's AI-adjacent members depend on §37 (the sandbox) existing first.
