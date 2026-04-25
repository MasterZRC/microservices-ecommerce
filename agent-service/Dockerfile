# Multi-stage build: 基础镜像统一用 python:3.11-slim 与 recommendation-rank-service 风格一致
FROM python:3.11-slim AS base

WORKDIR /app

# 安装系统依赖 (curl 用于 healthcheck)
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
    && rm -rf /var/lib/apt/lists/*

# 先复制 requirements 单独安装，便于 docker 缓存
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用源码
COPY app ./app

EXPOSE 8011

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8011", "--workers", "1"]
