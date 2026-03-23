# 项目结构总览（microservices-ecommerce）

更新时间：2026-03-23

## 1. 仓库总览

```text
microservices-ecommerce/
├── api-gateway/                    # 网关服务（统一入口）
├── user-service/                   # 用户服务
├── product-service/                # 商品服务
├── order-service/                  # 订单服务
├── recommendation-service/         # 推荐编排服务（Java）
├── recommendation-rank-service/    # 推荐排序服务（Python）
├── seckill-service/                # 秒杀服务
├── frontend/                       # Vue3 前端
├── infrastructure/                 # 基础设施配置（MySQL、监控）
├── scripts/                        # 压测与脚本
├── docs/                           # 文档中心
├── docker-compose.yml              # 全量编排
├── docker-compose.infra.yml        # 仅基础设施编排
├── pom.xml                         # Maven 聚合工程
└── README.md                       # 项目说明
```

## 2. 后端微服务（Java）

### 服务职责

- `api-gateway`：统一路由转发与入口治理。
- `user-service`：注册、登录、用户信息。
- `product-service`：商品查询与管理。
- `order-service`：订单创建与查询。
- `recommendation-service`：推荐逻辑编排与策略控制。
- `seckill-service`：高并发秒杀链路。

### 统一目录骨架

```text
<service>/
├── Dockerfile
├── pom.xml
├── src/
│   └── main/
│       ├── java/com/ecommerce/<domain>/
│       │   └── *Application.java
│       └── resources/
└── target/   # 构建产物
```

已识别启动类：

- `com.ecommerce.gateway.GatewayApplication`
- `com.ecommerce.user.UserServiceApplication`
- `com.ecommerce.product.ProductServiceApplication`
- `com.ecommerce.order.OrderServiceApplication`
- `com.ecommerce.recommendation.RecommendationServiceApplication`
- `com.ecommerce.seckill.SeckillServiceApplication`

## 3. 推荐排序服务（Python）

```text
recommendation-rank-service/
├── app/
│   ├── main.py
│   ├── model.py
│   ├── features.py
│   ├── schemas.py
│   ├── evaluation.py
│   └── config.yaml
├── models/
│   └── deepfm.pt
├── requirements.txt
└── Dockerfile
```

说明：该服务在编排中使用 `8010` 端口，供 `recommendation-service` 调用。

## 4. 前端（Vue3）

```text
frontend/
├── src/
│   ├── api/
│   ├── router/
│   ├── store/
│   ├── views/
│   ├── components/
│   ├── App.vue
│   └── main.js
├── package.json
├── vite.config.js
├── Dockerfile
└── nginx.conf
```

## 5. 运行拓扑（docker-compose.yml）

- 基础设施：`mysql(3306)`、`redis(6379)`、`nacos(8848/9848)`
- 业务服务：`user(8001)`、`product(8002)`、`order(8003)`、`recommendation(8004)`、`seckill(8005)`
- 算法服务：`recommendation-rank-service(8010)`
- 网关入口：`api-gateway(8080)`
- 监控：`prometheus(9090)`、`grafana(3001)`

## 6. 基础设施目录

```text
infrastructure/
├── mysql/
│   └── init/
└── monitoring/
    ├── prometheus/
    └── grafana/
```

## 7. 模块关系（简图）

```text
Frontend
   ↓
API Gateway (8080)
   ├── User Service (8001)
   ├── Product Service (8002)
   ├── Order Service (8003)
   ├── Recommendation Service (8004) ──→ Recommendation Rank Service (8010)
   └── Seckill Service (8005)

All services -> MySQL / Redis / Nacos
Monitoring   -> Prometheus / Grafana
```

## 8. 建议阅读顺序

1. `README.md` + `docker-compose.yml`
2. `api-gateway`（入口与路由）
3. `user/product/order`（核心业务链）
4. `seckill-service`（高并发场景）
5. `recommendation-service` + `recommendation-rank-service/app`
6. `docs/recommendation` + `docs/benchmark`

## 9. 维护建议

- 新增服务时同步更新 `README.md`、`docker-compose*.yml`、本文件。
- 统一 Java 服务目录骨架，降低维护复杂度。
- `target/`、`__pycache__/` 作为构建产物默认忽略。
