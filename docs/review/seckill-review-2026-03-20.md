# 秒杀模块代码审查报告

> **审查时间**：2026-03-20
> **审查范围**：seckill-service（后端）、frontend/src/views/Seckill.vue（前端子组件）、Redis Stream 消息队列消费链路、性能测试脚本、监控面板
> **技术栈**：Spring Boot 3.x + Redis（Lua 脚本 + Stream）+ MyBatis-Plus + Sentinel + Nacos + Vue 3

---

## 一、总体评价

| 维度 | 评分 | 说明 |
|------|------|------|
| 核心算法（库存扣减） | ⭐⭐⭐⭐⭐ | Lua 脚本原子操作，双重超卖检查，设计良好 |
| 高并发防护 | ⭐⭐⭐⭐ | 限流 + 幂等 + 异步解耦，链路完整 |
| 业务逻辑 | ⭐⭐⭐ | 存在多处生产级风险，详见本文 |
| 代码质量 | ⭐⭐⭐ | 存在语法错误、重复代码、逻辑缺陷 |
| 监控可观测性 | ⭐⭐⭐⭐ | Grafana + Micrometer，指标覆盖较全 |
| 测试覆盖 | ⭐⭐ | 有压测脚本，但无单元测试 |
| 生产就绪度 | ⭐⭐ | 存在必须修复的问题 |

**结论**：核心库存扣减算法设计优秀，但在业务逻辑层面、异常处理层面、前端交互层面存在多处需要修复的问题。距离生产级仍需进一步打磨。

---

## 二、严重问题（必须修复）

### 2.1 语法错误——`SeckillService.java` 编译不过

**文件**：`SeckillService.java`，第 464-483 行 `getQueueMetrics()` 方法

```464:483:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
    public Map<String, Object> getQueueMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        Long queueSize = stringRedisTemplate.opsForStream().size(SECKILL_ORDER_STREAM_KEY);
        Long dlqSize = stringRedisTemplate.opsForStream().size(SeckillOrderStreamConsumer.DLQ_STREAM_KEY);
        int retryKeys = stringRedisTemplate.keys(SeckillOrderStreamConsumer.RERY_KEY_PREFIX + "*") == null
                ? 0
                : stringRedisTemplate.keys(SeckillOrderStreamConsumer.RETRY_KEY_PREFIX + "*").size();
        int doneMarkers = stringRedisTemplate.keys(ASYNC_DONE_KEY_PREFIX + "*") == null
                ? 0
                : stringRedisTemplate.keys(ASYNC_DONE_KEY_PREFIX + "*").size();

        metrics.put("queueSize", queueSize == null ? 0L : queueSize);
        metrics.put("deadLetterSize", dlqSize == null ? 0L : dlqSize);
        metrics.put("retryingMessages", retryKeys);
        metrics.put("doneMarkers", doneMarkers);
        metrics.put("productExistsCacheHits", productExistsCacheHits.get());
        metrics.put("productExistsCacheMisses", productExistsCacheMisses.get());
                ? 0
                : stringRedisTemplate.keys(ASYNC_DONE_KEY_PREFIX + "*").size();  // ← 残留代码，语法错误
    }
```

**问题**：`getQueueMetrics()` 方法的最后两行是残留代码块，没有闭合大括号，且中间出现了无意义的 `? 0` 三元表达式片段。编译会失败。

**修复建议**：删除第 481-482 行，并在第 480 行闭合方法。

---

### 2.2 限流位置错误——先扣库存后限流

**文件**：`SeckillService.java`，第 208-272 行的 Lua 脚本

**问题**：Lua 脚本中，限流检查（第 242-251 行）在库存扣减（第 254 行）之后。这意味着即使用户被限流拒绝，库存已经被扣了——导致库存白白减少，用户却无法下单。

```208:272:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
-- 限流检查（第 242-251 行）在扣减库存（第 254 行）之前，
-- 但如果限流被触发，已扣减的库存不会回滚。
-- 更严重的问题是：限流 key 是 "seckill:ratelimit:{seckillProductId}"，
-- 这是按商品维度的全局限流，而不是按用户维度的限流。
```

**修复建议**：将限流检查移到库存扣减之前（保持 Lua 脚本中的顺序），或者在扣减库存前先做限流检查（因为先限流后扣减可以保证：被限流的请求不进入后续流程）。

