# Build stage - use cached maven image
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy parent pom first (needed for multi-module resolution)
COPY pom.xml ./

# Copy admin-service with its own pom
COPY admin-service/pom.xml ./admin-service/
COPY admin-service/src ./admin-service/src

WORKDIR /app/admin-service
RUN mvn clean package -DskipTests -B && mv target/*.jar app.jar

# Run stage - use cached JRE image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/admin-service/app.jar app.jar
COPY --from=builder /app/admin-service/src/main/resources/sql/init.sql /app/sql/init.sql
RUN apk add --no-cache curl
EXPOSE 8006
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8006/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
