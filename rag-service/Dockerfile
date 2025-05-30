# =========================
# Build Stage
# =========================

# Use the official Gradle image with JDK 17 for building
FROM gradle:8.4.0-jdk17 AS builder

WORKDIR /app

# Copy the full repo context to the build container
COPY ../../ .

# Build the specific service using Gradle
RUN gradle :rag-service:build --no-daemon


# =========================
# Runtime Stage
# =========================

# Use a lightweight Amazon Corretto JRE for runtime
FROM amazoncorretto:17-alpine

WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=builder /app/rag-service/build/libs/*.jar app.jar

# Expose the port the service listens on
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

LABEL authors="moritzslz"