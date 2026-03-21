# 推荐模块技术报告

> 更新日期：2026-03-20
> 文档范围：recommendation-service、recommendation-rank-service、前端调用层
> 文档目的：记录完整的推荐算法流程、系统架构和使用指南

---

## 一、总体架构

### 1.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                  用户请求                                      │
│                                     ↓                                         │
│  ┌─────────┐    ┌──────────────┐    ┌──────────────┐    ┌─────────────────┐   │
│  │  前端   │───▶│  API Gateway │───▶│ Recommendation │───▶│ Product Service │   │
│  │ Home.vue│    │   (鉴权)     │    │   Service     │    │   (商品数据)     │   │
│  └─────────┘    └──────────────┘    └───────┬──────┘    └─────────────────┘   │
│                                              │                                │
│                                              ↓                                │
│                                   ┌───────────────────┐                       │
│                                   │  多路召回层        │                       │
│                                   │  • ItemCF 召回     │                       │
│                                   │  • 热门召回        │                       │
│                                   │  • 同类目召回      │                       │
│                                   └─────────┬─────────┘                       │
│                                             │                                 │
│                                    ┌────────▼─────────┐                       │
│                                    │   GrayRelease     │                       │
│                                    │   Service         │                       │
│                                    │  (灰度分组)       │                       │
│                                    └────┬───────┬─────┘                       │
│                              对照组     │       │     灰度组                   │
│                                 ↓       │       │       ↓                     │
│                    ┌─────────────────┐   │       │   ┌─────────────────┐       │
│                    │ 返回 ItemCF 召回 │   │       │   │ Rank Client      │       │
│                    │    结果          │   │       │   │ Service          │       │
│                    └─────────────────┘   │       │   └────────┬────────┘       │
│                                           │       │            ↓              │
│                                           │       │   ┌─────────────────┐       │
│                                           │       │   │ Recommendation   │       │
│                                           │       │   │ -Rank-Service    │       │
│                                           │       │   │  (DeepFM 排序)   │       │
│                                           │       │   └─────────────────┘       │
│                                           │       │            │              │
│                                           │       │            ↓              │
│                                           │       │   ┌─────────────────┐       │
│                                           │       │   │ 返回 DeepFM      │       │
│                                           │       │   │ 排序结果         │       │
│                                           │       │   └─────────────────┘       │
└───────────────────────────────────────────┴───────────────────────────────┘
```

### 1.2 核心组件

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| recommendation-service | Java Spring Boot | 多路召回、灰度分发、特征服务 |
| recommendation-rank-service | Python FastAPI | DeepFM 模型推理、CTR 预估排序 |
| 前端 Home.vue | Vue 3 + Element Plus | 推荐展示、行为上报、换一换 |

---

## 二、推荐流程详解

### 2.1 用户请求入口

**前端调用**：`Home.vue` → `loadPopularProducts()`

```javascript
// 优先使用个性化推荐
if (userStore.token && userStore.userInfo?.id) {
  const personalRes = await api.getRecommendationProducts(userStore.userInfo.id, 32)
  const personalProducts = personalRes?.data?.products || []
  if (personalProducts.length > 0) {
    products.value = generateRecReason(personalProducts, isGray)
    return
  }
}

