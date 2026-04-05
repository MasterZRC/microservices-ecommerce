package com.ecommerce.admin.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("dashboard:stats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("product:detail", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("product:page", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("order:detail", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("order:page", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("seckill:detail", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("seckill:page", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("categories:all", defaultConfig.entryTtl(Duration.ofHours(1)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
