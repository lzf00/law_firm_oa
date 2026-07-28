FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache \
    && addgroup -S app \
    && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/law-firm-oa-*.jar app.jar
COPY --from=build /workspace/target/classes/META-INF/sbom/application.cdx.json /opt/sbom/backend.cdx.json
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
