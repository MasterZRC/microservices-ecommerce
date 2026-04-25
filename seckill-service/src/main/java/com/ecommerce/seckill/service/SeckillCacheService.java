package com.ecommerce.seckill.service;

import com.ecommerce.seckill.config.BloomFilterConfig;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存管理服务
 * 职责：
 * 1. 商品存在性判断（布隆过滤器 + 多级缓存 + 雪崩防护）
 * 2. 提供带随机偏移的缓存TTL，防止缓存雪崩
 */
@Slf4j
@Service
public class SeckillCacheService {

    private static final String PRODUCT_EXISTS_CACHE_KEY_PREFIX = "seckill:product:exists:";
    private static final String SECKILL_STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String SECKILL_PRODUCT_CACHE_KEY = "seckill:cache:products:active";
    private static final int PRODUCT_EXISTS_CACHE_MINUTES = 30;   // 基础TTL
    private static final int PRODUCT_NOT_EXISTS_CACHE_MINUTES = 5; // 基础TTL
    private static final int CACHE_TTL_JITTER_MAX_MINUTES = 5;    // 随机偏移上限

    private final StringRedisTemplate stringRedisTemplate;
    private final BloomFilterConfig bloomFilterConfig;
    private final SeckillProductMapper seckillProductMapper;

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong bloomFilterHits = new AtomicLong(0);  // 布隆过滤器命中（可能存在）
    private final AtomicLong bloomFilterPasses = new AtomicLong(0); // 布隆过滤器通过（一定不存在，直接拒绝）

    public SeckillCacheService(StringRedisTemplate stringRedisTemplate,
                               BloomFilterConfig bloomFilterConfig,
                               SeckillProductMapper seckillProductMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.bloomFilterConfig = bloomFilterConfig;
        this.seckillProductMapper = seckillProductMapper;
    }

    @PostConstruct
    public void initBloomFilter() {
        // 服务启动时从数据库加载所有秒杀商品ID到布隆过滤器
        try {
            LocalDateTime now = LocalDateTime.now();
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ecommerce.seckill.entity.SeckillProduct>();
            wrapper.select(com.ecommerce.seckill.entity.SeckillProduct::getId);
            var products = seckillProductMapper.selectList(wrapper);
            var ids = products.stream()
                    .map(com.ecommerce.seckill.entity.SeckillProduct::getId)
                    .toList();
            bloomFilterConfig.addProductIds(ids);
            log.info("布隆过滤器初始化完成，加载 {} 个商品ID", ids.size());
        } catch (Exception e) {
            log.warn("布隆过滤器初始化失败（非致命，继续运行）: {}", e.getMessage());
        }
    }

    /**
     * 判断秒杀商品是否存在
     * 三级查询：布隆过滤器 → Redis缓存 → 远程服务
     *
     * 雪崩防护策略：
     * 1. 布隆过滤器快速过滤不存在的请求（零穿透）
     * 2. 缓存TTL添加随机偏移（±5分钟），避免同时失效
     * 3. 缓存结果区分「存在」和「不存在」的TTL（30分钟 vs 5分钟）
     */
    public boolean productExists(Long seckillProductId) {
        if (seckillProductId == null) {
            return false;
        }

        // 第一层：布隆过滤器（毫秒级，极低误判率）
        if (bloomFilterConfig != null && !bloomFilterConfig.mightExist(seckillProductId)) {
            bloomFilterPasses.incrementAndGet();
            log.debug("布隆过滤器拦截不存在商品: {}", seckillProductId);
            return false;
        }
        if (bloomFilterConfig != null) {
            bloomFilterHits.incrementAndGet();
        }

        // 第二层：Redis缓存
        String cacheKey = PRODUCT_EXISTS_CACHE_KEY_PREFIX + seckillProductId;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if ("1".equals(cached)) {
            cacheHits.incrementAndGet();
            return true;
        }
        if ("0".equals(cached)) {
            cacheHits.incrementAndGet();
            return false;
        }
        cacheMisses.incrementAndGet();

        // 第三层：直接从本地数据库查询秒杀商品是否存在
        try {
            var product = seckillProductMapper.selectById(seckillProductId);
            if (product != null) {
                setExistsCache(cacheKey);
                return true;
            } else {
                setNotExistsCache(cacheKey);
                return false;
            }
        } catch (Exception exception) {
            log.error("查询秒杀商品是否存在失败: seckillProductId={}", seckillProductId, exception);
            return false;
        }
    }

    /**
     * 设置「商品存在」缓存，带随机TTL偏移防止雪崩
     */
    private void setExistsCache(String cacheKey) {
        int jitter = ThreadLocalRandom.current().nextInt(0, CACHE_TTL_JITTER_MAX_MINUTES + 1);
        int ttlMinutes = PRODUCT_EXISTS_CACHE_MINUTES + jitter;
        stringRedisTemplate.opsForValue().set(cacheKey, "1", ttlMinutes, TimeUnit.MINUTES);
        log.debug("设置商品存在缓存: key={}, ttl={}分钟", cacheKey, ttlMinutes);
    }

    /**
     * 设置「商品不存在」缓存，带随机TTL偏移防止雪崩
     * 不存在商品的缓存时间较短（5分钟），以便快速发现新增商品
     */
    private void setNotExistsCache(String cacheKey) {
        int jitter = ThreadLocalRandom.current().nextInt(0, CACHE_TTL_JITTER_MAX_MINUTES + 1);
        int ttlMinutes = PRODUCT_NOT_EXISTS_CACHE_MINUTES + jitter;
        stringRedisTemplate.opsForValue().set(cacheKey, "0", ttlMinutes, TimeUnit.MINUTES);
        log.debug("设置商品不存在缓存: key={}, ttl={}分钟", cacheKey, ttlMinutes);
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public long getBloomFilterHits() {
        return bloomFilterHits.get();
    }

    public long getBloomFilterPasses() {
        return bloomFilterPasses.get();
    }

    public void initStock(Long seckillProductId, Integer stock) {
        if (stock == null) {
            throw new IllegalArgumentException("库存不能为空");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("库存不能为负数");
        }
        String stockKey = SECKILL_STOCK_KEY_PREFIX + seckillProductId;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock), 1L, TimeUnit.DAYS);
        log.info("秒杀商品 {} 库存初始化为 {}", seckillProductId, stock);
    }

    public void clearProductCache() {
        stringRedisTemplate.delete(SECKILL_PRODUCT_CACHE_KEY);
        log.info("秒杀商品列表缓存已清除");
    }
}
