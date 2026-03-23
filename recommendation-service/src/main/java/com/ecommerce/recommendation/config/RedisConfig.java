package com.ecommerce.recommendation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 * 
 * 生产级配置包含：
 * - 高效的序列化器（减少网络传输）
 * - StringRedisTemplate（用于简单字符串操作）
 * - 连接池配置提示
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Key 使用 String 序列化，便于阅读和管理
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        
        // Value 使用 JSON 序列化，支持复杂对象
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        // 开启事务支持（可选，根据业务需求）
        template.setEnableTransactionSupport(false);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate 用于简单的字符串操作
     * 如计数器、分布式锁、简单缓存等
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
