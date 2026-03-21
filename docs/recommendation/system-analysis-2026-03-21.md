# 电商推荐系统 — 架构与算法详细分析

> 文档版本：2026-03-21
> 基于代码版本：microservices-ecommerce（所有 P0/P1/P2 问题已全部修复）

---

## 一、系统架构总览

整个推荐系统由 4 个独立服务组成，采用**多路召回 + 精排**的两阶段推荐范式：

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                 前端 (Vue 3 + Element Plus)                      │
│                    Home.vue 发起 /api/recommendation/personal/products           │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │ HTTP (用户已登录，携带 JWT Token)
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        API Gateway / JWT Auth Filter                             │
│              验证 Token，提取 userId → 写入 X-Authenticated-User-Id Header        │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │ RestTemplate (X-Authenticated-User-Id Header)
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    recommendation-service (Java Spring Boot)                      │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  RecommendationController — 所有 REST API 入口                              │  │
│  │  RecommendationService — 协调召回 + 排序 + 后处理                            │  │
│  │  CandidateRecallService — 多路召回引擎                                       │  │
│  │  GrayReleaseService — 灰度分组与 A/B 指标记录                               │  │
│  │  RankClientService — DeepFM 排序服务客户端（带 API Key 认证）                │  │
│  │  OnlineLearningService — 增量学习定时任务（每 30 分钟）                       │  │
│  │  UserProfileService — 用户画像标签构建                                       │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  ┌─────────────────┐    │
│  │  MySQL       │  │   Redis      │  │ product-service  │  │ rank-service    │    │
│  │ user_behavior│  │  多级缓存     │  │  (商品信息查询)   │  │ (Python FastAPI)│    │
│  └──────────────┘  └──────────────┘  └──────────────────┘  └─────────────────┘    │
└────────────────────────────────────────────────────────────────┬──────────────────┘
                                   │
                         ┌─────────┴──────────┐
                         │   DeepFM Rerank    │ (仅灰度用户)
                         │  X-API-Key Header  │ (独立 API Key 认证)
                         └────────────────────┘
```

---

## 二、推荐流程（完整链路）

当用户访问首页时，推荐系统经历以下步骤：

### 步骤 1：路由判断（RecommendationController）

```
GET /api/recommendation/personal/products?userId=xxx&limit=32
```

- 从 `X-Authenticated-User-Id` header 获取真实用户 ID（防伪造）
- 调用 `RecommendationService.getPersonalizedProductDetails()`

### 步骤 2：多路召回（CandidateRecallService.multiChannelRecall）

同时从三个渠道召回候选商品，合并去重后移除用户已交互商品：

| 渠道 | 方法 | 策略 |
|------|------|------|
| ItemCF 协同过滤 | `recallByItemCF()` | 基于用户历史行为的相似商品，最多 80 个 |
| 热门召回 | `recallByPopular()` | 全局热门商品（带时间衰减），最多 40 个 |
| 类目召回 | `recallByCategory()` | 用户偏好类目下的热门商品，最多 40 个 |
| **合并** | `LinkedHashSet` | 三路合并去重，移除已交互商品 |
| **兜底** | `getColdStartFallback()` | 三路均空时返回热门商品，永不生成假数据 |

**时间衰减因子**：`score = baseWeight × decayFactor^(daysAgo)`
- `decayFactor = 0.95`（可配置），最大衰减 30 天
- 行为权重：`buy=8, favorite=5, cart=4, click=2, view=1`

### 步骤 3：灰度分流（GrayReleaseService）

```
isGray = isGrayByHash(userId)  // hash(userId) % 100 < grayRatio (默认 10%)
```

- 一致性哈希保证同一用户始终命中同一分组
- 分组结果缓存至 Redis，TTL 7 天
- **对照组**（非灰度）：跳过 DeepFM 排序
- **灰度组**：进入步骤 4

### 步骤 4：DeepFM 重排（仅灰度用户）

灰度用户请求发送到 `recommendation-rank-service`：

```
POST /rank/simple
Header: X-API-Key: <rank_service_api_key>
Body: {
  "user_id": 123,
  "candidates": [101, 102, ...],
  "user_features": { "view_1d": 5, "click_1d": 2, ... },
  "item_features": { "101": { "category_id": 3, "brand_id": 12, ... }, ... }
}
```

- Python FastAPI 服务用 DeepFM 模型预测每个商品的 CTR 分数
- 按分数降序返回排序后的商品 ID 列表
- 覆盖率低于 30% 时拒绝请求（防止商品服务故障导致质量下降）
- 排序失败时降级回原始候选列表

### 步骤 5：类目打散（RecommendationService.shuffleByCategory）

排序后强制打散同类目商品：

```
最多连续出现 maxConsecutiveSameCategory（默认 2）个同类商品
超出后贪心插入不同类目的商品
末尾若有重复，进行局部交换
```

### 步骤 6：推荐理由生成

后端根据 ItemCF 相似度矩阵为每个推荐商品生成解释：

| 场景 | 推荐理由 |
|------|---------|
| 商品与用户历史浏览同类目且相似度高 | "与你近期浏览的商品相似" |
| 商品属于用户偏好类目 | "符合你偏好的商品类目" |
| 商品无关联特征（冷启动/热门） | "当前热门推荐" |
| 其他协同过滤推荐 | "为你精选推荐" |

### 步骤 7：结果缓存

```
Redis Key: recommendation:personal:{userId}
TTL: 1 小时
```

用户下次访问时直接返回缓存，避免重复计算。

---

## 三、算法详解

### 3.1 召回层 — ItemCF 协同过滤

**核心公式**：加权余弦相似度

```
sim(item_i, item_j) = c_ij / (||V_i|| × ||V_j||) × category_bonus × hot_penalty

