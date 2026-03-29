# ─── Stage 1: Build ──────────────────────────────────────────────────────────
# Full Maven + JDK image — only used to compile and package the fat JAR.
# Nothing from this stage ships in the final image.
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy the POM first and resolve dependencies in a separate layer.
# Docker will cache this layer as long as pom.xml doesn't change,
# avoiding a full re-download on every source-only change.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR (skip tests; tests run in CI, not inside Docker).
COPY src ./src
RUN mvn package -DskipTests -q


# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
# Minimal JRE image — no compiler, no Maven, no build artifacts.
FROM eclipse-temurin:17-jre-jammy AS runtime

# Run as a non-root user for security hardening.
RUN groupadd --system bitnet && useradd --system --gid bitnet bitnet
USER bitnet

WORKDIR /app

# Copy only the fat JAR produced in the build stage.
COPY --from=builder /build/target/bitnet-api-*.jar app.jar

# Port 8081: cluster-facing Spring Boot API.
# Port 8080 (BitNet sidecar) is NOT exposed here — it is localhost-only inside the Pod.
EXPOSE 8081

# Kubernetes liveness probe will hit GET /actuator/health.
# Set JVM flags suitable for a containerised environment:
#   -XX:+UseContainerSupport  — respect cgroup CPU/memory limits (on by default in JDK 11+, explicit for clarity)
#   -XX:MaxRAMPercentage       — use up to 75% of the container memory limit for the heap
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]
