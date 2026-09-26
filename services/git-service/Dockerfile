FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -f backend/pom.xml -pl services/git-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/git-service/target/git-service-0.1.0.jar ./app.jar
EXPOSE 9005
ENTRYPOINT ["java", "-jar", "app.jar"]
