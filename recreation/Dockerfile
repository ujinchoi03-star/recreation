# ---- Stage 1: Build with Gradle ----
FROM gradle:8.14-jdk17 AS builder
WORKDIR /app

COPY gradle/ gradle/
COPY gradlew ./
COPY gradlew.bat ./
COPY build.gradle.kts ./
COPY settings.gradle.kts ./

# Download dependencies (cached unless build files change)
RUN gradle dependencies --no-daemon || true

COPY src/ src/
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Production runtime ----
FROM eclipse-temurin:17-jre-alpine AS runner
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
