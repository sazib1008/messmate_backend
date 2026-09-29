# ==============================================================================
# MessMate Backend - Multi-Stage Dockerfile for Render & Cloud Environments
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build stage
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Copy gradle wrapper and dependency configuration files
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Ensure gradle wrapper has execution permissions and cache dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

# Copy source code
COPY src/ src/

# Build application bootJar without running unit tests (tests run in CI)
RUN ./gradlew bootJar --no-daemon -x test

# ------------------------------------------------------------------------------
# Stage 2: Minimal Production Runtime
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create unprivileged system user for security
RUN groupadd -r messmate && useradd -r -g messmate -s /bin/false messmate

# Copy the built jar from the builder stage
COPY --from=builder --chown=messmate:messmate /app/build/libs/app.jar app.jar

# Switch to unprivileged user
USER messmate

# Render dynamically sets PORT at runtime (defaulting to 8080)
ENV PORT=8080
EXPOSE 8080

# Production JVM optimizations for containerized environments
# Limits heap to 75% of container RAM to prevent OOM termination on Render (512MB free tier)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=40.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Start the Spring Boot application binding to the assigned PORT
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
