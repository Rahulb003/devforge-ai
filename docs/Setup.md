# Setup

## Requirements

- Node.js 20+ and npm
- Java 21
- Maven 4+
- Docker Engine
- Docker Compose
- kubectl
- PostgreSQL client (optional)

## Local development

1. Install dependencies.

```bash
cd "e:\Devforge AI\frontend"
npm install

cd "e:\Devforge AI\backend"
mvn -q clean install -DskipTests
```

2. Start the development stack.

```bash
cd "e:\Devforge AI"
docker compose up --build
```

3. Open the frontend shell.

- Frontend: http://localhost:4173
- API gateway: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html

## Notes

- This phase only includes infrastructure and shell implementation.
- No backend business APIs are wired yet.