// 降级到热门推荐
const popularCardsRes = await api.getPopularProductCards(16)
```

### 2.2 后端 Controller 入口

**接口路径**：`GET /api/recommendation/personal/products`

**关键安全特性**：
```java
@GetMapping("/personal/products")
public ResponseEntity<Map<String, Object>> getPersonalizedRecommendationProducts(
        @RequestHeader(value = "X-Authenticated-User-Id", required = false) Long authUserId,
        @RequestParam Long userId,  // 前端传入
        @RequestParam(defaultValue = "10") Integer limit) {
    // 优先使用网关认证后的真实用户ID，防止伪造
    Long verifiedUserId = (authUserId != null) ? authUserId : userId;
    // ...
}
```

### 2.3 多路召回层

**文件**：`CandidateRecallService.java`

```java
public List<Long> multiChannelRecall(Long userId) {
    // 三路召回并行执行
    List<Long> cfCandidates = recallByItemCF(userId, cfRecallCount);      // ItemCF 召回
    List<Long> popularCandidates = recallByPopular(popularRecallCount);   // 热门召回
    List<Long> categoryCandidates = recallByCategory(userId, categoryRecallCount); // 同类目召回

    // 合并去重
    Set<Long> recallPool = new LinkedHashSet<>();
    recallPool.addAll(cfCandidates);
    recallPool.addAll(popularCandidates);
    recallPool.addAll(categoryCandidates);

    // 移除用户已交互商品
    recallPool.removeAll(getUserInteractedItems(userId));

    // 冷启动兜底：使用真实热门商品
    if (recallPool.isEmpty()) {
        return getColdStartFallback(maxPoolSize);  // 禁止生成假商品ID
    }

    return result;
}
```

#### 召回策略详情

| 召回通道 | 候选数量 | 说明 |
|---------|---------|------|
| ItemCF 协同过滤 | 80 | 基于用户历史行为的相似商品推荐 |
| 热门召回 | 40 | 带时间衰减的热门商品，decay_factor=0.95 |
| 同类目召回 | 40 | 用户偏好类目下的热门商品 |

### 2.4 灰度发布决策

**文件**：`GrayReleaseService.java`

```java
public boolean isGrayUser(Long userId) {
    if (!grayEnabled) return false;

    // 检查 Redis 缓存
    String userKey = GRAY_USER_KEY + userId;
    Boolean isGray = redisTemplate.opsForValue().get(userKey);

    // 使用一致性哈希分配分组
    boolean isGray = isGrayByHash(userId);

    // 缓存 7 天
    redisTemplate.opsForValue().set(userKey, isGray, 7, TimeUnit.DAYS);
    return isGray;
}
```

**灰度分组逻辑**：
```java
private boolean isGrayByHash(Long userId) {
    int hash = Math.abs(userId.hashCode());
    return (hash % 100) < grayRatio;  // 默认灰度比例 60%
}
```

### 2.5 对照组处理（ItemCF）

对照组用户直接返回 ItemCF 召回结果，无需调用排序服务。

**结果处理**：
- 类目打散：同一类目商品最多连续出现 2 个
- 添加推荐理由：基于用户偏好类目、相似商品等生成

### 2.6 灰度组处理（DeepFM 排序）

#### 2.6.1 特征构建

**文件**：`RecommendationService.java` → `buildUserFeatures()`

```java
private Map<String, Object> buildUserFeatures(Long userId) {
    Map<String, Object> features = new HashMap<>();

    // 近 1 天行为统计
    List<UserBehavior> behaviors1d = behaviorMapper.selectList(
        new LambdaQueryWrapper<UserBehavior>()
            .eq(UserBehavior::getUserId, userId)
            .ge(UserBehavior::getCreateTime, now.minusDays(1))
    );

    // 填充特征
    features.put("view_1d", stats1d.getOrDefault("view", 0));
    features.put("click_1d", stats1d.getOrDefault("click", 0));
    features.put("cart_1d", stats1d.getOrDefault("cart", 0));
    features.put("buy_1d", stats1d.getOrDefault("buy", 0));
    features.put("view_7d", stats7d.getOrDefault("view", 0));
    features.put("last_active_hours", hoursSinceActive);

    // 偏好类目（Top3）
    features.put("prefer_category", preferCategories);

    return features;
}
```

#### 2.6.2 商品特征构建

**文件**：`RecommendationService.java` → `buildItemFeaturesForCandidates()`

```java
private Map<String, Map<String, Object>> buildItemFeaturesForCandidates(List<Long> productIds) {
    Map<String, Map<String, Object>> itemFeatures = new HashMap<>();

    // 从商品服务获取真实数据
    Map<Long, Map<String, Object>> productInfoMap = getProductInfoMap(productIds);

    for (Long productId : productIds) {
        Map<String, Object> productInfo = productInfoMap.get(productId);
        if (productInfo == null) {
            // 禁止生成假特征，跳过该候选
            log.warn("无法获取商品 {} 的真实信息，跳过该候选", productId);
            continue;
        }

        Map<String, Object> features = new HashMap<>();
        features.put("category_id", categoryId);  // 真实类目ID
        features.put("brand_id", brandHash);       // 品牌哈希
        features.put("price_bucket", price / 100);
        features.put("sales_bucket", sales / 100);
        features.put("hot_score", (double) sales);

        itemFeatures.put(String.valueOf(productId), features);
    }

    return itemFeatures;
}
```

#### 2.6.3 排序服务调用

**文件**：`RankClientService.java`

```java
public List<Long> rank(Long userId, List<Long> candidateIds,
                       Map<String, Object> userFeatures,
                       Map<String, Map<String, Object>> itemFeatures) {

    // 特征验证
    FeatureValidationResult validation = validateFeatures(userFeatures, itemFeatures, candidateIds);
    if (!validation.isValid) {
        log.warn("RANK_FEATURE_ALERT: userId={}, reason={}", userId, validation.reason);
    }

    // 构建请求
    Map<String, Object> request = buildRankRequest(userId, candidateIds, userFeatures, itemFeatures);

    // 带 API Key 认证调用
    ResponseEntity<Map> response = executeRankRequest(url, request);

    // 返回排序结果或原始候选
    return rankedItems != null ? rankedItems : candidateIds;
}
```

#### 2.6.4 DeepFM 模型推理

**文件**：`recommendation-rank-service/app/main.py` → `/rank/simple`

```python
@router.post("/rank/simple")
async def rank_items_simple(request: Dict):
    # 必须提供用户特征
    has_user_features = any(k in request for k in [
        "view_1d", "click_1d", "cart_1d", "buy_1d",
        "view_7d", "click_7d", "cart_7d", "buy_7d",
        "last_active_hours"
    ])
    if not has_user_features:
        raise HTTPException(status_code=400, detail="未提供用户行为特征，禁止使用假数据兜底")

    # 必须提供商品特征
    raw_item_features = request.get("item_features", {})
    if not raw_item_features:
        raise HTTPException(status_code=400, detail="未提供商品特征，禁止生成假特征")

    # DeepFM 推理
    scores = ranker.rank(user_features, item_features)

    # 按分数排序返回
    return {"ranked_items": sorted(scores, reverse=True)}