其中：
- c_ij = Σ(userPenalty × score_u_i × score_u_j)  // 共现加权
- userPenalty = 1 / log(1 + |I_u|)                // 活跃用户降权
- category_bonus = 1.2（同类目）× 1.0（不同类目）
- hot_penalty = 1 / log(10 + (pop_i + pop_j) / 2)  // 热门商品降权
```

**推荐得分**：

```
score(user, item_k) = Σ_{item_i ∈ 历史交互} historyScore_i × sim(item_i, item_k)
```

### 3.2 排序层 — DeepFM 点击率预估

**模型架构**（Deep Factorization Machine）：

```
输入层：8 个稀疏特征 + 10 个密集特征
    ↓
FM 组件：
  ├─ 一阶：Linear(sum of embeddings + dense features)
  └─ 二阶：0.5 × [ (ΣV)² - Σ(V²) ]  ← 捕捉二阶特征交互
    ↓
DNN 组件：
  └─ [Input] → 128 → ReLU → BN → Dropout(0.2)
            → 64  → ReLU → BN → Dropout(0.2)
            → 32  → ReLU → BN → Dropout(0.2)
            → [1]
    ↓
输出：sigmoid(first_order + second_order + dnn_output)
```

**稀疏特征**（8 个，分桶/Embedding 后输入）：
- 用户：`view_1d_bucket`、`click_1d_bucket`、`active_days_bucket`、`prefer_category_idx`
- 商品：`category_id (mod 100)`、`brand_id (mod 50)`、`price_bucket`、`sales_bucket`

**密集特征**（10 个，归一化后输入）：
- 用户 1 天行为：`view_1d / 100`、`click_1d / 50`、`cart_1d / 20`、`buy_1d / 10`
- 用户活跃：`last_active_hours / 720`
- 商品热门：`hot_score / 10000`、`price_ratio`
- 交叉匹配：`category_match`、`brand_match`（偏好类目/品牌是否命中）

### 3.3 增量学习（Online Learning）

每 30 分钟定时执行：

```
1. 收集正样本（近 30 分钟内的 buy/cart 行为）
2. 收集负样本（近 60 分钟内的 view/click 行为，排除 buy/cart）
3. 为每个样本构建：
   - 用户特征：1 天 + 7 天行为统计（真实数据库查询）
   - 商品特征：类目、品牌、价格分桶、销量分桶（Redis 缓存）
4. 调用 /model/incremental-update 接口
   - 学习率：正常学习的 1/10
   - Epochs：3
   - Mini-batch：64
