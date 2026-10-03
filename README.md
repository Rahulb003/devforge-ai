# DevForge AI

**DevForge AI** is an enterprise-grade AI developer workspace platform designed for next-generation engineering teams. It provides a production-ready foundation for a modular microservices architecture, unified developer tooling, and observability.

## What is included

This is a foundation with five service domains built on it, not a finished platform.
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
- React 19 + Vite frontend covering the above

**Scaffolded only — health endpoint, no behaviour yet:** AI, review, documentation,
chat, deployment and analytics services.

**Present but unverified:** Docker Compose and the Kubernetes manifests have never been
run here (no Docker daemon available). Treat them as untested.

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