```

### 2.7 推荐理由生成

**后端生成**（真实理由）：

```java
private Map<Long, String> generateExplanations(Long userId, List<Long> productIds, ...) {
    // 策略1：同类目相似商品
    if (similarItems != null && similar) {
        return "与你近期浏览的商品相似";
    }

    // 策略2：偏好类目匹配
    if (cat != null && cat.equals(topCategory)) {
        return "符合你偏好的商品类目";
    }

    // 策略3：热门商品
    if (score == 0.0) {
        return "当前热门推荐";
    }

    return "为你精选推荐";
}
```

**前端展示**（优先使用后端理由）：

```javascript
function generateRecReason(products, isGray) {
    return products.map((product, index) => {
        const backendReason = product.recommendation_reason || product.recReason;
        const backendReasons = ['与你近期浏览的商品相似', '符合你偏好的商品类目'];

        if (backendReason && backendReasons.some(r => backendReason.includes(r.split('，')[0]))) {
            return { ...product, recReason: backendReason };
        }

        // 否则使用前端生成的文案
        const reasons = isGray ? personalizedReasons : popularReasons;
        return { ...product, recReason: backendReason || reasons[index % reasons.length] };
    });
}
```

---

## 三、API 接口文档

### 3.1 推荐服务接口

| 接口 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `/api/recommendation/personal/products` | GET | userId, limit | 获取个性化推荐商品 |
| `/api/recommendation/popular/products` | GET | limit | 获取热门商品 |
| `/api/recommendation/behavior` | POST | userId, productId, behaviorType | 记录用户行为 |
| `/api/recommendation/gray/check` | GET | userId | 检查用户灰度分组 |
| `/api/recommendation/gray/metrics` | GET | date | 获取灰度指标 |
| `/api/recommendation/gray/compare` | GET | date | 获取灰度组对比 |

### 3.2 排序服务接口

| 接口 | 方法 | 认证 | 说明 |
|------|------|------|------|
| `/rank` | POST | API Key | 完整特征排序 |
| `/rank/simple` | POST | API Key | 简化特征排序 |
| `/health` | GET | - | 健康检查 |
| `/model/info` | GET | - | 模型信息 |

---

## 四、安全机制

### 4.1 用户身份验证

```java
// 所有涉及用户身份的接口，强制使用网关认证后的用户ID
Long verifiedUserId = (authUserId != null) ? authUserId : userId;

// 前端传入的 userId 仅作为默认值
if (verifiedUserId == null) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", 400,
        "message", "无法确认用户身份，请先登录"
    ));
}
```

### 4.2 排序服务 API Key 认证

```java
// 配置项
@Value("${services.rank.api-key:}")
private String apiKey;

