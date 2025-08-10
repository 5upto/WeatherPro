# syntax=docker/dockerfile:1

# ===== Builder stage =====
FROM eclipse-temurin:18-jdk AS builder
WORKDIR /workspace

# Install dependencies first to leverage layer caching
# Copy Gradle wrapper and build files (paths relative to Dockerfile directory)
COPY gradlew /workspace/gradlew
COPY gradlew.bat /workspace/gradlew.bat
COPY gradle /workspace/gradle
COPY settings.gradle /workspace/settings.gradle
COPY app/build.gradle /workspace/app/build.gradle

# Prepare an empty sources tree to run dependency resolution
RUN mkdir -p /workspace/app/src/main/java && \
    mkdir -p /workspace/app/src/test/java

# Resolve and cache Gradle dependencies
RUN chmod +x ./gradlew && ./gradlew -q :app:dependencies --no-daemon || true

# Now copy the full source
COPY app /workspace/app

# Build a self-contained distribution
RUN ./gradlew :app:installDist --no-daemon

# ===== Runtime stage =====
FROM eclipse-temurin:18-jre AS runtime
WORKDIR /app

# Copy the application distribution from the builder
COPY --from=builder /workspace/app/build/install/app /app

# Ensure startup script is executable
RUN chmod +x /app/bin/app

# Expose the HTTP port
EXPOSE 8080

# Environment (optional): increase blocked thread check interval in Vert.x
ENV JAVA_TOOL_OPTIONS="-Dvertx.options.blockedThreadCheckInterval=60000"

# Default command uses the generated Unix script
CMD ["/app/bin/app"]
