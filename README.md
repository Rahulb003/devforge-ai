# DevForge AI

**DevForge AI** is an enterprise-grade AI developer workspace platform designed for next-generation engineering teams. It provides a production-ready foundation for a modular microservices architecture, unified developer tooling, and observability.

## What is included

- React 19 + Vite frontend shell
- Java 21 Spring Boot microservice foundation
- API Gateway, config server, discovery server
- PostgreSQL, Redis, RabbitMQ, Prometheus, Grafana
- Docker Compose orchestration
- Kubernetes manifests for namespace, deployments, services, config maps, secrets, and ingress
- OpenAPI / Swagger setup
- Monitoring, health endpoints, logging, and request tracing foundation
- CI pipeline with lint, test, build, and Docker image packaging

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
