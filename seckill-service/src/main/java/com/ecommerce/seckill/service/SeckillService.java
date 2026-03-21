package com.ecommerce.seckill.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SeckillService {

    // ==================== Redis Key 常量 ====================
    private static final String SECKILL_STOCK_KEY = "seckill:stock:";
    private static final String SECKILL_ORDER_KEY = "seckill:order:";
    private static final String SECKILL_PRODUCTS_CACHE_KEY = "seckill:cache:products:active";
    private static final String SECKILL_UPCOMING_CACHE_KEY = "seckill:cache:products:upcoming";
    private static final String SECKILL_ENDTIME_CACHE_KEY = "seckill:cache:endtime";
    private static final String ASYNC_DONE_KEY_PREFIX = "seckill:async:done:";

    private static final long SECKILL_CACHE_SECONDS = 60; // 缓存60秒
    private static final long ORDER_TTL_SECONDS = 24 * 60 * 60; // 订单过期24小时

    public static final String SECKILL_ORDER_STREAM_KEY = "seckill:stream:orders";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = buildSeckillScript();

    // ==================== 依赖注入 ====================
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SeckillProductMapper seckillProductMapper;

    @Autowired
    private SeckillCacheService seckillCacheService;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${services.order.url:http://localhost:8003}")
    private String orderServiceUrl;

    @Value("${services.product.url:http://localhost:8002}")
    private String productServiceUrl;

    // 限流配置：每个用户每秒最大请求数
    @Value("${seckill.rate-limit.max-requests-per-second:100}")
    private int maxRequestsPerSecond;

    // 限流开关
    @Value("${seckill.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    // ==================== 统计指标 ====================
    private final AtomicLong productExistsCacheHits = new AtomicLong(0);
    private final AtomicLong productExistsCacheMisses = new AtomicLong(0);
    private final AtomicLong rateLimitRejects = new AtomicLong(0);
    private final AtomicLong seckillSuccessCount = new AtomicLong(0);
    private final AtomicLong seckillFailCount = new AtomicLong(0);

    // ==================== 秒杀核心逻辑 ====================

    /**
     * 尝试获取秒杀资格
     * 使用 Redis Lua 原子脚本：限流 + 幂等 + 库存检查 + 扣减库存
     */
    public boolean trySeckill(Long userId, Long seckillProductId, Integer quantity) {
        if (!seckillCacheService.productExists(seckillProductId)) {
            log.warn("商品不存在，拒绝秒杀请求: userId={}, seckillProductId={}", userId, seckillProductId);
            seckillFailCount.incrementAndGet();
            return false;
        }

        // 单用户单次最多购买10件，防止恶意刷单
        int buyQuantity = quantity == null || quantity <= 0 ? 1 : Math.min(quantity, 10);
        String stockKey = SECKILL_STOCK_KEY + seckillProductId;
        String orderKey = SECKILL_ORDER_KEY + seckillProductId + ":" + userId;
        // 限流维度：「商品 + 用户」双重维度，每个用户每秒最多 maxRequestsPerSecond 次
        String limitKey = "seckill:ratelimit:" + seckillProductId + ":" + userId;

        try {
            Long result;
            if (rateLimitEnabled) {
                result = stringRedisTemplate.execute(
                        SECKILL_SCRIPT,
                        Collections.singletonList(stockKey),
                        orderKey,
                        String.valueOf(buyQuantity),
                        String.valueOf(ORDER_TTL_SECONDS),
                        String.valueOf(System.currentTimeMillis()),
                        limitKey,
                        String.valueOf(maxRequestsPerSecond)
                );
            } else {
                result = stringRedisTemplate.execute(
                        SECKILL_SCRIPT,
                        Collections.singletonList(stockKey),
                        orderKey,
                        String.valueOf(buyQuantity),
                        String.valueOf(ORDER_TTL_SECONDS),
                        String.valueOf(System.currentTimeMillis()),
                        "",  // 空限流key
                        "0"  // 限流阈值0表示不限流
                );
            }

            if (result == null) {
                log.warn("秒杀脚本执行失败: userId={}, seckillProductId={}", userId, seckillProductId);
                seckillFailCount.incrementAndGet();
                return false;
            }

            if (result == 1L) {
                enqueueOrderEvent(userId, seckillProductId, buyQuantity);
                seckillSuccessCount.incrementAndGet();
                log.info("用户 {} 秒杀成功，商品 {}，数量 {}", userId, seckillProductId, buyQuantity);
                return true;
            }

            if (result == -1L) {
                log.warn("用户 {} 已参与过秒杀", userId);
            } else if (result == -2L) {
                log.warn("商品 {} 库存不足", seckillProductId);
            } else if (result == -3L) {
                log.warn("商品 {} 库存未初始化", seckillProductId);
            } else if (result == -4L) {
                log.warn("商品 {} 触发限流", seckillProductId);
                rateLimitRejects.incrementAndGet();
            } else {
                log.warn("秒杀脚本返回未知状态: {}", result);
            }

            seckillFailCount.incrementAndGet();
            return false;
        } catch (Exception exception) {
            log.error("秒杀处理异常: userId={}, seckillProductId={}", userId, seckillProductId, exception);
            seckillFailCount.incrementAndGet();
            return false;
        }
    }

    /**
     * 将秒杀事件写入 Redis Stream 消息队列
     */
    private void enqueueOrderEvent(Long userId, Long seckillProductId, Integer quantity) {
        Map<String, String> event = new HashMap<>();
        event.put("userId", String.valueOf(userId));
        event.put("seckillProductId", String.valueOf(seckillProductId));
        event.put("quantity", String.valueOf(quantity));
        event.put("eventTime", String.valueOf(System.currentTimeMillis()));
        RecordId recordId = stringRedisTemplate.opsForStream().add(SECKILL_ORDER_STREAM_KEY, event);
        log.debug("秒杀事件入流: recordId={}, userId={}, seckillProductId={}", recordId, userId, seckillProductId);
    }

    /**
     * 构建秒杀 Lua 脚本
     * 原子性保证：库存检查 → 限流 → 扣库存 → 记录订单
     */
    private static DefaultRedisScript<Long> buildSeckillScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                -- 秒杀Lua脚本：支持限流、幂等、超卖检查
                -- 返回值：1=成功, -1=重复下单, -2=库存不足, -3=库存未初始化, -4=限流拒绝
                -- 【重要】限流检查在扣库存之前，避免用户被限流但库存已被扣的问题

                local stockKey = KEYS[1]
                local orderKey = ARGV[1]
                local quantity = tonumber(ARGV[2])
                local ttl = tonumber(ARGV[3])
                local orderValue = ARGV[4]
                local limitKey = ARGV[5]          -- 限流key（商品+用户维度）
                local limitRate = tonumber(ARGV[6]) -- 限流阈值

                -- 1. 检查用户是否已下单（幂等性）
                if redis.call('EXISTS', orderKey) == 1 then
                    return -1
                end

                -- 2. 检查库存是否存在
                local stock = redis.call('GET', stockKey)
                if not stock then
                    return -3
                end

                -- 3. 转换为数值并检查库存
                stock = tonumber(stock)
                if not stock then
                    return -3
                end

                -- 4. 库存不足检查（防止超卖）【在限流之前先检查库存，避免浪费配额】
                if stock < quantity then
                    return -2
                end

                -- 5. 分布式限流检查（用户+商品双重维度）【在扣库存之前】
                if limitKey and limitKey ~= "" and limitRate and limitRate > 0 then
                    local current = redis.call('INCR', limitKey)
                    if current == 1 then
                        redis.call('EXPIRE', limitKey, 1) -- 1秒过期
                    end
                    if current > limitRate then
                        return -4 -- 限流拒绝
                    end
                end

                -- 6. 扣减库存（原子操作）【限流检查已通过，安全扣减】
                local newStock = redis.call('DECRBY', stockKey, quantity)

                -- 7. 双重检查：扣减后库存不能为负（极端并发保护）
                if newStock < 0 then
                    redis.call('INCRBY', stockKey, quantity) -- 回滚
                    return -2
                end

                -- 8. 记录用户订单（设置过期时间）
                redis.call('SETEX', orderKey, ttl, orderValue)

                -- 9. 记录秒杀成功次数（用于统计）
                local successTimeKey = stockKey .. ':success:time'
                redis.call('INCR', successTimeKey)
                redis.call('EXPIRE', successTimeKey, 86400)

                return 1
                """);
        return script;
    }

    // ==================== 库存管理 ====================

    /**
     * 初始化秒杀库存到 Redis
     */
    public void initStock(Long seckillProductId, Integer stock) {
        if (stock == null) {
            throw new IllegalArgumentException("库存不能为空");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("库存不能为负数");
        }
        String stockKey = SECKILL_STOCK_KEY + seckillProductId;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock), 1L, TimeUnit.DAYS);
        log.info("秒杀商品 {} 库存初始化为 {}", seckillProductId, stock);
    }

    /**
     * 获取秒杀商品库存
     */
    public Integer getStock(Long seckillProductId) {
        String stockKey = SECKILL_STOCK_KEY + seckillProductId;
        String stock = stringRedisTemplate.opsForValue().get(stockKey);
        return stock != null ? Integer.parseInt(stock) : 0;
    }

    // ==================== 商品列表查询 ====================

    /**
     * 获取进行中的秒杀活动商品列表（带缓存）
     */
    public List<SeckillProduct> getActiveSeckillProducts() {
        List<SeckillProduct> products = getCachedOrQuery(
                SECKILL_PRODUCTS_CACHE_KEY,
                wrapper -> {
                    LocalDateTime now = LocalDateTime.now();
                    wrapper.eq(SeckillProduct::getStatus, 1)
                            .le(SeckillProduct::getStartTime, now)
                            .ge(SeckillProduct::getEndTime, now)
                            .orderByAsc(SeckillProduct::getStartTime);
                    return seckillProductMapper.selectList(wrapper);
                },
                null  // 无自定义后处理，直接返回查询结果
        );

        if (products == null || products.isEmpty()) {
            return products;
        }

        // 库存单独从 Redis 读取（实时库存不缓存，保证数据新鲜）
        return products.stream().map(product -> {
            Integer redisStock = getStock(product.getId());
            if (redisStock != null && redisStock >= 0) {
                product.setAvailableStock(redisStock);
            } else if (redisStock == null) {
                // Redis 中无库存，从 DB 初始化（幂等保护）
                Integer dbStock = product.getAvailableStock();
                if (dbStock != null && dbStock >= 0) {
                    initStock(product.getId(), dbStock);
                }
            }
            return product;
        }).collect(Collectors.toList());
    }

    /**
     * 获取即将开始的秒杀活动商品列表（带缓存）
     */
    public List<SeckillProduct> getUpcomingSeckillProducts(int limit) {
        String cacheKey = SECKILL_UPCOMING_CACHE_KEY + limit;
        return getCachedOrQuery(
                cacheKey,
                wrapper -> {
                    LocalDateTime now = LocalDateTime.now();
                    wrapper.eq(SeckillProduct::getStatus, 1)
                            .gt(SeckillProduct::getStartTime, now)
                            .orderByAsc(SeckillProduct::getStartTime)
                            .last("LIMIT " + limit);
                    return seckillProductMapper.selectList(wrapper);
                },
                null
        );
    }

    /**
     * 通用缓存读写方法（Cache-Aside 模式）
     * 1. 先查缓存，命中则直接返回
     * 2. 缓存未命中则查数据库
     * 3. 结果写入缓存（TTL = SECKILL_CACHE_SECONDS）
     *
     * @param cacheKey      缓存 key
     * @param dbQuery       缓存未命中时的数据库查询
     * @param postProcessor 可选的查询后处理器（如更新库存），传入 null 则直接返回查询结果
     */
    private List<SeckillProduct> getCachedOrQuery(
            String cacheKey,
            java.util.function.Function<LambdaQueryWrapper<SeckillProduct>, List<SeckillProduct>> dbQuery,
            java.util.function.UnaryOperator<SeckillProduct> postProcessor) {

        // 1. 尝试从缓存读取
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                List<SeckillProduct> products = JSON.parseArray(cached, SeckillProduct.class);
                if (products != null) {
                    log.debug("从缓存获取商品列表: cacheKey={}, count={}", cacheKey, products.size());
                    if (postProcessor != null) {
                        return products.stream().map(postProcessor).collect(Collectors.toList());
                    }
                    return products;
                }
            } catch (Exception e) {
                log.warn("解析缓存数据失败: cacheKey={}, error={}", cacheKey, e.getMessage());
            }
        }

        // 2. 缓存未命中，查询数据库
        LambdaQueryWrapper<SeckillProduct> wrapper = new LambdaQueryWrapper<>();
        List<SeckillProduct> products = dbQuery.apply(wrapper);

        // 3. 写入缓存
        if (products != null && !products.isEmpty()) {
            try {
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        JSON.toJSONString(products),
                        SECKILL_CACHE_SECONDS,
                        TimeUnit.SECONDS
                );
                log.debug("缓存商品列表: cacheKey={}, count={}", cacheKey, products.size());
            } catch (Exception e) {
                log.warn("缓存商品列表失败: cacheKey={}, error={}", cacheKey, e.getMessage());
            }
        }

        if (postProcessor != null && products != null) {
            return products.stream().map(postProcessor).collect(Collectors.toList());
        }
        return products;
    }

    /**
     * 获取最近的秒杀活动结束时间（用于倒计时，带缓存）
     */
    public LocalDateTime getNearestEndTime() {
        String cached = stringRedisTemplate.opsForValue().get(SECKILL_ENDTIME_CACHE_KEY);
        if (cached != null) {
            try {
                return LocalDateTime.parse(cached);
            } catch (Exception e) {
                log.warn("解析缓存结束时间失败: {}", e.getMessage());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<SeckillProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SeckillProduct::getStatus, 1)
                .le(SeckillProduct::getStartTime, now)
                .ge(SeckillProduct::getEndTime, now)
                .orderByAsc(SeckillProduct::getEndTime)
                .last("LIMIT 1");

        SeckillProduct product = seckillProductMapper.selectOne(wrapper);
        LocalDateTime endTime = product != null ? product.getEndTime() : null;

        if (endTime != null) {
            try {
                stringRedisTemplate.opsForValue().set(
                    SECKILL_ENDTIME_CACHE_KEY,
                    endTime.toString(),
                    SECKILL_CACHE_SECONDS,
                    TimeUnit.SECONDS
                );
            } catch (Exception e) {
                log.warn("缓存结束时间失败: {}", e.getMessage());
            }
        }

        return endTime;
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除秒杀商品缓存
     */
    public void clearCache() {
        stringRedisTemplate.delete(SECKILL_PRODUCTS_CACHE_KEY);
        stringRedisTemplate.delete(SECKILL_ENDTIME_CACHE_KEY);
        stringRedisTemplate.delete(stringRedisTemplate.keys(SECKILL_UPCOMING_CACHE_KEY + "*"));
        log.info("秒杀商品缓存已清除");
    }

    // ==================== 队列监控 ====================

    public long getQueueSize() {
        Long size = stringRedisTemplate.opsForStream().size(SECKILL_ORDER_STREAM_KEY);
        return size != null ? size : 0L;
    }

    public Map<String, Object> getQueueMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        Long queueSize = stringRedisTemplate.opsForStream().size(SECKILL_ORDER_STREAM_KEY);
        Long dlqSize = stringRedisTemplate.opsForStream().size(SeckillOrderStreamConsumer.DLQ_STREAM_KEY);

        int retryKeys = 0;
        int doneMarkers = 0;
        try {
            var retryPattern = stringRedisTemplate.keys(SeckillOrderStreamConsumer.RETRY_KEY_PREFIX + "*");
            retryKeys = retryPattern != null ? retryPattern.size() : 0;

            var donePattern = stringRedisTemplate.keys(ASYNC_DONE_KEY_PREFIX + "*");
            doneMarkers = donePattern != null ? donePattern.size() : 0;
        } catch (Exception e) {
            log.warn("获取队列指标模式匹配失败: {}", e.getMessage());
        }

        metrics.put("queueSize", queueSize == null ? 0L : queueSize);
        metrics.put("deadLetterSize", dlqSize == null ? 0L : dlqSize);
        metrics.put("retryingMessages", retryKeys);
        metrics.put("doneMarkers", doneMarkers);
        metrics.put("productExistsCacheHits", productExistsCacheHits.get());
        metrics.put("productExistsCacheMisses", productExistsCacheMisses.get());
        // 布隆过滤器统计
        if (seckillCacheService != null) {
            metrics.put("bloomFilterHits", seckillCacheService.getBloomFilterHits());
            metrics.put("bloomFilterPasses", seckillCacheService.getBloomFilterPasses());
            metrics.put("bloomFilterHitRate",
                    seckillCacheService.getBloomFilterHits() + seckillCacheService.getBloomFilterPasses() > 0
                    ? String.format("%.2f%%", seckillCacheService.getBloomFilterPasses() * 100.0 /
                            (seckillCacheService.getBloomFilterHits() + seckillCacheService.getBloomFilterPasses()))
                    : "N/A");
        }
        metrics.put("rateLimitRejects", rateLimitRejects.get());
        metrics.put("seckillSuccessCount", seckillSuccessCount.get());
        metrics.put("seckillFailCount", seckillFailCount.get());
        return metrics;
    }

    // ==================== 异步下单 ====================

    public void asyncOrder(Long userId, Long seckillProductId) {
        log.info("异步下单: 用户 {}, 商品 {}", userId, seckillProductId);
    }

    public void asyncOrder(Long userId, Long seckillProductId, Integer quantity, String messageId) {
        log.info("异步订单处理: messageId={}, userId={}, seckillProductId={}, quantity={}",
                messageId, userId, seckillProductId, quantity);
    }

    public boolean submitSeckillOrder(Long userId, Long seckillProductId, Integer quantity, String messageId) {
        String url = UriComponentsBuilder.fromHttpUrl(orderServiceUrl)
                .path("/api/order/create/seckill")
                .toUriString();

        Map<String, Object> request = new HashMap<>();
        request.put("userId", userId);
        request.put("productId", seckillProductId);
        request.put("quantity", quantity);
        request.put("messageId", messageId);

        try {
            restTemplate.postForObject(url, request, Map.class);
            return true;
        } catch (Exception exception) {
            log.error("调用订单服务失败: messageId={}, userId={}, seckillProductId={}",
                    messageId, userId, seckillProductId, exception);
            return false;
        }
    }

    public boolean isAsyncDone(String messageId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(getAsyncDoneKey(messageId)));
    }

    public void markAsyncDone(String messageId) {
        stringRedisTemplate.opsForValue().set(getAsyncDoneKey(messageId), "1", 7, TimeUnit.DAYS);
    }

    /**
     * 异步下单失败后的库存补偿
     * 将 Redis 库存回滚，并删除用户的秒杀记录
     */
    public void compensateAfterAsyncFailure(Long userId, Long seckillProductId, Integer quantity) {
        String stockKey = SECKILL_STOCK_KEY + seckillProductId;
        String orderKey = SECKILL_ORDER_KEY + seckillProductId + ":" + userId;
        stringRedisTemplate.opsForValue().increment(stockKey, quantity);
        stringRedisTemplate.delete(orderKey);
        log.warn("异步下单失败已补偿库存: userId={}, seckillProductId={}, quantity={}",
                userId, seckillProductId, quantity);
    }

    private String getAsyncDoneKey(String messageId) {
        return ASYNC_DONE_KEY_PREFIX + messageId;
    }

    /**
     * Redis 连接健康检查
     */
    public String pingRedis() {
        try {
            String result = stringRedisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return result != null ? result : "PONG";
        } catch (Exception e) {
            log.warn("Redis Ping 失败: {}", e.getMessage());
            return "ERROR";
        }
    }
}
