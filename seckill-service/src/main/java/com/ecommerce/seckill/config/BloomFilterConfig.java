package com.ecommerce.seckill.config;

import com.google.common.hash.Funnels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

/**
 * 布隆过滤器配置
 * 用于快速判断秒杀商品ID是否可能存在，避免缓存穿透击穿数据库
 */
@Configuration
public class BloomFilterConfig {

    private static final String BLOOM_FILTER_KEY = "seckill:bloom:productids";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 注入 RedisConnectionFactory 以便自行管理连接
     * 使用 Google Guava 的 BloomFilter 实现（线程安全）
     */
    private com.google.common.hash.BloomFilter<Long> bloomFilter;

    @Value("${bloomfilter.expected-insertions:10000}")
    private int expectedInsertions;

    @Value("${bloomfilter.fpp:0.01}")
    private double fpp;

    public BloomFilterConfig(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    public void init() {
        this.bloomFilter = com.google.common.hash.BloomFilter.create(
                Funnels.longFunnel(),
                expectedInsertions,
                fpp
        );
    }

    /**
     * 添加商品ID到布隆过滤器（秒杀商品上架时调用）
     */
    public void addProductId(Long productId) {
        if (productId != null) {
            bloomFilter.put(productId);
        }
    }

    /**
     * 批量添加商品ID到布隆过滤器（服务启动时从数据库加载）
     */
    public void addProductIds(Iterable<Long> productIds) {
        for (Long id : productIds) {
            if (id != null) {
                bloomFilter.put(id);
            }
        }
    }

    /**
     * 判断商品ID是否可能存在
     * @return true = 可能存在（需进一步查数据库确认）；false = 一定不存在
     */
    public boolean mightExist(Long productId) {
        if (productId == null) {
            return false;
        }
        return bloomFilter.mightContain(productId);
    }
}