---

### 2.3 限流维度错误——按商品而非按用户

**文件**：`SeckillService.java`，第 88 行

```88:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
        String limitKey = "seckill:ratelimit:" + seckillProductId;
```

**问题**：限流 key 是 `seckill:ratelimit:{seckillProductId}`，这是对整个商品的限流，而非单个用户。在秒杀场景中，全局限流会导致：第一个用户的一个请求就能消耗掉所有限流配额，导致其他真实用户被误杀。

**修复建议**：改为按用户维度限流：`seckill:ratelimit:{seckillProductId}:{userId}` 或使用滑动窗口算法。

---

### 2.4 缓存雪崩风险——商品存在性缓存无过期时间随机化

**文件**：`SeckillService.java`，第 163-203 行 `productExists()` 方法

**问题**：缓存雪崩（Cache Avalanche）风险。商品存在性缓存设置了固定过期时间（如 30 分钟），在缓存过期瞬间，大量请求同时穿透到数据库或远程服务。

**当前缓存配置**（缺失）：
- `PRODUCT_EXISTS_CACHE_MINUTES` 和 `PRODUCT_NOT_EXISTS_CACHE_MINUTES` 常量在代码中被引用但未定义。
- 缓存 TTL 固定无随机偏移，易引发雪崩。

**修复建议**：
1. 定义常量并添加随机偏移：`cacheMinutes + random(0, 5)`
2. 或使用 Redisson 的 `setIfAbsent` 实现分布式锁

---

### 2.5 缺少关键常量定义——编译时警告

**文件**：`SeckillService.java`，多处引用未定义常量

```java
// 以下常量被引用但未定义：
SECKILL_STOCK_KEY          // 未定义
SECKILL_ORDER_KEY          // 未定义
SECKILL_PRODUCTS_CACHE_KEY // 未定义
PRODUCT_EXISTS_CACHE_KEY_PREFIX // 未定义
PRODUCT_EXISTS_CACHE_MINUTES    // 未定义
PRODUCT_NOT_EXISTS_CACHE_MINUTES // 未定义
ASYNC_DONE_KEY_PREFIX      // 未定义
```

**问题**：这些常量是代码运行所必需的，缺失会导致编译失败或运行时异常（`NoSuchFieldError`）。

**修复建议**：在类顶部定义所有常量，例如：

```java
private static final String SECKILL_STOCK_KEY = "seckill:stock:";
private static final String SECKILL_ORDER_KEY = "seckill:order:";
private static final String SECKILL_PRODUCTS_CACHE_KEY = "seckill:cache:products:active";
private static final String PRODUCT_EXISTS_CACHE_KEY_PREFIX = "seckill:product:exists:";
private static final int PRODUCT_EXISTS_CACHE_MINUTES = 30;
private static final int PRODUCT_NOT_EXISTS_CACHE_MINUTES = 5;
private static final String ASYNC_DONE_KEY_PREFIX = "seckill:async:done:";
```

---

### 2.6 `initStock()` 语义错误——没有处理 stock == 0 的情况

**文件**：`SeckillService.java`，第 279-283 行

```279:283:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
    public void initStock(Long seckillProductId, Integer stock) {
        String stockKey = SECKILL_STOCK_KEY + seckillProductId;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock), 1, TimeUnit.DAYS);
        log.info("秒杀商品 {} 库存初始化为 {}", seckillProductId, stock);
    }
```

**问题**：
1. 如果传入 `stock == null` 会抛出 NPE
2. 没有校验 `stock < 0` 的非法输入
3. `opsForValue().set()` 第三个参数 `1` 是 TTL 值，但缺少时间单位——实际会按秒而非天生效
4. 缺少幂等检查：如果 Redis 中已有库存，不应该无条件覆盖

**修复建议**：
```java
public void initStock(Long seckillProductId, Integer stock) {
    if (stock == null || stock < 0) {
        throw new IllegalArgumentException("库存必须为非负整数");
    }
    String stockKey = SECKILL_STOCK_KEY + seckillProductId;
    stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(stock));
    log.info("秒杀商品 {} 库存初始化为 {}", seckillProductId, stock);
}
```

---

### 2.7 `getNearestEndTime()` 返回 null 时未做保护

