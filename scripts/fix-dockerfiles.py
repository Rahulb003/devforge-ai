from pathlib import Path

root = Path(r"e:\Devforge AI")

def write(path: Path, content: str):
    path.write_text(content)

service_defs = {
    'api-gateway': (8080, 'api-gateway'),
    'config-server': (8888, 'config-server'),
    'discovery-server': (8761, 'discovery-server'),
    'services/auth-service': (9001, 'services/auth-service'),
    'services/project-service': (9002, 'services/project-service'),
    'services/task-service': (9003, 'services/task-service'),
    'services/ai-service': (9004, 'services/ai-service'),
    'services/git-service': (9005, 'services/git-service'),
    'services/analytics-service': (9006, 'services/analytics-service'),
    'services/chat-service': (9007, 'services/chat-service'),
    'services/deployment-service': (9008, 'services/deployment-service'),
    'services/documentation-service': (9009, 'services/documentation-service'),
    'services/notification-service': (9010, 'services/notification-service'),
    'services/review-service': (9011, 'services/review-service'),
}

for rel, (port, module) in service_defs.items():
    path = root / rel / 'Dockerfile'
    artifact = f"{module.split('/')[-1]}-0.1.0.jar"
    content = f"FROM maven:3.9.9-eclipse-temurin-21 AS build\n"
    content += "WORKDIR /workspace\n"
    content += "COPY . .\n"
    content += f"RUN mvn -q -B -pl {module} -am package -DskipTests\n\n"
    content += "FROM eclipse-temurin:21-jre-alpine\n"
    content += "WORKDIR /app\n"
    content += f"COPY --from=build /workspace/{module}/target/{artifact} ./app.jar\n"
    content += f"EXPOSE {port}\n"
    content += 'ENTRYPOINT ["java", "-jar", "app.jar"]\n'
    write(path, content)

# ensure frontend Dockerfile is correct
write(root / 'frontend' / 'Dockerfile',
"FROM node:20-alpine AS build\nWORKDIR /app\nCOPY package*.json ./\nRUN npm ci\nCOPY . .\nRUN npm run build\n\nFROM nginx:alpine\nCOPY --from=build /app/dist /usr/share/nginx/html\nEXPOSE 80\nCMD [\"nginx\", \"-g\", \"daemon off;\"]\n")

# rewrite docker-compose
compose = f"version: '3.9'\n\nservices:\n"
compose += "  frontend:\n    build:\n      context: ./frontend\n    ports:\n      - 4173:4173\n    depends_on:\n      - api-gateway\n\n"
for rel, (port, module) in service_defs.items():
    if rel == 'services/auth-service':
        service_name = 'auth-service'
    elif rel.startswith('services/'):
        service_name = rel.split('/', 1)[1]
    else:
        service_name = rel
    compose += f"  {service_name}:\n"
    if service_name == 'frontend':
        continue
    if service_name in ['api-gateway', 'config-server', 'discovery-server']:
        compose += f"    build:\n      context: .\n      dockerfile: {service_name}/Dockerfile\n"
    else:
        compose += f"    build:\n      context: .\n      dockerfile: services/{service_name}/Dockerfile\n"
    compose += f"    ports:\n      - {port}:{port}\n"
    if service_name not in ['api-gateway', 'config-server', 'discovery-server']:
        compose += "    environment:\n      SPRING_PROFILES_ACTIVE: local\n      SPRING_CLOUD_CONFIG_URI: http://config-server:8888\n      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/\n"
        compose += "    depends_on:\n      - config-server\n      - discovery-server\n      - postgres\n      - redis\n      - rabbitmq\n"
    if service_name == 'api-gateway':
        compose += "    depends_on:\n      - discovery-server\n      - config-server\n"
    compose += "\n"

compose += "  postgres:\n    image: postgres:15\n    environment:\n      POSTGRES_USER: devforge\n      POSTGRES_PASSWORD: devforge\n      POSTGRES_DB: devforge\n    ports:\n      - 5432:5432\n    volumes:\n      - postgres-data:/var/lib/postgresql/data\n\n"
compose += "  redis:\n    image: redis:7\n    ports:\n      - 6379:6379\n\n"
compose += "  rabbitmq:\n    image: rabbitmq:3-management\n    ports:\n      - 5672:5672\n      - 15672:15672\n\n"
compose += "  prometheus:\n    image: prom/prometheus:latest\n    ports:\n      - 9090:9090\n    volumes:\n      - ./infrastructure/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro\n\n"
compose += "  grafana:\n    image: grafana/grafana:latest\n    ports:\n      - 3000:3000\n    environment:\n      GF_SECURITY_ADMIN_PASSWORD: admin\n\n"
compose += "volumes:\n  postgres-data:\n"
write(root / 'docker-compose.yml', compose)

# create ingress yaml
write(root / 'infrastructure' / 'kubernetes' / 'ingress.yaml',
"apiVersion: networking.k8s.io/v1\nkind: Ingress\nmetadata:\n  name: devforge-ai-ingress\n  namespace: devforge-ai\n  annotations:\n    kubernetes.io/ingress.class: nginx\nspec:\n  rules:\n    - host: devforge.local\n      http:\n        paths:\n          - path: /api\n            pathType: Prefix\n            backend:\n              service:\n                name: api-gateway\n                port:\n                  number: 8080\n          - path: /\n            pathType: Prefix\n            backend:\n              service:\n                name: frontend\n                port:\n                  number: 80\n")

print('Dockerfiles and docker-compose.yml rewritten successfully.')
