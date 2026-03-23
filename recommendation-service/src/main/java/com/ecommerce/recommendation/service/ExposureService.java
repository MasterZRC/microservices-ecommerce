package com.ecommerce.recommendation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 曝光埋点服务
 * 负责记录商品曝光日志，支持曝光负采样查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExposureService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final String REDIS_EXPOSURE_COUNT_KEY = "exposure:count:{}";
    private static final String REDIS_UV_KEY = "exposure:uv:{}";
    private static final String REDIS_DAILY_EXPOSURE_KEY = "exposure:daily:{}:{}";

    /**
     * 记录商品曝光
     *
     * @param userId       用户ID
     * @param productId    商品ID
     * @param position     推荐位排名（从1开始）
     * @param recommendType 推荐来源：deepfm/cf/popular
     */
    public void recordExposure(Long userId, Long productId, Integer position, String recommendType) {
        if (userId == null || productId == null) {
            log.warn("曝光记录失败：userId 或 productId 为空");
            return;
        }

        if (recommendType == null) {
            recommendType = "deepfm";
        }
        if (position == null) {
            position = 0;
        }

        try {
            // 写入 MySQL 曝光日志表
            String sql = """
                INSERT INTO product_exposure (user_id, product_id, position, recommend_type, create_time)
                VALUES (?, ?, ?, ?, NOW())
                """;
            jdbcTemplate.update(sql, userId, productId, position, recommendType);

            // 更新 Redis 曝光计数器
            updateExposureCounters(userId, productId, recommendType);

            log.debug("曝光记录成功: userId={}, productId={}, position={}, type={}",
                    userId, productId, position, recommendType);

        } catch (Exception e) {
            log.error("曝光记录失败: userId={}, productId={}, error={}",
                    userId, productId, e.getMessage());
        }
    }

    /**
     * 批量记录曝光（用于推荐结果返回时一次性记录多个商品）
     *
     * @param userId         用户ID
     * @param productIds     商品ID列表（按推荐顺序排列）
     * @param recommendType  推荐来源
     */
    public void recordExposures(Long userId, List<Long> productIds, String recommendType) {
        if (userId == null || productIds == null || productIds.isEmpty()) {
            return;
        }

        if (recommendType == null) {
            recommendType = "deepfm";
        }

        try {
            // 批量写入 MySQL
            String sql = """
                INSERT INTO product_exposure (user_id, product_id, position, recommend_type, create_time)
                VALUES (?, ?, ?, ?, NOW())
                """;
            List<Object[]> batchArgs = new ArrayList<>();
            for (int i = 0; i < productIds.size(); i++) {
                batchArgs.add(new Object[]{userId, productIds.get(i), i + 1, recommendType});
            }
            jdbcTemplate.batchUpdate(sql, batchArgs);

            // 批量更新 Redis 计数器
            for (int i = 0; i < productIds.size(); i++) {
                updateExposureCounters(userId, productIds.get(i), recommendType);
            }

            log.info("批量曝光记录成功: userId={}, count={}, type={}",
                    userId, productIds.size(), recommendType);

        } catch (Exception e) {
            log.error("批量曝光记录失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 查询用户曝光但未点击的商品（用于负采样）
     *
     * @param userId       用户ID
     * @param excludeItems 需要排除的商品ID集合
     * @param limit        返回数量上限
     * @return 曝光但未交互的商品ID列表
     */
    public List<Long> getExposureNegativeSamples(Long userId, Set<Long> excludeItems, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }

        try {
            // 查询用户曝光但未点击/购买/加购的商品
            String sql = """
                SELECT DISTINCT pe.product_id
                FROM product_exposure pe
                LEFT JOIN user_behavior ub
                    ON ub.user_id = pe.user_id
                    AND ub.product_id = pe.product_id
                    AND ub.behavior_type IN ('click', 'buy', 'cart', 'favorite')
                WHERE pe.user_id = ?
                  AND ub.id IS NULL
                  AND pe.create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                ORDER BY pe.create_time DESC
                LIMIT ?
                """;

            List<Long> result = jdbcTemplate.queryForList(sql, Long.class, userId, limit);

            // 排除指定商品
            if (excludeItems != null && !excludeItems.isEmpty()) {
                result.removeIf(excludeItems::contains);
            }

            return result;

        } catch (Exception e) {
            log.error("查询曝光负样本失败: userId={}, error={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取商品的曝光统计
     *
     * @param productId 商品ID
     * @param days      统计天数
     * @return 曝光次数
     */
    public long getProductExposureCount(Long productId, int days) {
        if (productId == null) {
            return 0;
        }

        try {
            // 先尝试从 Redis 获取
            String redisKey = String.format(REDIS_EXPOSURE_COUNT_KEY, productId);
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                return Long.parseLong(cached);
            }

            // 从 MySQL 查询
            String sql = """
                SELECT COUNT(*) FROM product_exposure
                WHERE product_id = ?
                  AND create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                """;
            Long count = jdbcTemplate.queryForObject(sql, Long.class, productId, days);

            // 缓存到 Redis（10分钟过期）
            if (count != null) {
                redisTemplate.opsForValue().set(redisKey, String.valueOf(count), 10, TimeUnit.MINUTES);
            }

            return count != null ? count : 0;

        } catch (Exception e) {
            log.error("获取商品曝光统计失败: productId={}, error={}", productId, e.getMessage());
            return 0;
        }
    }

    /**
     * 获取用户的曝光次数
     *
     * @param userId 用户ID
     * @param days   统计天数
     * @return 曝光次数
     */
    public long getUserExposureCount(Long userId, int days) {
        if (userId == null) {
            return 0;
        }

        try {
            String sql = """
                SELECT COUNT(*) FROM product_exposure
                WHERE user_id = ?
                  AND create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                """;
            Long count = jdbcTemplate.queryForObject(sql, Long.class, userId, days);
            return count != null ? count : 0;

        } catch (Exception e) {
            log.error("获取用户曝光统计失败: userId={}, error={}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 更新 Redis 曝光计数器
     */
    private void updateExposureCounters(Long userId, Long productId, String recommendType) {
        try {
            // 商品曝光计数器
            String countKey = String.format(REDIS_EXPOSURE_COUNT_KEY, productId);
            redisTemplate.opsForValue().increment(countKey);

            // 每日曝光计数器
            java.time.LocalDate today = java.time.LocalDate.now();
            String dailyKey = String.format(REDIS_DAILY_EXPOSURE_KEY, productId, today);
            redisTemplate.opsForValue().increment(dailyKey);
            redisTemplate.expire(dailyKey, 8, TimeUnit.DAYS);

        } catch (Exception e) {
            log.warn("Redis 计数器更新失败: userId={}, productId={}, error={}",
                    userId, productId, e.getMessage());
        }
    }

    /**
     * 获取曝光服务的健康状态
     */
    public boolean isHealthy() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.error("ExposureService 健康检查失败: {}", e.getMessage());
            return false;
        }
    }
}