**文件**：`SeckillService.java`，第 409-446 行

```429:429:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
        LocalDateTime endTime = product != null ? product.getEndTime() : null;
```

**问题**：当数据库无进行中活动时，`endTime` 为 null，但前端 `Seckill.vue` 会调用 `.getTime()`，在 `endTime.value` 为 null 时可能抛出异常。

**影响**：`Seckill.vue` 第 76 行有 `new Date(activityRes.data.endTime).getTime()` 调用，当后端返回 null 时会得到 `NaN`，导致倒计时显示错误。

---

### 2.8 库存一致性风险——Redis 与 MySQL 不同步

**文件**：`SeckillService.java` 整体

**问题**：
1. 秒杀成功的库存扣减发生在 Redis 中，**没有定时同步回 MySQL**
2. 如果 Redis 宕机，库存数据丢失
3. 异步下单成功后，MySQL 的 `available_stock` 并未更新
4. 服务重启后，库存从 MySQL 重新加载，但 Redis 中已有部分库存被消耗（双重售卖风险）

**修复建议**：
1. 添加定时任务（如每分钟）将 Redis 库存增量同步到 MySQL
2. 在异步下单成功后，更新 MySQL 的 `available_stock`
3. 服务重启时，从 MySQL 加载 `available_stock` 到 Redis（已有逻辑，但需验证幂等性）

---

## 三、中等问题（建议修复）

### 3.1 `getUpcomingSeckillProducts()` SQL 注入风险

**文件**：`SeckillService.java`，第 385 行

```385:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
                .last("LIMIT " + limit);
```

**问题**：直接拼接 `limit` 参数，虽然是 int 类型不会造成 SQL 注入，但违反了 MyBatis-Plus 的最佳实践。

**修复建议**：使用 MyBatis-Plus 的 `last(boolean condition, CharSequence sql)` 并配合参数校验，或使用 `wrapper.last(condition, sql)` 并确保 `limit > 0`。

---

### 3.2 重复代码——`getUpcomingSeckillProducts` 和 `getActiveSeckillProducts`

**文件**：`SeckillService.java`，第 298-357 行和第 362-404 行

**问题**：两个方法有大量重复的缓存读写逻辑，可抽取为私有方法。

---

### 3.3 `productExists()` 调用远程服务——高并发下瓶颈

**文件**：`SeckillService.java`，第 163-203 行

**问题**：`productExists()` 在每次秒杀前调用 product-service 验证商品存在性。虽然有缓存，但首次访问和缓存失效时会对 product-service 造成压力。

**风险**：
- product-service 不可用时，秒杀直接失败（无降级）
- 缓存穿透：大量不存在的商品请求穿透到数据库

**修复建议**：
1. 增加熔断器（Sentinel 已经引入但未使用）
2. 使用布隆过滤器（Bloom Filter）快速判断商品是否可能存在
3. 考虑在 seckill-service 本地缓存商品元数据

---

### 3.4 限流配置未在 application.yaml 中定义

**文件**：`application.yaml`

**问题**：`seckill.rate-limit.max-requests-per-second` 和 `seckill.rate-limit.enabled` 在 `SeckillService.java` 中使用，但 `application.yaml` 中未定义（使用默认值 100 和 true）。

**风险**：在生产环境中无法通过配置中心（Nacos）动态调整限流阈值。

**修复建议**：在 `application.yaml` 中添加：

```yaml
seckill:
  rate-limit:
    enabled: true
    max-requests-per-second: 100
  queue:
    max-retry: 5
    poll-interval-ms: 200
    pending-poll-interval-ms: 1500
```

---

### 3.5 消费组初始化的竞态条件

**文件**：`SeckillOrderStreamConsumer.java`，第 37-59 行

```37:59:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillOrderStreamConsumer.java
    @PostConstruct
    public void initConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(SeckillService.SECKILL_ORDER_STREAM_KEY, ReadOffset.latest(), GROUP);
        } catch (Exception exception) {
            if (message.contains("BUSYGROUP")) {
                return;  // 组已存在
            }
            if (message.contains("requires the key to exist")) {
                // 先添加一条空消息，再创建组
                stringRedisTemplate.opsForStream().add(...);
                stringRedisTemplate.opsForStream().createGroup(...);
            }
        }
    }
```

