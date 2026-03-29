# microservices-ecommerce

基于微服务架构的电商平台，集成了**个性化推荐系统**（多路召回 + DeepFM 精排）、**高并发秒杀系统**（Redis 原子操作 + Sentinel 限流）、**实时监控体系**（Prometheus + Grafana）和**全链路灰度发布**（A/B Testing）四大核心能力。技术栈覆盖 Spring Cloud Alibaba + Python FastAPI + Vue 3，完整覆盖从用户下单到商品推荐的电商全链路。

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                    客户端                                    │
│                         Vue 3 SPA (localhost:80)                              │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │ HTTP
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                              API Gateway (8080)                              │
│                  Spring Cloud Gateway · JWT 认证 · 路由转发                  │
│                 JwtAuthenticationFilter → X-Authenticated-User-Id           │
└──────┬──────┬──────┬──────┬──────┬───────────────────────────────────────────┘
       │      │      │      │
       ▼      ▼      ▼      ▼
   ┌──────┐┌──────┐┌──────┐┌──────────────┐┌──────────────┐
   │User  ││Product││Order ││Recommendation││   Seckill   │
   │8001  ││8002  ││8003  ││    8004      ││    8005     │
   └──┬───┘└──┬───┘└──┬───┘└───────┬──────┘└───────┬──────┘
      │       │       │            │               │
      └───────┴───────┴────────────┴───────────────┘
                              │
              ┌───────────────┴───────────────┐
              │     MySQL (3306)              │
              │     Redis (6379)              │
              │     Nacos (8848)              │
              └───────────────────────────────┘
                          排序推理层
              ┌──────────────────────────────┐
              │ recommendation-rank-service  │
              │   8010 (Python FastAPI)     │
              │   DeepFM-Attention PyTorch  │
              └──────────────────────────────┘
                          监控基础设施
              ┌───────────────┐  ┌─────────────────┐
              │  Prometheus   │  │     Grafana     │
              │   (9090)     │  │    (3001)       │
              └───────────────┘  └─────────────────┘
