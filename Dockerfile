# Code Scanner - sunucu modu (REST API + Swagger)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
# Repoları klonlamak için git
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/system-code-analyzer-1.0.0.jar app.jar
COPY scanner.properties scanner.properties
ENV SCANNER_CONFIG=/app/scanner.properties \
    SCANNER_WORK_DIR=/data/work \
    SCANNER_OUTPUT_DIR=/data/reports
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar", "--server"]
