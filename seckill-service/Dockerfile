FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY seckill-service/target/seckill-service-1.0.0.jar app.jar

EXPOSE 8005

ENTRYPOINT ["java", "-jar", "app.jar"]
