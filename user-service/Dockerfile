FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY user-service/target/user-service-1.0.0.jar app.jar

EXPOSE 8001

ENTRYPOINT ["java", "-jar", "app.jar"]