```

---

## 技术栈

| 层级 | 技术选型 | 说明 |
|------|---------|------|
| **网关层** | Spring Cloud Gateway | JWT 认证、路由转发、请求限流 |
| **业务服务层** | Spring Boot 3.x + MyBatis-Plus | 微服务业务逻辑 |
| **推荐排序服务** | Python 3.10 + FastAPI + PyTorch | DeepFM-Attention CTR 预估 |
| **缓存 / 消息** | Redis 7.2（Redisson / Redis Stream）| 分布式锁、缓存、消息队列 |
| **数据库** | MySQL 8.0（utf8mb4） | 主数据存储 |
| **服务治理** | Nacos 2.3.0 | 服务注册发现、配置中心 |
| **流量防护** | Sentinel | 限流、熔断、降级 |
| **前端** | Vue 3 + Element Plus + Vite + Pinia | 用户界面 |
| **监控** | Prometheus + Grafana | 指标采集与可视化 |
| **容器化** | Docker Compose | 一键部署 |

---

## 核心微服务

| 服务名 | 端口 | 技术栈 | 核心职责 |
|--------|------|--------|---------|
| `api-gateway` | 8080 | Spring Cloud Gateway | 统一入口、JWT 鉴权、路由分发 |
| `user-service` | 8001 | Spring Boot + MySQL + Redis | 用户注册 / 登录 / JWT 签发 |
| `product-service` | 8002 | Spring Boot + MySQL + Redis | 商品管理、类目查询、批量查询 |
| `order-service` | 8003 | Spring Boot + MySQL | 订单创建、订单列表 |
| `recommendation-service` | 8004 | Spring Boot + MySQL + Redis | 多路召回、灰度分流、用户画像、在线学习 |
| `recommendation-rank-service` | 8010 | Python FastAPI + PyTorch | DeepFM-Attention CTR 精排推理 |
| `seckill-service` | 8005 | Spring Boot + Sentinel + Redis | 秒杀活动、流量防护、Redis 原子操作 |
| `frontend` | 80 | Vue 3 + Nginx | Web 应用界面 |

---

## 核心功能亮点

### 1. 推荐系统（两阶段：召回 + 精排）

**召回层 — 四条通道并行互补**

| 召回通道 | 算法 | 核心公式 | 作用 |
|---------|------|---------|------|
| ItemCF 协同过滤 | 加权余弦相似度 | $\text{sim}(i,j) = c_{ij} / (\|\|V_i\|\| \times \|\|V_j\|\|)$ | 个性化偏好挖掘 |
| 热门商品召回 | 时间衰减热门度 | $\text{popularScore} = \sum \text{weight} \times \text{decayFactor}^{\text{daysAgo}}$ | 大众化兜底推荐 |
| 同类目热门召回 | 偏好类目加权 | $\text{categoryScore} = \sum \text{weight} \times \text{decayFactor}^{\text{daysAgo}}$ | 精准偏好匹配 |
| TF-IDF 内容召回 | N-gram + MM 池化 | $\text{userProfile}[t] = \max_{\text{item}} \text{TF-IDF}(\text{item},t)$ | 解决冷启动 |

- ItemCF 增量更新：$O(n^2) \rightarrow O(n \cdot k)$，避免全量重算
- 用户惩罚因子：$\text{userPenalty} = 1/\log(1+|I_u|)$，抑制刷单用户偏差
- 热门惩罚：$1/\log(10 + (pop_i + pop_j)/2)$，解决热门物品相似度膨胀问题
- 差异化过滤：已购买商品在 ItemCF 中过滤但保留在热门召回中（已加购商品同理）

**排序层 — DeepFM-Attention 精排（仅灰度组 10%）**

```
FM 一阶:  y₁ = Σ wᵢ · xᵢ
FM 二阶:  y₂ = Σ⟨Vᵢ,Vⱼ⟩·xᵢ·xⱼ  (O(n·k) via (Σaᵢ)² - Σaᵢ²)
DNN:     多层全连接 [128→64→32] + BatchNorm + Dropout
Attention: DIN 风格，捕捉用户行为序列时序兴趣
输出:    sigmoid(y₁ + y₂ + DNN_out + Attention_out)
```

**后处理**

- 类目 / 品牌 / 价格多维度打散，MMR 算法（$\lambda = 0.6$）平衡相关性与多样性
- 智能跳过：候选池中偏好类目占比 > 60% 时跳过打散，避免过度干预
- 推荐理由自动生成（来自 ItemCF 相似度、用户偏好类目、召回通道标识）

**在线学习**

- Redis Stream 事件驱动：曝光负采样（Exposed but Not Clicked）
- 分钟级增量更新：学习率 $= 0.0001$（正常训练的 $1/10$），3 epochs 防震荡
- 样本数量保护：最少 100 条触发，最多 5000 条/次

**用户画像双轨更新**

- 实时增量（行为触发，毫秒级）→ Redis 计数器 + 标记脏数据
- 每日凌晨定时全量重建 → 从 MySQL 重算所有画像标签

**算法评测**

- 离线：Precision@K、Recall@K、NDCG@K、MRR@K、HitRate@K（按时间划分训练集/测试集）
- 在线：A/B 测试，HyperLogLog 低内存统计（~12KB/指标，误差 ~0.81%）

### 2. 秒杀系统

- **Sentinel** 限流 + 熔断降级，`@SentinelResource` 注解绑定 fallback
- **布隆过滤器**：恶意请求拦截，避免击穿数据库
- **Redis Lua 原子操作**：库存扣减（`DECR`）+ 分布式锁（Redisson）
- **Redis Stream** 异步下单：秒杀请求先写 Stream，消费者异步创建订单，解耦流量高峰
- **库存多级同步**：DB → Redis 定时同步 + 热点数据本地缓存
- **并发回归脚本**：
  ```bash
  node scripts/loadtest/p1-seckill-regression.mjs
  ```
  支持环境变量配置 `TOTAL`（默认 200）、`CONCURRENCY`（默认 20）、`STOCK`（默认 100）等

### 3. 监控体系

- **Prometheus**：Spring Boot Micrometer 指标采集，覆盖 QPS、延迟、错误率、JVM 内存
- **Grafana 预置看板**（位于 `Microservices E-Commerce` 文件夹）：
  - `Microservices Overview`：全服务健康状态、QPS 趋势、延迟分布
  - `Seckill Overview`：秒杀成功率、库存一致性、下单吞吐量

---

## 项目结构

```
microservices-ecommerce/
├── api-gateway/                    # API 网关
│   └── src/main/java/.../config/
│       ├── JwtAuthenticationFilter.java    # JWT 认证，从 Token 解析 userId 写入内部 Header
│       ├── RateLimitConfig.java           # 请求限流
│       └── CorsConfig.java                # 跨域配置
│
├── user-service/                   # 用户服务
│   └── src/main/java/.../
│       ├── controller/UserController.java # 注册/登录/用户信息
│       ├── service/UserService.java       # JWT 签发与验证
│       └── util/JwtUtil.java              # JJWT 工具类
│
├── product-service/                # 商品服务
│   └── src/main/java/.../
│       ├── controller/ProductController.java  # 商品 CRUD、类目查询、批量查询
│       ├── service/ProductService.java        # 商品缓存、Redis 缓存
│       └── entity/{Product,Category}.java
│
├── order-service/                  # 订单服务
│   └── src/main/java/.../
│       ├── controller/OrderController.java   # 创建订单、订单列表
│       └── service/OrderService.java
│
├── recommendation-service/         # 推荐主服务（Java Spring Boot）
│   └── src/main/java/com/ecommerce/recommendation/
│       ├── controller/RecommendationController.java  # 完整 REST API（推荐/曝光/A/B/画像）
│       ├── algorithm/
│       │   ├── ItemCFAlgorithm.java         # ItemCF 协同过滤 + 增量更新
│       │   └── ContentBasedAlgorithm.java   # TF-IDF 内容召回 + N-gram 分词
│       ├── service/
│       │   ├── CandidateRecallService.java  # 多路召回编排（4 通道并行）
│       │   ├── DiversityService.java        # MMR 多样性打散 + 智能跳过
│       │   ├── GrayReleaseService.java      # 一致性哈希灰度分组 + HyperLogLog 指标
│       │   ├── ExperimentService.java       # A/B 实验 CRUD 管理
│       │   ├── UserProfileService.java     # 用户画像双轨更新
│       │   ├── OnlineLearningService.java   # Redis Stream 在线学习
│       │   ├── IncrementalItemCFService.java# 增量相似度矩阵更新
│       │   ├── RankClientService.java       # Java → Python HTTP 调用排序服务
│       │   ├── ExposureService.java         # 曝光记录 + 负采样查询
│       │   └── RecommendationMetricsService.java  # 推荐效果指标
│       └── config/
│           ├── RedisConfig.java             # Redis 模板配置
│           ├── HttpClientConfig.java        # RestTemplate 连接池
│           └── OpenApiConfig.java           # Swagger 3（OpenAPI 文档）
│
├── recommendation-rank-service/     # 推荐排序服务（Python FastAPI）
│   ├── app/
│   │   ├── main.py                 # FastAPI 入口、所有 API 路由
│   │   ├── model.py                # 标准 DeepFM（FM 一阶 + 二阶 + DNN）
│   │   ├── model_attention.py      # DeepFM-Attention（含 AttentionLayer、DIN 风格）
│   │   ├── features.py             # 特征工程、合成数据生成、序列特征构建
│   │   ├── online_learning.py      # 增量学习服务
│   │   ├── evaluation.py           # 离线评测（P/R/NDCG/MRR/HitRate）
│   │   └── schemas.py              # Pydantic 请求/响应模型
│   └── Dockerfile
│
├── seckill-service/                # 秒杀服务
│   └── src/main/java/com/ecommerce/seckill/
│       ├── controller/SeckillController.java   # 秒杀 API（Sentinel 注解）
│       ├── service/
│       │   ├── SeckillService.java              # 核心秒杀逻辑
│       │   ├── SeckillCacheService.java         # Redis 库存 Lua 原子操作
│       │   └── SeckillOrderStreamConsumer.java   # Redis Stream 消费者
│       ├── config/
│       │   ├── BloomFilterConfig.java            # 布隆过滤器（Guava）
│       │   └── SentinelFallbackHandler.java      # 熔断降级处理
│       └── scheduler/SeckillStockSyncScheduler.java  # 库存定时同步
│
├── frontend/                       # Vue 3 前端
│   └── src/
│       ├── views/                  # 页面组件
│       ├── stores/                  # Pinia 状态管理
│       └── api/                     # Axios 接口封装
│
├── infrastructure/                  # 基础设施配置
│   ├── mysql/
│   │   ├── init/                    # SQL 初始化脚本（表结构 + 初始数据）
│   │   └── conf/custom.cnf         # MySQL 配置（utf8mb4）
│   └── monitoring/
│       ├── prometheus/prometheus.yml             # 指标采集配置
│       └── grafana/provisioning/                 # 数据源 + 看板自动配置
│
├── scripts/loadtest/               # 并发压测脚本
│   └── p1-seckill-regression.mjs    # 秒杀并发回归（输出成功率、延迟、库存一致性）
│
├── docker-compose.yml               # 全量服务一键启动（包含所有服务 + 监控）
├── docker-compose.infra.yml         # 仅启动基础设施（MySQL/Redis/Nacos）
├── pom.xml                         # Maven 多模块父 POM
└── README.md
```

---

## 快速启动

### 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | Java 运行时 |
| Maven | 3.8+ | 项目构建 |
| Node.js | 18+ | 前端构建 |
| Docker & Docker Compose | latest | 容器化部署 |

MySQL（8.0）、Redis（7.2）、Nacos（2.3.0）已包含于 `docker-compose.yml`，无需单独安装。

### 步骤一：配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 文件，必填项说明：

```env
# JWT 签名密钥（生产环境请使用随机字符串，不少于 32 字符）
JWT_SECRET=your-secret-key-here
MYSQL_DATABASE=ecommerce
MYSQL_USER=ecommerce_user
MYSQL_ROOT_PASSWORD=your-db-password
```

### 步骤二：一键启动全量服务

```bash
docker compose up -d --build
```

首次启动会自动执行 `infrastructure/mysql/init/` 下的 SQL 初始化脚本，完成数据库表创建和初始数据导入。`recommendation-rank-service` 已直接在 `docker-compose.yml` 中配置构建上下文，支持一键构建，无需手工操作。

### 步骤三：启动前端（开发模式）

```bash
cd frontend
npm install
npm run dev
```

前端访问 `http://localhost:5173`，Vite 代理自动将 API 请求转发至 `http://localhost:8080`（API Gateway）。