5. 幂等控制：已处理行为记录到 Redis（TTL 24h）
```

**关键约束**：
- 单次最少 100 条样本才触发更新
- 单次最多 5000 条，避免模型震荡
- `brand_id`/`price_bucket`/`sales_bucket` 从 Redis 缓存获取，缓存未命中时降级为 0

---

## 四、特征工程体系

### 4.1 用户特征（两类来源）

| 特征 | 来源 | 说明 |
|------|------|------|
| `view_1d/7d/30d` | MySQL `user_behavior` 表真实查询 | 行为数量统计 |
| `click_1d/7d` | MySQL `user_behavior` 表真实查询 | 点击数量 |
| `cart_1d/7d` | MySQL `user_behavior` 表真实查询 | 加购数量 |
| `buy_1d/7d` | MySQL `user_behavior` 表真实查询 | 购买数量 |
| `last_active_hours` | MySQL `user_behavior` 表真实查询 | 末次行为距今小时数 |
| `prefer_category` | `buildItemCategoryMap()` + 行为权重 | Top 3 偏好类目 |
| `prefer_brand` | `buildItemBrandMap()` + 行为权重 | Top 2 偏好品牌 |

### 4.2 商品特征（两类来源）

| 特征 | 来源 | 说明 |
|------|------|------|
| `category_id` | `product-service` REST API | 真实类目 ID |
| `brand_id` | `product-service` REST API → hash 映射 | 品牌哈希（% 100） |
| `price_bucket` | `product-service` REST API → 分桶 | 价格 / 100 |
| `sales_bucket` | `product-service` REST API → 分桶 | 销量 / 100 |
| `hot_score` | `product-service` REST API | 真实销量值 |

**数据流**：
1. `RecommendationService.buildItemFeaturesForCandidates()` 调用 `getProductInfoMap()`
2. `getProductInfoMap()` 调用 `POST /api/product/batch`（精确 ID 查询）
3. 失败时降级为逐个 `GET /api/product/{id}` 查询

---

## 五、灰度发布与 A/B 测试

### 5.1 灰度分组机制

```
userId → hash(userId) % 100 < grayRatio → 灰度组（DeepFM）
                            ≥ grayRatio → 对照组（ItemCF）
```

- 分组一旦确定，7 天内不变（Redis TTL 7 天）
- 配置开关：`recommendation.gray.enabled`（默认 false）
- 配置比例：`recommendation.gray.ratio`（默认 10%）

### 5.2 A/B 指标采集

使用 Redis HyperLogLog 记录每日各组指标：

```
recommendation:metrics:{group}:{exposure|click|cart|order}:{date}
  └─ group = gray | control
  └─ date = YYYY-MM-DD
  └─ 再按 algorithm 细分（deepfm | itemcf）
