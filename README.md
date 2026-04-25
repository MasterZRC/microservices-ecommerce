# microservices-ecommerce

> 从用户点击到推荐成单，一条完整的技术链路

一个覆盖**推荐召回 → DeepFM 精排 → 秒杀高并发 → 全链路监控**的微服务电商实战项目。包含多路召回、在线学习、A/B 灰度发布、Sentinel 限流、Grafana 看板等生产级工程实践。

[![Java](https://img.shields.io/badge/Java-17%2B-blue)]()  [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)]()  [![Python](https://img.shields.io/badge/Python-3.10-orange)]()  [![Vue%203](https://img.shields.io/badge/Vue%203-3.4-yellow)]()  [![Docker](https://img.shields.io/badge/Docker%20Compose-v2-blue)]()  [![License](https://img.shields.io/badge/License-MIT-lightgrey)]()

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

## 核心能力

### 推荐系统

推荐系统是电商的核心竞争力，也是算法与工程结合最难的部分。

**召回层：4 条互补通道，覆盖个性化和兜底**

| 通道 | 算法 | 解决什么问题 |
|------|------|------------|
| ItemCF 协同过滤 | 加权余弦相似度 | 挖掘用户历史偏好 |
| 热门商品召回 | 时间衰减热门度 | 冷启动用户有内容可看 |
| 同类目热门召回 | 偏好类目加权 | 精准命中用户偏好类目 |
| TF-IDF 内容召回 | N-gram + 最大池化 | 解决新商品冷启动 |

工程细节：增量更新 `O(n²) → O(n·k)`、热门商品相似度惩罚、用户活跃度偏差抑制。

**精排层：DeepFM-Attention，兼顾特征交互与序列兴趣**

- FM 二阶通过 `(Σaᵢ)² - Σaᵢ²` 将复杂度从 `O(n²·k)` 降至 `O(n·k)`
- DIN 风格 Attention 捕捉用户行为序列的时序兴趣
- 仅灰度组 10% 流量使用精排，与对照组 A/B 对比效果

**在线学习：分钟级增量更新**

- Redis Stream 驱动曝光负采样（Exposed but Not Clicked）
- 学习率 `lr = 0.0001`（正常训练 1/10），3 epochs 防震荡
- 用户画像双轨：实时增量（毫秒级） + 每日凌晨全量重建

### 高并发秒杀

秒杀的本质是：在有限库存下，如何让尽可能多的真实用户成功下单。

- **Sentinel 限流 + 熔断降级**：超出 QPS 限制自动降级，商品售罄时保护数据库
- **布隆过滤器**：恶意刷单请求在 Redis 层拦截，不进业务层
- **Redis Lua 原子操作**：`DECR` 库存扣减 + Redisson 分布式锁，保证库存一致性
- **Redis Stream 异步下单**：秒杀请求先写 Stream，消费者异步创建订单，峰值与业务解耦
- **库存多级同步**：DB → Redis 定时同步 + 本地热点缓存，减少 DB 压力

并发回归脚本验证：`node scripts/loadtest/p1-seckill-regression.mjs`，输出成功率、延迟 P99、库存一致性。

### 全链路监控与灰度发布

可观测 + 流量控制是微服务从 demo 到生产的最后一步。

**监控体系：Prometheus + Grafana + SkyWalking + Loki**

| 组件 | 端口 | 作用 |
|------|------|------|
| Prometheus | 9090 | 指标采集与存储 |
| Grafana | 3001 | 监控看板可视化 |
| SkyWalking | 8082 | 分布式链路追踪 |
| Loki | 3100 | 日志聚合与搜索 |
| AlertManager | 9093 | 告警通知 |

- Spring Boot Micrometer 覆盖 QPS、延迟、错误率、JVM 内存
- Grafana 预置 `Microservices Overview`（全局健康）、`Seckill Overview`（秒杀专项）、`Alerts Overview`（告警监控）三个看板
- SkyWalking 无侵入式链路追踪，可视化服务拓扑和完整调用链
- Loki 日志聚合，支持 `{service="seckill-service", level="ERROR"}` 标签过滤

**告警体系：Prometheus AlertManager + 自定义规则**

| 告警名称 | 触发条件 | 严重级别 |
|---------|---------|---------|
| ServiceDown | 服务宕机超过1分钟 | 严重 |
| HighLatencyP95 | P95延迟超过2秒 | 警告 |
| SeckillSuccessRateLow | 秒杀成功率低于80% | 警告 |
| SeckillQueueBacklog | 队列积压超过1000条 | 严重 |
| RecommendationTimeout | 排序P99超过5秒 | 警告 |
| OrderFailureRateHigh | 订单失败率超过5% | 警告 |

**灰度发布：A/B Testing + 一致性哈希**

- 一致性哈希分组，同一用户 7 天内始终命中同一组，体验一致
- HyperLogLog 低内存统计指标（~12KB/指标，误差 ~0.81%），不干扰业务性能
- 支持创建实验、查看分组、对比 CTR / 加购率 / 下单率

---

## 快速启动

```bash
# 1. 克隆 & 配置
git clone https://github.com/YOUR_USERNAME/microservices-ecommerce.git
cp .env.example .env   # 编辑 JWT_SECRET 等字段

# 2. 一键启动（包含所有服务 + 监控栈）
docker compose up -d --build

# 3. 启动前端
cd frontend && npm install && npm run dev

# 4. 访问
#   前端：http://localhost:80
#   Swagger：http://localhost:8080/swagger-ui.html
#   Grafana：http://localhost:3001 （admin / admin123）
#   SkyWalking：http://localhost:8082 （链路追踪）
#   AlertManager：http://localhost:9093 （告警配置）
#   Elasticsearch：http://localhost:9200 （数据存储）
```

首次启动自动执行 SQL 初始化脚本。MySQL / Redis / Nacos 已内置于 docker-compose，无需单独安装。

---

## 技术栈

Spring Cloud Alibaba · Spring Boot 3.x · MyBatis-Plus · Python 3.10 · FastAPI · PyTorch · Redis 7.2 · MySQL 8.0 · Nacos 2.3.0 · Sentinel · Docker Compose · SkyWalking 9.x · Loki · Prometheus · AlertManager

---

## 贡献与反馈

欢迎提交 Issue 和 PR。如果这个项目对你有帮助，点个 Star 是最大的支持。

---

<details>
<summary>项目结构</summary>

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
│   ├── loki/                        # Loki 日志聚合配置
│   │   ├── loki.yml                 # Loki 服务配置
│   │   └── promtail.yml             # Promtail 日志采集配置
│   └── monitoring/
│       ├── prometheus/
│       │   ├── prometheus.yml       # 指标采集配置
│       │   └── rules/alerts.yml     # 告警规则（秒杀/推荐/服务健康）
│       ├── grafana/provisioning/    # Grafana 自动配置
│       │   ├── datasources/         # 数据源（Prometheus + Loki）
│       │   └── dashboards/json/    # 监控看板（3个）
│       └── alertmanager/            # AlertManager 告警配置
│           └── alertmanager.yml     # 告警路由和接收器配置
│
├── scripts/loadtest/               # 并发压测脚本
│   └── p1-seckill-regression.mjs    # 秒杀并发回归（输出成功率、延迟、库存一致性）
│
├── docker-compose.yml               # 全量服务一键启动（包含所有服务 + 监控）
├── docker-compose.infra.yml         # 仅启动基础设施（MySQL/Redis/Nacos）
├── pom.xml                         # Maven 多模块父 POM
└── README.md
```

</details>

<details>
<summary>完整 API 参考</summary>

### Swagger 在线文档

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

</details>

<details>
<summary>设计原则与安全机制</summary>

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

</details>

<details>
<summary>秒杀并发回归</summary>

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

</details>
