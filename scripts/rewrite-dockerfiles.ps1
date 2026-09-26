$root = 'e:\Devforge AI'

$files = @{
    'docker-compose.yml' = @'
version: '3.9'

services:
  frontend:
    build:
      context: ./frontend
    ports:
      - 80:80
    depends_on:
      - api-gateway

  api-gateway:
    build:
      context: .
      dockerfile: api-gateway/Dockerfile
    ports:
      - 8080:8080
    depends_on:
      - discovery-server
      - config-server

  config-server:
    build:
      context: .
      dockerfile: config-server/Dockerfile
    ports:
      - 8888:8888

  discovery-server:
    build:
      context: .
      dockerfile: discovery-server/Dockerfile
    ports:
      - 8761:8761

  auth-service:
    build:
      context: .
      dockerfile: services/auth-service/Dockerfile
    ports:
      - 9001:9001
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  project-service:
    build:
      context: .
      dockerfile: services/project-service/Dockerfile
    ports:
      - 9002:9002
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  task-service:
    build:
      context: .
      dockerfile: services/task-service/Dockerfile
    ports:
      - 9003:9003
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  ai-service:
    build:
      context: .
      dockerfile: services/ai-service/Dockerfile
    ports:
      - 9004:9004
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  git-service:
    build:
      context: .
      dockerfile: services/git-service/Dockerfile
    ports:
      - 9005:9005
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  analytics-service:
    build:
      context: .
      dockerfile: services/analytics-service/Dockerfile
    ports:
      - 9006:9006
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  chat-service:
    build:
      context: .
      dockerfile: services/chat-service/Dockerfile
    ports:
      - 9007:9007
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  deployment-service:
    build:
      context: .
      dockerfile: services/deployment-service/Dockerfile
    ports:
      - 9008:9008
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  documentation-service:
    build:
      context: .
      dockerfile: services/documentation-service/Dockerfile
    ports:
      - 9009:9009
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  notification-service:
    build:
      context: .
      dockerfile: services/notification-service/Dockerfile
    ports:
      - 9010:9010
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  review-service:
    build:
      context: .
      dockerfile: services/review-service/Dockerfile
    ports:
      - 9011:9011
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_CLOUD_CONFIG_URI: http://config-server:8888
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://discovery-server:8761/eureka/
    depends_on:
      - config-server
      - discovery-server
      - postgres
      - redis
      - rabbitmq

  postgres:
    image: postgres:15
    environment:
      POSTGRES_USER: devforge
      POSTGRES_PASSWORD: devforge
      POSTGRES_DB: devforge
    ports:
      - 5432:5432
    volumes:
      - postgres-data:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - 6379:6379

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - 5672:5672
      - 15672:15672

  prometheus:
    image: prom/prometheus:latest
    ports:
      - 9090:9090
    volumes:
      - ./infrastructure/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro

  grafana:
    image: grafana/grafana:latest
    ports:
      - 3000:3000
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin

volumes:
  postgres-data:
'@
}

$services = @{
    'api-gateway' = 8080
    'config-server' = 8888
    'discovery-server' = 8761
    'services/auth-service' = 9001
    'services/project-service' = 9002
    'services/task-service' = 9003
    'services/ai-service' = 9004
    'services/git-service' = 9005
    'services/analytics-service' = 9006
    'services/chat-service' = 9007
    'services/deployment-service' = 9008
    'services/documentation-service' = 9009
    'services/notification-service' = 9010
    'services/review-service' = 9011
}

foreach ($entry in $files.GetEnumerator()) {
    $path = Join-Path $root $entry.Key
    Set-Content -LiteralPath $path -Value $entry.Value
}

foreach ($entry in $services.GetEnumerator()) {
    $rel = $entry.Key
    $port = $entry.Value
    $artifact = if ($rel -match 'services/(.+)') { "$($Matches[1])-0.1.0.jar" } else { "$rel-0.1.0.jar" }
    $dockerfile = @"
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -f backend/pom.xml -pl $rel -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/$rel/target/$artifact ./app.jar
EXPOSE $port
ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]
"@
    $modulePath = Join-Path -Path $root -ChildPath $rel
    $path = Join-Path -Path $modulePath -ChildPath 'Dockerfile'
    Set-Content -LiteralPath $path -Value $dockerfile
}

Write-Output 'rewrite completed.'
