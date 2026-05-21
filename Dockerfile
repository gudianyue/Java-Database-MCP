FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home --uid 10001 appuser
COPY --from=build /workspace/target/postgres-mcp-java-0.1.0-SNAPSHOT.jar /app/postgres-mcp-java.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/postgres-mcp-java.jar"]
