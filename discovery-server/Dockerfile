FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -f backend/pom.xml -pl discovery-server -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/discovery-server/target/discovery-server-0.1.0.jar ./app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
