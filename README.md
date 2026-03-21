# microservices-ecommerce

## 项目结构

```
microservices-ecommerce/
├── user-service/           # 用户服务 (端口 8001)
├── product-service/        # 商品服务 (端口 8002)
├── order-service/          # 订单服务 (端口 8003)
├── recommendation-service/ # 推荐服务 (端口 8004)
├── seckill-service/        # 秒杀服务 (端口 8005)
├── api-gateway/            # API网关 (端口 8080)
└── frontend/               # Vue3前端
```

## 技术栈

**后端:**
- Spring Boot 3.x
- Spring Cloud Alibaba (Nacos, Sentinel)
- MyBatis-Plus
- Redis + Redisson
- MySQL 8.0
- JWT认证

**前端:**
- Vue 3 + Composition API
- Element Plus
- Vite
- Pinia (状态管理)
- Vue Router

## 快速启动

### 1. 环境要求
- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0+
- Redis 7.0+
- Nacos 2.x

### 2. 编译项目
```bash
mvn clean install -DskipTests
```

### 3. 启动服务
1. 复制环境变量模板：`cp .env.example .env`（Windows 可直接复制文件）
2. 使用 Docker Compose 一键启动（推荐）：`docker compose -f docker-compose.yml up -d --build`
3. 如需仅启动基础设施：`docker compose -f docker-compose.infra.yml up -d`
4. 首次启动会自动执行 `infrastructure/mysql/init` 下 SQL 初始化脚本

> 说明：`recommendation-rank-service` 已在 `docker-compose.yml` 中使用 `./recommendation-rank-service` 作为构建上下文，支持直接一键构建启动，无需手工单独 `docker build`。

### 3.1 启动监控栈（Prometheus + Grafana）

```bash
docker compose -f docker-compose.yml up -d prometheus grafana
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001`
- Grafana 默认账号：`admin`
- Grafana 默认密码：`admin123`
- 预置看板：`Microservices Overview`、`Seckill Overview`（位于 `Microservices E-Commerce` 文件夹）

### 4. 启动前端
```bash
cd frontend
npm install
npm run dev
```

## API文档

### 用户服务
- POST /user/register - 注册
- POST /user/login - 登录
- GET /user/{id} - 获取用户信息

### 商品服务
- GET /product/list - 商品列表
- GET /product/{id} - 商品详情
- POST /product/create - 创建商品

### 订单服务
- POST /order/create - 创建订单
- GET /order/list - 订单列表

### 推荐服务
- GET /recommendation/personal - 个性化推荐
- GET /recommendation/popular - 热门推荐

### 秒杀服务
- POST /seckill/start - 秒杀抢购
- GET /seckill/stock - 查看库存

## 核心功能

1. **推荐算法**: Item-CF + 时间衰减 + 类别加权
2. **秒杀系统**: Redis原子操作 + 分布式锁
3. **微服务治理**: Nacos服务发现 + Sentinel限流

## 并发回归（秒杀）

执行一键并发回归脚本（输出成功率、延迟、库存一致性、DLQ/队列指标）：

```bash
node scripts/loadtest/p1-seckill-regression.mjs
```

可选参数（环境变量）：

- `BASE_URL`（默认 `http://localhost:8080`）
- `TOTAL`（默认 `200`）
- `CONCURRENCY`（默认 `20`）
- `STOCK`（默认 `100`）
- `PRODUCT_ID`（默认 `1`）
- `USER_OFFSET`（默认 `3000000`）

示例：

```bash
TOTAL=500 CONCURRENCY=50 STOCK=200 PRODUCT_ID=1 node scripts/loadtest/p1-seckill-regression.mjs
```