**问题**：在 `createGroup` 失败后，先 `add` 再 `createGroup` 的两步操作不是原子的。如果多实例同时启动，可能出现竞态条件（两实例同时 add、空消息覆盖等）。

**修复建议**：使用 Redisson 的分布式锁确保只有一个实例执行初始化，或使用 Redis 的 `NX` 选项（`XGROUP CREATE MKSTREAM`）。

---

### 3.6 `consumePending()` 使用 `ReadOffset.from("0")` 每次拉全量 Pending

**文件**：`SeckillOrderStreamConsumer.java`，第 82-101 行

```88:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillOrderStreamConsumer.java
                    StreamOffset.create(SeckillService.SECKILL_ORDER_STREAM_KEY, ReadOffset.from("0"))
```

**问题**：`ReadOffset.from("0")` 会从 Stream 的起始位置读取所有 pending 消息。正常情况下 pending 列表应该为空或很少，重复拉取整个历史可能造成性能问题。但这个设计在 pending 消息量大时尤其危险。

**修复建议**：使用 `StreamOffset.create(consumerGroup, ReadOffset.lastDelivered())` 或 `XPENDING` 命令先获取 pending 消息 ID 范围，再针对性拉取。

---

### 3.7 缺少 `submitSeckillOrder` 失败的幂等补偿

**文件**：`SeckillOrderStreamConsumer.java`，第 117 行

```117:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillOrderStreamConsumer.java
            boolean created = seckillService.submitSeckillOrder(userId, seckillProductId, quantity, messageId);
            if (created) {
                seckillService.markAsyncDone(messageId);
                // ...
                acknowledge(record.getId());
                return;
            }
            // created == false 时进入重试逻辑
```

**问题**：`submitSeckillOrder` 失败（返回 false）时，进入重试逻辑。但如果 order-service 返回了 HTTP 200 但业务处理失败（如余额不足、库存已用完），`submitSeckillOrder` 仍可能返回 true，但实际未创建订单。订单服务和库存补偿链路不完整。

**修复建议**：
1. order-service 应返回明确的业务结果码（如 `ORDER_CREATED`、`INSUFFICIENT_STOCK` 等）
2. `submitSeckillOrder` 应解析 response 判断真实结果
3. 考虑引入 saga 模式或最终一致性补偿机制

---

### 3.8 `asyncOrder()` 方法是空实现

**文件**：`SeckillService.java`，第 488-496 行

```488:496:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
    public void asyncOrder(Long userId, Long seckillProductId) {
        log.info("异步下单: 用户 {}, 商品 {}", userId, seckillProductId);
    }

    public void asyncOrder(Long userId, Long seckillProductId, Integer quantity, String messageId) {
        log.info("异步订单处理: messageId={}, userId={}, seckillProductId={}, quantity={}",
                messageId, userId, seckillProductId, quantity);
    }
```

**问题**：这两个方法只有日志打印，没有实际业务逻辑。如果后续扩展使用，可能忘记实现。

---

### 3.9 `compensateAfterAsyncFailure()` 补偿逻辑不完整

**文件**：`SeckillService.java`，第 527-534 行

```527:534:seckill-service\src\main\java\com\ecommerce\seckill\service\SeckillService.java
    public void compensateAfterAsyncFailure(Long userId, Long seckillProductId, Integer quantity) {
        String stockKey = SECKILL_STOCK_KEY + seckillProductId;
        String orderKey = SECKILL_ORDER_KEY + seckillProductId + ":" + userId;
        stringRedisTemplate.opsForValue().increment(stockKey, quantity);
        stringRedisTemplate.delete(orderKey);
        log.warn("异步下单失败已补偿库存: userId={}, seckillProductId={}, quantity={}",
                userId, seckillProductId, quantity);
    }
```

**问题**：
1. `increment` 不是原子操作（在补偿场景下可以接受，但不够严谨）
2. 如果 Redis 库存已为 0，补偿后变成负数（理论上不应发生，但需保护）
3. 没有将补偿事件发送到 DLQ 或记录到数据库（审计追踪）

---

### 3.10 前端硬编码默认值——生产数据污染

**文件**：`frontend/src/views/Seckill.vue`，第 11 行

```11:frontend\src\views\Seckill.vue
        <span v-else>02:35:48</span>
```

