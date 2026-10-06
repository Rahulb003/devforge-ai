# DevForge AI

**DevForge AI** is an enterprise-grade AI developer workspace platform designed for next-generation engineering teams. It provides a production-ready foundation for a modular microservices architecture, unified developer tooling, and observability.

## What is included

This is a foundation with eight service domains built on it, not a finished platform.
`docs/PROGRESS.md` tracks exactly what works, and what is only scaffolded, per feature.

**Working end-to-end, with tests:**

- Authentication — signup, sign-in, JWT access/refresh with rotation and reuse detection,
  TOTP two-factor, per-device sessions, password reset, rate limiting
- Organizations and projects, with membership-derived authorization and tenant isolation
- Tasks, a Kanban board, sprints, comments and labels
- An API gateway as the single entry point, with correlation ids
- A transactional outbox over Kafka, verified against a real broker
- Notifications driven by those events: task assignments and security alerts,
  with an unread badge and a feed
- Self-hosted git repositories: create, browse, commit, branch and diff, backed by
  real git objects (JGit). Not a GitHub integration — that needs provider
  credentials and does not exist yet
- Automated code review over that content: committed credentials, credential files,
  merge-conflict markers and a few high-signal dangerous patterns, with a
  configurable quality gate. Not an AI reviewer and not a general SAST engine
- Generated documentation from that content: a repository overview, the HTTP API
  surface found in the source, and documentation coverage. Pattern-based, not
  AI-written, and each document states its own limits
- Project analytics built by consuming those events: tasks created, completed and
  assigned, and commits, as a daily series
- React 19 + Vite frontend covering the above

**Scaffolded only — health endpoint, no behaviour yet:** AI, chat and deployment
services.

**Present but unverified:** Docker Compose and the Kubernetes manifests have never been
run here (no Docker daemon available). Treat them as untested.

## A note on credentials in this repository

This repository is public, and that is safe by design rather than by luck:

- Every credential-shaped value in it is either a `REPLACE_ME` placeholder or an
  **explicitly labelled development/test-only** value. There are no real secrets.
- `.env`, `*.pem`, `*.key`, `*.p12`, `*.jks` and the local `data/` directory are
  gitignored. `.env.example` is tracked on purpose and documents weak local defaults.
- The `standalone` profile ships a known JWT signing key
  (`local-development-only-signing-key-change-me-...`). **It is public, deliberately.**
  It exists so `openapp.bat` works with no setup, and it must never be used anywhere
  reachable from a network — anyone reading this repository can forge tokens for an
  instance running with it.
- Real deployments supply every secret through the environment, and the default profile
  has **no fallback values**, so a service started without them refuses to boot rather
  than quietly running on development credentials.

`docs/SECURITY.md` lists the controls that are implemented and, just as importantly, the
gaps that are not.
RabbitMQ was removed: it was declared in five places and called from none. Kafka is the
event backbone — see `docs/EVENT_CATALOG.md` §1 for the reasoning.

## Project structure

```text
devforge-ai/
├── api-gateway/
├── backend/
├── common-library/
├── config-server/
├── discovery-server/
├── docs/
├── frontend/
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   └── monitoring/
├── scripts/
├── services/
│   ├── analytics-service/
│   ├── ai-service/
│   ├── auth-service/
│   ├── chat-service/
│   ├── deployment-service/
│   ├── documentation-service/
│   ├── git-service/
│   ├── notification-service/
│   ├── project-service/
│   ├── review-service/
│   └── task-service/
└── .github/workflows/
```

## Phase 0 scope

This repository currently contains the foundation for the DevForge AI platform. No business logic, authentication, ChatGPT integration, or deployment workflows are implemented yet.

## Getting started

See `docs/Setup.md` for local development commands.
