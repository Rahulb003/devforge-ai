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
| 17 | Build must be green | `IMPLEMENTED` | 509 backend + 79 frontend + 49 e2e, all passing |
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
| 25 | RBAC | `IMPLEMENTED` | Project roles enforced on every write in every service (VIEWER read-only; repository deletion ADMIN/TEAM_LEAD), from project-service's answer; tested per service. Previously only project-service enforced them |
| 26 | Audit trail | `PARTIALLY_IMPLEMENTED` | Every published change - projects, membership, tasks, repositories, pull requests - recorded append-only from the events, shown to project admins; verified end to end in the compose CI job. Auth events in auth-service. Tamper-evident: a SHA-256 hash chain per project, re-hashed on demand from the UI, detects edits, deletions and truncation (tested, and verified on real PostgreSQL in CI). Not a signature: a wholesale rewrite is only caught against an externally recorded head hash; no external sink |
| 27 | Password reset must not reveal account existence | `IMPLEMENTED` | identical response either way |
| 32 | Prove user A cannot reach user B's data | `IMPLEMENTED` | 23 tenant-isolation + 3 cross-user tests, 404-not-403 |

## Platform

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 28 | Multi-tenancy | `IMPLEMENTED` | membership-derived authorization throughout; organizations grow by email invitation (verified address only, no account enumeration) with member and role management, and projects add members from the organization |
| 29 | Projects | `IMPLEMENTED` | CRUD, archive, members |
| 30 | Tasks, board, sprints | `IMPLEMENTED` | 29 tests |
| 31 | API gateway as single entry point | `IMPLEMENTED` | verified live for all four services |
| 33 | Event backbone (Kafka) | `IMPLEMENTED` | outbox + consumer, 9 tests against a real broker |
| 34 | Correlation across services | `IMPLEMENTED` | generated at the gateway, validated, carried into events |
| 35 | Observability | `PARTIALLY_IMPLEMENTED` | Prometheus metrics from every service with a Grafana dashboard, structured logs with correlation ids across services and events. Alert rules for availability, 5xx rate, heap, stuck or growing outboxes and dead-lettered events, over outbox and dead-letter meters added for them. Alertmanager emails each alert, through Mailpit in compose and on kind; CI stops a service and waits for its ServiceDown email. Distributed tracing through OpenTelemetry to Jaeger in compose: CI checks every service reports traces and a request crossing services is one trace. Kubernetes creates spans (trace ids in logs) but has no collector deployed |
| 36 | Error handling and problem responses | `IMPLEMENTED` | `ApiError` with a traceId, never an exception message |
| 37 | Code-execution sandbox | `MISSING` | **nothing executes developer code today.** Designed in `docs/SANDBOX.md` with twelve required guarantees and an escape-attempt suite; not built, because no container runtime or hypervisor is available here. Hard prerequisite for §6 and the IDE phases |
| 38 | Kafka security (TLS/SASL/ACL) | `IMPLEMENTED` | SASL over TLS for every client, fail-closed when required; the client listeners speak only SASL_SSL, with a CA each stack generates for itself and hostname verification on. One identity per service and deny-by-default ACLs. Verified against a real TLS broker in tests (untrusted CA and plaintext both refused) and in CI on compose and kind. SCRAM and a real PKI **UNVERIFIED** |

## Product surface

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 6, 15, 16 | AI: generation, review, explanation, RAG, agents | `PARTIALLY_IMPLEMENTED` | **Explanation and pull request review are built**: "Explain with AI" on any text file, and "Review with AI" on a pull request (advice only - it approves and posts nothing), sends it to Claude (Messages API) as delimited untrusted data, with no tools, a per-user hourly limit and plain-text output. Off without `ANTHROPIC_API_KEY`, and says so; tested against a stand-in for the API at the network boundary - **the live call is UNVERIFIED** (no key here). "Ask about this repository" answers from excerpts of the best-matching files, found by lexical search (not embeddings), naming the files it used. "Suggest change" proposes a new version of a file from a request, opened in the editor for the developer to review and commit - nothing is written, run or committed by the AI. Agents that execute code are not built: they need the sandbox |
| 7 (svc) | Git: repos, branches, commits, diffs, PRs | `PARTIALLY_IMPLEMENTED` | Real self-hosted repositories via JGit — create, browse, branch, multi-file commit, diff, and pull requests (open, conflict detection, three-way merge with a merge commit, close, comments, approvals pinned to the approved commit, an admin-set required-approvals rule), all through the UI; line-level comments on a diff view. Clone and push with a real git client over HTTP, authenticated by personal access tokens that work for git only, with the default branch protected and the merge rule enforced on pushes - verified with JGit in tests and with the git CLI on kind. **No** git over SSH, no GitHub/GitLab integration |
| 8 | Code review and quality gates | `IMPLEMENTED` | Secret detection, credential files, conflict markers, dangerous patterns; severities, configurable gate, dismissal with a recorded reason. 83 tests, verified live. **Not** an AI reviewer and not a general SAST engine — a focused, high-signal rule set |
| 9 | IDE / editor | `PARTIALLY_IMPLEMENTED` | Code browser plus a CodeMirror editor: edit and delete across files, commit them as one, refused with 409 if the branch moved since editing began. No execution, terminal or debugger — §37 gates those |
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
| 45 | Accessibility | `PARTIALLY_IMPLEMENTED` | axe (WCAG 2.1 A/AA) runs in the browser suite on every main page in both themes and fails on serious or critical violations: none remain. Automated checks find about a third of real issues; no screen-reader or keyboard-only audit by a person yet |

## Operations

| § | Requirement | Status | Evidence |
|---|---|---|---|
| 46 | Docker Compose | `IMPLEMENTED` | Runs in CI: per-service databases, real Kafka and Redis, browser suite against nginx, event pipeline checked end to end |
| 47 | Kubernetes manifests | `IMPLEMENTED` | Generated by `infrastructure/kubernetes/generate.cjs` from one service table: a database and Kafka identity per service, ACL setup Job, probes, resource limits, non-root security contexts, Prometheus and Grafana. CI deploys them to kind and smoke-tests signup through the gateway to the audit log, its hash chain and Prometheus. Single-node kind only: ingress, NetworkPolicy enforcement, autoscaling and failover are **UNVERIFIED** |
| 48 | CI pipeline | `IMPLEMENTED` | GitHub Actions on every push: lint, typecheck, unit and backend tests, the browser suite, every image built, started and scanned, and the whole stack under docker compose with the event pipeline and Kafka ACLs checked |
| 49 | Config server / service discovery | `IMPLEMENTED` (by the platform) | The empty Boot apps were deleted. Services resolve each other by DNS name under compose and Kubernetes, and take configuration from environment, ConfigMap and Secret; no Eureka or Spring Cloud Config |
| 50 | Monitoring stack | `IMPLEMENTED` | Every service exports Prometheus metrics behind a scrape credential (fails closed); Prometheus scrapes all ten and Grafana is provisioned with the data source and a services dashboard - all checked in the compose CI job, which also requires all six alert rules to load healthy and the outbox gauge from all six publishing services. Alertmanager delivery checked end to end in compose and wired on kind. Alerts go to Mailpit until a deployment configures a real receiver |

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