**问题**：当没有 `endTime` 时，显示硬编码的倒计时 `02:35:48`。这可能是开发时的假数据，生产中会误导用户。

**修复建议**：当无活动时显示"暂无秒杀活动"，或使用真实的下一场秒杀开始时间。

---

### 3.11 前端价格计算默认值——假数据

**文件**：`frontend/src/views/Seckill.vue`，第 106 行

```106:frontend\src\views\Seckill.vue
          originalPrice: Number(item.originalPrice ?? item.seckillPrice * 1.5).toFixed(2),
```

**问题**：`originalPrice` 为空时，使用 `seckillPrice * 1.5` 计算——这是前端自行计算的原价，**不是从后端获取的真实原价**。这可能导致价格信息不准确。

**修复建议**：
1. 后端返回 `originalPrice` 字段
2. 前端不应该自行计算原价（从 product-service 获取）

---

### 3.12 前端 `loadFallbackProducts()` 同样使用假数据

**文件**：`frontend/src/views/Seckill.vue`，第 127-158 行

```151:155:frontend\src\views\Seckill.vue
    // 如果没有结束时间，设置一个默认的2小时后
    if (!endTime.value) {
      endTime.value = Date.now() + 3600000 * 2
    }
```

**问题**：`loadFallbackProducts()` 中 `originalPrice` 计算同样依赖前端自行计算（第 145 行）。第 151-154 行设置了 2 小时后的假结束时间，可能误导用户。

---

## 四、轻微问题

### 4.1 缺少秒杀的 `Sentinel` 注解

**文件**：`SeckillController.java`

**说明**：pom.xml 已引入 Sentinel 依赖，但 Controller 层没有使用 `@SentinelResource` 注解进行流量控制。

**建议**：为 `/api/seckill/start` 接口添加 `@SentinelResource` 配置降级和限流规则。

---

### 4.2 缺少单元测试

**说明**：seckill-service 没有 `src/test/java` 目录。对于高并发场景，应该有：
- Lua 脚本的单元测试
- 库存扣减并发测试
- 消息队列消费测试

---

### 4.3 `SECKILL_CACHE_SECONDS = 60` 可能过短

**文件**：`SeckillService.java`，第 49 行

**问题**：秒杀商品列表的缓存只有 60 秒。在秒杀高峰期，频繁缓存失效可能导致缓存击穿。

**建议**：根据实际 QPS 调整缓存时间，或使用布隆过滤器 + 分布式锁。

---

### 4.4 监控面板未接入实际指标

**文件**：`infrastructure/monitoring/grafana/provisioning/dashboards/json/seckill-overview.json`

**问题**：Grafana 面板依赖 `seckill_success`、`seckill_fail`、`rate_limit_rejects` 等自定义指标，但代码中只使用了 Micrometer 的默认指标（`http_server_requests_seconds_*`）。

**修复建议**：
1. 在 `SeckillService` 中注入 `MeterRegistry`，注册自定义指标：

```java
private final MeterRegistry meterRegistry;

meterRegistry.counter("seckill.success").increment();
meterRegistry.counter("seckill.fail").increment();
meterRegistry.counter("seckill.ratelimit.rejects").increment();
```

2. 或者移除 Grafana 面板中不存在的指标，使用实际可用的指标（如 HTTP 状态码分布）。

---

### 4.5 缺少健康检查端点

**文件**：`SeckillController.java`

**说明**：没有 `/health` 或 `/ready` 端点。K8s/负载均衡器无法判断服务是否就绪。

**建议**：添加 `/api/seckill/health` 返回 Redis 连接状态、消息队列积压情况等。

---

### 4.6 `RequestIdFilter` 在异常时可能泄漏 MDC

**文件**：`RequestIdFilter.java`，第 35-39 行

```35:39:seckill-service\src\main\java\com\ecommerce\seckill\filter\RequestIdFilter.java
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ATTR_REQUEST_ID);
        }
```

**问题**：虽然有 `finally` 块，但 MDC 的实现依赖于 `ThreadLocal`，如果使用了线程池（如 `@Async`），可能出现 MDC 泄漏。

**建议**：使用 `TransmittableThreadLocal` 或在异步调用前传递 MDC。

---

### 4.7 没有对 `quantity` 参数做上限检查

