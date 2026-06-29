# =============================================================================
# Stage 1: Build
# Verwendet das offizielle Eclipse Temurin JDK 25 Image (noble = Ubuntu 24.04)
# für den Build-Prozess mit dem Gradle Wrapper.
# =============================================================================
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /app

# Gradle Wrapper und Build-Konfiguration zuerst kopieren (Layer-Caching für Dependencies)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Wrapper ausführbar machen und Dependencies vorab herunterladen (Cache-Layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Quellcode kopieren und Projekt bauen (Tests werden übersprungen)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# =============================================================================
# Stage 2: Runtime
# Schlankes JRE-only Alpine Image – kein JDK, kein Build-Tool, kein Source-Code.
# eclipse-temurin:25-jre-alpine liegt deutlich unter 250 MB.
# =============================================================================
FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

# Non-Root User erstellen – Alpine nutzt addgroup/adduser statt groupadd/useradd
RUN addgroup -S appgroup && \
    adduser -S -G appgroup -H appuser

# Nur das fertige JAR aus der Build-Stage übernehmen und Eigentümer direkt setzen
COPY --chown=appuser:appgroup --from=build /app/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

# JSON-Notation (Hadolint DL3025 – kein Shell-Form für ENTRYPOINT)
ENTRYPOINT ["java", "-jar", "app.jar"]