```

**计算指标**：
- CTR = click_count / exposure_count
- 加购率 = cart_count / exposure_count
- 下单率 = order_count / exposure_count

### 5.3 A/B 实验框架

独立于灰度发布的通用 A/B 实验框架，支持：
- 创建实验：`POST /experiment/create`（指定变体列表和流量比例）
- 用户分配：`isGrayByHash(experimentId + userId)` 确定性分配
- 指标收集：复用 GrayReleaseService 的埋点基础设施

---

## 六、用户画像服务

### 6.1 画像标签体系

| 维度 | 标签值 |
|------|--------|
| 活跃度 | 高活 / 中活 / 低活 / 沉默 |
| 消费能力 | 高消费 / 中消费 / 低消费 |
| 浏览深度 | 深度浏览 / 中度浏览 / 浅度浏览 |
| 价格偏好 | 高价位 / 中价位 / 低价位 |
| 偏好类目 | Top 3 类目（购买/加购权重最高） |

### 6.2 更新策略

| 触发方式 | 更新内容 |
|---------|---------|
| 行为记录时（`recordBehavior`） | 增量计数器 + 偏好类目 + 脏标记 |
| 每日凌晨 3 点（定时任务） | 读取所有脏用户，全量重建画像 |

---

## 七、数据存储设计

### 7.1 MySQL 表结构

**user_behavior 表**：
- 主数据源，记录 view/click/cart/buy/favorite 行为
- 被所有召回算法、特征构建、在线学习共同依赖

**product 表**（product-service）：
- `id`, `category_id`, `brand`, `price`, `sales`
- 通过 REST API 访问，不直接 JOIN

### 7.2 Redis 缓存策略

| 键前缀 | 数据 | TTL |
|--------|------|-----|
| `recommendation:popular:*` | 全局热门商品 ID 列表 | 1h |
| `recommendation:item:category:*all` | 商品→类目映射 Map | 1h |
| `recommendation:item:features:*all` | 完整商品特征 Map | 1h |
| `recommendation:similarity:*all` | ItemCF 相似度矩阵 | 24h |
| `recommendation:personal:*` | 用户个性化推荐结果 | 1h |
| `recommendation:behavior:*` | 用户已交互商品集合 | 30d |
| `recommendation:gray:users:*` | 用户灰度分组 | 7d |
| `recommendation:metrics:*` | A/B 指标 HyperLogLog | 8d |
| `user:profile:*` | 用户画像 Hash | 30d |
| `user:profile:dirty` | 脏用户集合 | - |
| `online_learning:processed:*` | 已处理行为记录 | 24h |
| `online_learning:sample_queue` | 增量样本队列 | - |

---

## 八、安全机制

| 层次 | 机制 | 实现 |
|------|------|------|
| 网关认证 | JWT Token → `X-Authenticated-User-Id` | API Gateway JwtAuthenticationFilter |
| 接口认证 | 所有 recommendation API 需登录 | Controller `HEADER_AUTH_USER_ID` |
| 排序服务认证 | `X-API-Key` Header | FastAPI `_require_api_key()` |
| 特征完整性验证 | `last_active_hours > 720` 异常检测 | `RankClientService.validateFeatures()` |
| 降级策略 | 排序失败返回原始候选 | `RankClientService.rank()` catch |
| 无假数据 | 覆盖不足拒绝，特征缺失跳过 | `main.py` + `RankClientService` |

---

## 九、服务间通信

| 调用方向 | 协议 | 鉴权 |
|---------|------|------|
| Frontend → recommendation-service | HTTP/REST (Axios) | JWT Token |
| recommendation-service → product-service | HTTP/REST (RestTemplate) | 无（内网） |
| recommendation-service → rank-service | HTTP/REST (RestTemplate) | `X-API-Key` Header |
| rank-service → MySQL | pymysql | 用户名/密码 |
| rank-service → 模型文件 | torch.load | 文件系统 |

---

## 十、核心配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `recommendation.recall.cf-count` | 80 | ItemCF 召回数量 |
| `recommendation.recall.popular-count` | 40 | 热门召回数量 |
| `recommendation.recall.category-count` | 40 | 类目召回数量 |
| `recommendation.recall.max-pool-size` | 120 | 合并后最大候选池 |
| `recommendation.popular.time-decay-factor` | 0.95 | 时间衰减因子 |
| `recommendation.diversity.max-consecutive-same-category` | 2 | 最大连续同类商品数 |
| `recommendation.gray.enabled` | false | 灰度开关 |
| `recommendation.gray.ratio` | 10 | 灰度流量比例 |
| `recommendation.rerank.enabled` | true | DeepFM 重排开关 |
| `ONLINE_LEARNING_ENABLED` | false | 增量学习开关 |
| `ONLINE_LEARNING_INTERVAL_MS` | 1800000 | 增量更新间隔（30 分钟） |

---

## 十一、系统优势分析

1. **两阶段设计**：召回层保证多样性（ItemCF + 热门 + 类目），排序层提升相关性（DeepFM），各司其职
2. **多渠道互补**：三类召回策略相互补充，有效缓解单一算法的冷启动问题
3. **时间衰减**：热门召回和 ItemCF 都引入时间衰减，新热商品有更多曝光机会
4. **灰度发布**：低风险验证新算法，支持对照组对比评估
5. **类目打散**：避免推荐结果同质化，提升用户体验
6. **优雅降级**：排序服务故障时自动降级回原始召回结果，不影响可用性
7. **禁止假数据**：所有环节均不生成虚假特征，一旦数据缺失则明确失败或跳过

---

## 十二、待优化方向

1. **商品特征实时同步**：当前 `buildFullItemFeatureMap` 每小时全量拉取一次，价格/销量变化存在延迟；建议改为变更事件驱动或设置更细粒度的 TTL
2. **在线学习特征质量**：`brand_id`/`price_bucket`/`sales_bucket` 降级为 0 时特征不完整；建议独立 Redis 缓存各字段并设置不同 TTL
3. **排序模型专属 Embedding**：当前模型复用合成数据训练时的 Embedding 维度，真实数据场景下可能欠拟合；建议基于真实数据重新训练并调参
4. **类目偏好特征空值**：`buildUserFeatureFromHistory` 中 `prefer_category` 和 `prefer_brand` 始终为空列表；建议从 `UserProfileService` 读取真实偏好
5. **批量接口调用优化**：`getProductInfoMap` 使用分页 API 获取商品信息（`pageSize=1000`），大批量时可能需要多次请求；已使用 `/batch` 接口缓解，可进一步考虑本地缓存热点商品

---

*文档维护：电商推荐系统开发组*
*最后更新：2026-03-21*