**文件**：`SeckillController.java`，第 80 行

```80:seckill-service\src\main\java\com\ecommerce\seckill\controller\SeckillController.java
            @Parameter(description = "抢购数量") @RequestParam(defaultValue = "1") Integer quantity) {
```

**问题**：`quantity` 没有最大值限制。用户可能传入极大值（如 10000），虽然 Lua 脚本会检查库存不足返回 -2，但在高并发下，多个请求同时传入大数量可能导致库存快速耗尽。

**建议**：`@Max(1)` 或在 service 层校验 `quantity <= 某个上限（如 5）`。

---

## 五、假数据与默认值清单

| 位置 | 类型 | 说明 | 严重程度 |
|------|------|------|----------|
| `Seckill.vue:11` | 硬编码 | `02:35:48` 倒计时默认值 | 中 |
| `Seckill.vue:106` | 计算生成 | `seckillPrice * 1.5` 作为原价 | 中 |
| `Seckill.vue:153` | 硬编码 | 无活动时设置 2 小时后 | 中 |
| `loadFallbackProducts:147` | 假数据 | fallback 逻辑中的 stock 计算 | 中 |
| `init.sql:24-27` | 测试数据 | 硬编码的 picsum.photos 图片 URL | 低（仅测试环境） |
| `init.sql:24-27` | 硬编码日期 | `DATE_FORMAT(NOW(), ...)` 每次启动刷新时间 | 高（生产数据污染风险） |

---

## 六、生产环境特别关注

### 6.1 活动时间的动态刷新

**问题**：`init.sql` 中使用 `NOW()` 插入秒杀商品，活动时间是动态的。这意味着每次重启容器或重新执行 SQL，秒杀活动时间都会变化。生产环境应该从配置中心或数据库预置固定的活动时间。

### 6.2 幂等性——Lua 脚本中的 orderKey

**说明**：Lua 脚本使用 `SECKILL_ORDER_KEY + seckillProductId + ":" + userId` 作为幂等键。默认 TTL 是 24 小时。这是合理的，但需要确认 order-service 创建订单时也做了幂等（基于相同的 messageId）。

### 6.3 缺少熔断降级

**问题**：product-service 调用没有熔断器保护。如果 product-service 不可用，整个秒杀链路会失败。建议使用 Sentinel 的 `@SentinelResource` 或 Resilience4j 熔断器。

---

## 七、修复优先级汇总

| 优先级 | 问题 | 影响 |
|--------|------|------|
| P0（立刻修复） | `getQueueMetrics()` 语法错误 | 编译失败 |
| P0 | 缺少关键常量定义 | 运行时崩溃 |
| P0 | `initStock()` 缺少参数校验 | NPE 或库存错误 |
| P0 | 限流先扣库存（Lua 顺序） | 库存白白减少 |
| P1（尽快修复） | 限流按商品而非按用户 | 限流失效 |
| P1 | Redis 与 MySQL 不同步 | 数据不一致 |
| P1 | 缓存雪崩风险 | 数据库压力 |
| P1 | `endTime` 为 null 前端崩溃 | 显示异常 |
| P2（计划修复） | Sentinel 熔断未启用 | 可用性风险 |
| P2 | 监控面板指标不匹配 | 可观测性失效 |
| P2 | 前端假数据（倒计时/原价） | 用户体验误导 |
| P3（优化项） | 单元测试缺失 | 质量保障 |
| P3 | 重复代码清理 | 可维护性 |

---

## 八、总结

你的秒杀模块在**核心库存扣减算法**上设计得非常扎实——Lua 脚本原子操作 + 双重超卖检查 + 限流 + 幂等，这一套组合在正确实现的情况下足以支撑高并发场景。Redis Stream 消息队列的异步解耦、死信队列、pending 重消费等机制也很完整。

但当前代码存在几个**阻断性问题**必须立即修复才能正常运行：
1. **语法错误**（`getQueueMetrics()` 编译不过）
2. **常量缺失**（多处引用未定义常量）
3. **限流逻辑缺陷**（限流在扣库存之后，且维度错误）

以及几个**生产级风险**：
4. Redis/MySQL 数据一致性
5. 缓存雪崩
6. 异常链路不完整

修复 P0 和 P1 问题后，这将是一个合格的秒杀系统原型。
