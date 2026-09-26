# Auth Service

This service provides the auth-service foundation for the DevForge AI microservice platform.

## Purpose

- Spring Boot 3 service shell
- Actuator health endpoint
- PostgreSQL / Redis / RabbitMQ support configured
- Shared library dependency for cross-service components

## Running locally

```bash
cd services/auth-service
./mvnw spring-boot:run
```

## Docker

```bash
docker build -t devforge-ai/auth-service .
```