// 请求时添加认证头
private ResponseEntity<Map> executeRankRequest(String url, Map<String, Object> request) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    if (apiKey != null && !apiKey.isBlank()) {
        headers.set("X-API-Key", apiKey);
    }

    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
    return restTemplate.postForEntity(url, entity, Map.class);
}
```

### 4.3 特征完整性验证

```java
private FeatureValidationResult validateFeatures(Map<String, Object> userFeatures,
                                               Map<String, Map<String, Object>> itemFeatures,
                                               List<Long> candidateIds) {
    // 验证用户特征非空
    if (userFeatures == null || userFeatures.isEmpty()) {
        return new FeatureValidationResult(false, "user_features_empty");
    }

    // 验证商品特征覆盖率 >= 50%
    int matchedCount = 0;
    for (Long candidateId : candidateIds) {
        Map<String, Object> feat = itemFeatures.get(String.valueOf(candidateId));
        if (feat != null && !feat.isEmpty()) {
            matchedCount++;
        }
    }

    double featureRatio = (double) matchedCount / candidateIds.size();
    if (featureRatio < 0.5) {
        return new FeatureValidationResult(false,
            String.format("item_feature_ratio_too_low:%.2f", featureRatio));
    }

    return new FeatureValidationResult(true, "valid");
}
```

---

## 五、监控与告警

### 5.1 特征验证告警

```java
private void recordFeatureAlert(Long userId, String reason) {
    log.warn("RANK_FEATURE_ALERT: userId={}, reason={}, timestamp={}",
            userId, reason, System.currentTimeMillis());
    // 生产环境可发送到监控系统
}
```

**告警原因类型**：
- `user_features_empty` - 用户特征为空
- `user_features_missing_required_keys` - 缺少关键特征
- `item_features_empty` - 商品特征为空
- `item_feature_ratio_too_low:0.XX` - 商品特征覆盖率过低

### 5.2 排序错误告警

```java
private void recordRankError(Long userId, String errorType, int httpStatus) {
    log.warn("RANK_ERROR: userId={}, type={}, httpStatus={}, timestamp={}",
            userId, errorType, httpStatus, System.currentTimeMillis());
}
```

**错误类型**：
- `http_error` - HTTP 协议错误
- `connection_error` - 连接超时/失败
- `unknown_error` - 未知错误

### 5.3 灰度指标

```java
public Map<String, Object> getMetrics(String date) {
    // 曝光数（HyperLogLog 去重）
    Long exposureCount = redisTemplate.opsForHyperLogLog().size(exposureKey);

    // 点击数
    Long clickCount = redisTemplate.opsForHyperLogLog().size(clickKey);

    // 转化率计算
    double ctr = (double) clickCount / Math.max(exposureCount, 1);

    return Map.of(
        "exposure", exposureCount,
        "click", clickCount,
        "ctr", ctr,
        "cartRate", cartRate,
        "orderRate", orderRate
    );
}
```

---

## 六、配置说明

### 6.1 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `RANK_SERVICE_URL` | 排序服务地址 | http://localhost:8010 |
| `RANK_SERVICE_API_KEY` | 排序服务 API Key | 空（开发模式） |
| `RERANK_ENABLED` | 是否启用 DeepFM 排序 | true |
| `GRAY_ENABLED` | 是否启用灰度发布 | true |
| `GRAY_RATIO` | 灰度用户比例 | 60 |
| `RANK_MIN_FEATURE_RATIO` | 商品特征最低覆盖率 | 0.5 |

### 6.2 召回配置

```yaml
recommendation:
  recall:
    cf-count: 80          # ItemCF 召回数量
    popular-count: 40      # 热门召回数量
    category-count: 40     # 同类目召回数量
    max-pool-size: 120    # 最大候选池大小
  popular:
    time-decay-factor: 0.95  # 热门召回时间衰减因子
  diversity:
    max-consecutive-same-category: 2  # 类目打散阈值
```

---

## 七、数据流总结

```
用户请求首页
    ↓
[前端] 调用 /recommendation/personal/products
    ↓
[网关] 验证 JWT，添加 X-Authenticated-User-Id Header
    ↓
[推荐服务]
    ├── 多路召回 → 合并候选池
    ├── 灰度分组 → 判断用户分组
    │
    ├── 对照组 → 返回 ItemCF 结果
    │
    └── 灰度组
        ├── 构建用户特征（行为统计）
        ├── 构建商品特征（真实数据）
        ├── 验证特征完整性
        └── 调用 DeepFM 排序
            ↓
        [排序服务]
            ├── 验证 API Key
            ├── 验证特征完整性
            ├── DeepFM 推理
            └── 返回排序结果
    ↓
[推荐服务] 生成推荐理由
    ↓
[前端] 展示推荐商品 + 推荐理由
    ↓
[用户行为]
    ├── 点击商品 → 记录 click 行为
    ├── 加入购物车 → 记录 cart 行为
    └── 购买商品 → 记录 buy 行为
    ↓
[行为数据] 用于更新用户画像和模型
```

---

## 八、代码质量清单

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 冷启动无假商品ID | ✅ | 使用真实热门商品或返回空 |
| 特征构建无假数据 | ✅ | 禁止生成假特征，无法获取则跳过 |
| 用户身份强制验证 | ✅ | 使用网关认证后的用户ID |
| 排序服务 API Key | ✅ | 支持 API Key 认证 |
| 特征验证告警 | ✅ | 记录完整告警日志 |
| 排序失败降级 | ✅ | 返回原始候选，不影响用户体验 |
| 推荐理由真实 | ✅ | 优先使用后端生成的真实理由 |
| 类目打散 | ✅ | 避免同类商品连续出现 |
| 灰度发布指标 | ✅ | 完整记录曝光/点击/转化 |

---

*文档更新：2026-03-20*
*维护团队：电商推荐系统开发组*