### 步骤四：启动监控栈（可选）

```bash
docker compose up -d prometheus grafana
```

| 组件 | 地址 | 默认账号 |
|------|------|---------|
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3001` | `admin` / `admin123` |

预置看板位于 Grafana 的 `Microservices E-Commerce` 文件夹，包含 `Microservices Overview` 和 `Seckill Overview` 两个看板。

### 仅启动基础设施（开发调试用）

```bash
docker compose -f docker-compose.infra.yml up -d
```

---

## API 文档

所有服务均内置 Swagger 3（OpenAPI）在线文档：

| 服务 | Swagger UI 地址 |
|------|---------------|
| API Gateway | `http://localhost:8080/swagger-ui.html` |
| 用户服务 | `http://localhost:8001/swagger-ui.html` |
| 商品服务 | `http://localhost:8002/swagger-ui.html` |
| 订单服务 | `http://localhost:8003/swagger-ui.html` |
| 推荐服务 | `http://localhost:8004/swagger-ui.html` |
| 秒杀服务 | `http://localhost:8005/swagger-ui.html` |
| 排序服务 | `http://localhost:8010/docs`（FastAPI 自动文档，ReDoc：`/redoc`） |

### 推荐服务完整 API

#### 个性化推荐

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/api/recommendation/personal` | 返回推荐商品 ID 列表 | 需要 |
| GET | `/api/recommendation/personal/products` | 返回带详情的推荐商品列表（含推荐理由） | 需要 |
| GET | `/api/recommendation/popular` | 热门商品 ID 列表（无需登录） | 不需要 |
| GET | `/api/recommendation/popular/products` | 热门商品详情列表（无需登录） | 不需要 |

#### 行为数据

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/recommendation/behavior` | 记录用户行为（view/click/cart/favorite/buy） | 需要 |
| POST | `/api/recommendation/exposure` | 记录单次商品曝光 | 需要 |
| POST | `/api/recommendation/exposure/batch` | 批量记录商品曝光（推荐结果返回时调用） | 需要 |
| GET | `/api/recommendation/exposure/samples` | 获取曝光负样本（用于训练负采样） | 需要 |

