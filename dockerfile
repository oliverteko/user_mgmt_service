# =============================================================================
# Stage 1: Build with caching optimizations
# =============================================================================
FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

# Copy only files needed for dependency resolution first (maximize layer cache)
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Make wrapper executable and resolve dependencies (cache this layer)
RUN chmod +x gradlew && \
    ./gradlew dependencies --no-daemon --max-workers=1

# Copy source and build (tests skipped)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test && \
    # Clean up Gradle cache in same layer to reduce final image
    rm -rf ~/.gradle/caches/

# =============================================================================
# Stage 2: Minimal JRE for Spring Boot
# =============================================================================
FROM eclipse-temurin:25-jdk-alpine AS jre
RUN /opt/java/openjdk/bin/jlink \
    --add-modules java.base,java.se,java.logging,java.sql,java.naming,java.xml,jdk.unsupported,jdk.management,jdk.crypto.ec \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /custom-jre && \
    rm -rf /opt/java/openjdk

# =============================================================================
# Stage 3: Ultra-minimal runtime (~50MB base + app)
# =============================================================================
FROM alpine:3.20 AS runtime

WORKDIR /app

# Install custom JRE
COPY --from=jre /custom-jre /usr/lib/jvm/custom-jre

# Create non-root user in single layer with runtime deps
RUN addgroup -S appgroup && \
    adduser -S -G appgroup -H -D appuser && \
    # Install ca-certificates for HTTPS (critical for Spring Boot)
    apk add --no-cache ca-certificates tzdata && \
    rm -rf /var/cache/apk/*

# Copy built JAR (specific filename pattern)
COPY --chown=appuser:appgroup --from=build /app/build/libs/*.jar app.jar

ENV JAVA_HOME=/usr/lib/jvm/custom-jre \
    PATH=$JAVA_HOME/bin:$PATH \
    TZ=UTC

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
