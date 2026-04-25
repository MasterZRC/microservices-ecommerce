# Multi-stage build with SkyWalking Agent
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 下载并解压 SkyWalking Agent
RUN curl -fL https://archive.apache.org/dist/skywalking/java-agent/9.3.0/apache-skywalking-java-agent-9.3.0.tgz | tar -xz && \
    mkdir -p /opt/skywalking && \
    mv skywalking-agent /opt/skywalking/agent

# 复制项目文件
COPY pom.xml ./
COPY admin-service/pom.xml ./admin-service/
COPY admin-service/src ./admin-service/src

WORKDIR /app/admin-service
RUN mvn clean package -DskipTests -B && mv target/*.jar app.jar

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 复制构建的 JAR
COPY --from=builder /app/admin-service/app.jar app.jar

# 复制 SQL 初始化脚本
COPY --from=builder /app/admin-service/src/main/resources/sql/init.sql /app/sql/init.sql

# 复制 SkyWalking Agent
COPY --from=builder /opt/skywalking/agent /opt/skywalking/agent

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

EXPOSE 8006

# 环境变量配置 SkyWalking
ENV JAVA_OPTS="-javaagent:/opt/skywalking/agent/skywalking-agent.jar \
    -Dskywalking.agent.service_name=admin-service \
    -Dskywalking.collector.backend_service=skywalking-oap:11800 \
    -Dskywalking.agent.sample_n_per_3_secs=10 \
    -Dskywalking.logging.level=INFO \
    -Xms256m -Xmx512m -XX:+UseG1GC"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8006/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