#### 离线评测

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/api/recommendation/baseline/compare` | 热门 / ItemCF二值 / ItemCF评分三种算法基线对比 | 需要 |
| GET | `/api/recommendation/refresh` | 清除所有 Redis 缓存（矩阵/热门/个性化） | 需要 |

#### 灰度发布与 A/B 测试

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/api/recommendation/gray/status` | 灰度开关状态和流量比例 | 需要 |
| GET | `/api/recommendation/gray/check` | 查询用户所属分组（灰度组 / 对照组） | 需要 |
| GET | `/api/recommendation/gray/metrics` | 灰度实验指标（曝光/点击/加购/下单，HyperLogLog 统计） | 需要 |
| GET | `/api/recommendation/gray/compare` | 灰度组 vs 对照组 CTR / 加购率 / 下单率对比 | 需要 |
| POST | `/api/recommendation/gray/click` | 记录推荐商品点击（用于 A/B 效果评估） | 需要 |
| POST | `/api/recommendation/gray/cart` | 记录推荐商品加购 | 需要 |
| POST | `/api/recommendation/gray/order` | 记录推荐商品下单（含下单金额，用于 GMV 对比） | 需要 |

#### A/B 实验管理

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/recommendation/experiment/create` | 创建 A/B 实验（指定变体和流量比例） | 需要 |
| GET | `/api/recommendation/experiment/list` | 获取所有实验列表 | 需要 |
| GET | `/api/recommendation/experiment/{id}` | 获取单个实验详情 | 需要 |
| GET | `/api/recommendation/experiment/{id}/stats` | 获取实验各变体的统计数据 | 需要 |
| GET | `/api/recommendation/experiment/user/{userId}` | 查询用户被分配的实验变体 | 需要 |
| POST | `/api/recommendation/experiment/{id}/end` | 提前终止实验 | 需要 |
| DELETE | `/api/recommendation/experiment/{id}` | 删除已结束的实验 | 需要 |

#### 用户画像

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/api/recommendation/profile/{userId}` | 获取用户画像（类目偏好、品牌偏好、活跃度、RFM 标签） | 需要 |
| POST | `/api/recommendation/profile/{userId}/refresh` | 强制重新计算用户画像 | 需要 |

