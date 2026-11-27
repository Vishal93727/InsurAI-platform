# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests run in CI (`mvn verify`); the image build only packages.
RUN mvn -q -B -DskipTests package \
    && java -Djarmode=tools -jar target/insurai-platform-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
# Pick up Alpine security fixes released after the base image was built.
RUN apk upgrade --no-cache \
    && addgroup -S platform && adduser -S -G platform -u 10001 platform
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# The portal stores claim documents uploaded without object storage here; mount a volume to keep them.
RUN mkdir -p /app/uploads && chown 10001:10001 /app/uploads
USER 10001
# Any profile other than `local` means no development secrets: every required secret must come from the
# environment or the application refuses to start (see .env.example).
ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=12 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health/readiness" | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