#### 排序服务 API（内部调用）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/rank/simple` | 对候选商品进行 CTR 排序 | `X-API-Key` Header |
| POST | `/model/train` | 触发模型训练 | `X-API-Key` Header |
| POST | `/model/incremental-update` | 增量更新模型参数 | `X-API-Key` Header |
| POST | `/model/evaluate` | 离线评测（Precision/Recall/NDCG/MRR） | `X-API-Key` Header |
| POST | `/data/generate` | 生成合成训练数据 | `X-API-Key` Header |
| GET | `/health` | 健康检查 | 不需要 |

---

## 设计原则与安全机制

### 多层次安全体系

```
外部请求
  │
  ▼
API Gateway（JWT 验签 + Token 过期检查）
  │
  ▼
X-Authenticated-User-Id 注入内部 Header
  │
  ▼
下游业务服务（始终使用 Header 中的 userId，忽略 URL 参数）
  │
  ▼
推荐服务 → 排序服务（X-API-Key 服务间鉴权）
```

**禁止假数据原则**：推荐链路中任何无法获取真实数据的环节均明确失败或跳过，不生成虚假的替代数据，保证推荐质量可控可预期。

### 一致性保证

- **灰度分组一致性**：用户分组结果缓存至 Redis（TTL = 7 天），同一用户 7 天内始终命中同一分组
- **推荐缓存一致性**：用户产生新行为时主动失效对应缓存（Write-Through），而非被动等待 TTL 过期
- **用户画像一致性**：双轨更新机制确保实时性和准确性的平衡

### 服务治理

- **Nacos 服务注册与发现**：所有服务启动后自动注册至 Nacos，支持服务动态上下线
- **Sentinel 流量控制**：秒杀接口绑定 `@SentinelResource`，超出 QPS 限制自动触发降级逻辑
- **请求链路追踪**：每个请求携带 `X-Request-Id` Header，可串联全链路日志

---

## 秒杀并发回归

执行一键并发回归脚本（输出成功率、延迟分布、库存一致性、DLQ 队列指标）：

```bash
node scripts/loadtest/p1-seckill-regression.mjs
```

可选环境变量参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `BASE_URL` | `http://localhost:8080` | API 入口地址 |
| `TOTAL` | `200` | 总请求数 |
| `CONCURRENCY` | `20` | 并发数 |
| `STOCK` | `100` | 初始库存 |
| `PRODUCT_ID` | `1` | 秒杀商品 ID |
| `USER_OFFSET` | `3000000` | 用户 ID 起始偏移 |

示例：自定义并发压测

```bash
TOTAL=500 CONCURRENCY=50 STOCK=200 PRODUCT_ID=1 node scripts/loadtest/p1-seckill-regression.mjs
```

---

*项目整理时间：2026-03-28*
