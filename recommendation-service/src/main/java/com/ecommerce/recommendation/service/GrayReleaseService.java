package com.ecommerce.recommendation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 灰度发布服务
 * 控制 DeepFM 重排的灰度流量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrayReleaseService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${recommendation.gray.enabled:false}")
    private boolean grayEnabled;

    @Value("${recommendation.gray.ratio:10}")
    private int grayRatio;

    private static final String GRAY_USER_KEY = "recommendation:gray:users:";
    private static final String METRICS_KEY = "recommendation:metrics:";

    /**
     * 判断用户是否在灰度组（使用 DeepFM 重排）
     */
    public boolean isGrayUser(Long userId) {
        if (!grayEnabled) {
            return false;
        }

        if (userId == null) {
            return false;
        }

        // 检查 Redis 中是否已有记录
        String userKey = GRAY_USER_KEY + userId;
        Boolean cached = (Boolean) redisTemplate.opsForValue().get(userKey);

        if (cached != null) {
            return Boolean.TRUE.equals(cached);
        }

        // 使用一致性哈希分配分组（保证同一用户始终分到同一组）
        boolean isGray = isGrayByHash(userId);

        // 缓存用户分组结果（7天过期）
        redisTemplate.opsForValue().set(userKey, isGray, 7, TimeUnit.DAYS);

        // 记录分组日志
        log.info("用户分组: userId={}, isGray={}, ratio={}%", userId, isGray, grayRatio);

        return isGray;
    }

    /**
     * 使用哈希算法确定灰度分组
     * 使用简单的取模方式，基于用户ID的哈希值
     */
    private boolean isGrayByHash(Long userId) {
        int hash = Math.abs(userId.hashCode());
        return (hash % 100) < grayRatio;
    }

    /**
     * 记录推荐展示埋点
     */
    public void recordExposure(Long userId, String algorithm, int position, Long itemId) {
        if (!grayEnabled) {
            return;
        }

        String group = isGrayUser(userId) ? "gray" : "control";
        
        // Redis 存储：group:date:metric -> count
        String dateKey = java.time.LocalDate.now().toString();
        
        // 曝光计数
        String exposureKey = METRICS_KEY + group + ":exposure:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(exposureKey, String.valueOf(userId));
        redisTemplate.expire(exposureKey, 8, TimeUnit.DAYS);
        
        // 按算法记录
        String algoKey = METRICS_KEY + group + ":" + algorithm + ":exposure:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(algoKey, String.valueOf(userId));
        redisTemplate.expire(algoKey, 8, TimeUnit.DAYS);
        
        log.debug("记录曝光: userId={}, group={}, algorithm={}, position={}, itemId={}", 
                userId, group, algorithm, position, itemId);
    }

    /**
     * 记录点击埋点
     */
    public void recordClick(Long userId, String algorithm, int position, Long itemId) {
        if (!grayEnabled) {
            return;
        }

        String group = isGrayUser(userId) ? "gray" : "control";
        String dateKey = java.time.LocalDate.now().toString();
        
        // 点击计数
        String clickKey = METRICS_KEY + group + ":click:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(clickKey, String.valueOf(userId));
        redisTemplate.expire(clickKey, 8, TimeUnit.DAYS);
        
        // 按算法记录
        String algoKey = METRICS_KEY + group + ":" + algorithm + ":click:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(algoKey, String.valueOf(userId));
        redisTemplate.expire(algoKey, 8, TimeUnit.DAYS);
        
        log.debug("记录点击: userId={}, group={}, algorithm={}, position={}, itemId={}", 
                userId, group, algorithm, position, itemId);
    }

    /**
     * 记录加购埋点
     */
    public void recordCart(Long userId, String algorithm, Long itemId) {
        if (!grayEnabled) {
            return;
        }

        String group = isGrayUser(userId) ? "gray" : "control";
        String dateKey = java.time.LocalDate.now().toString();
        
        // 加购计数
        String cartKey = METRICS_KEY + group + ":cart:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(cartKey, String.valueOf(userId));
        redisTemplate.expire(cartKey, 8, TimeUnit.DAYS);
        
        // 按算法记录
        String algoKey = METRICS_KEY + group + ":" + algorithm + ":cart:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(algoKey, String.valueOf(userId));
        redisTemplate.expire(algoKey, 8, TimeUnit.DAYS);
        
        log.debug("记录加购: userId={}, group={}, algorithm={}, itemId={}", 
                userId, group, algorithm, itemId);
    }

    /**
     * 记录下单埋点
     */
    public void recordOrder(Long userId, String algorithm, Long itemId, double amount) {
        if (!grayEnabled) {
            return;
        }

        String group = isGrayUser(userId) ? "gray" : "control";
        String dateKey = java.time.LocalDate.now().toString();
        
        // 下单计数
        String orderKey = METRICS_KEY + group + ":order:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(orderKey, String.valueOf(userId));
        redisTemplate.expire(orderKey, 8, TimeUnit.DAYS);
        
        // 按算法记录
        String algoKey = METRICS_KEY + group + ":" + algorithm + ":order:" + dateKey;
        redisTemplate.opsForHyperLogLog().add(algoKey, String.valueOf(userId));
        redisTemplate.expire(algoKey, 8, TimeUnit.DAYS);
        
        // 记录金额
        String amountKey = METRICS_KEY + group + ":" + algorithm + ":amount:" + dateKey;
        redisTemplate.opsForValue().increment(amountKey, (long) (amount * 100));
        redisTemplate.expire(amountKey, 8, TimeUnit.DAYS);
        
        log.debug("记录下单: userId={}, group={}, algorithm={}, itemId={}, amount={}", 
                userId, group, algorithm, itemId, amount);
    }

    /**
     * 获取灰度指标
     */
    public Map<String, Object> getMetrics(String date) {
        if (!grayEnabled) {
            return Map.of("enabled", false);
        }

        String targetDate = date != null ? date : java.time.LocalDate.now().toString();
        
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("date", targetDate);
        result.put("grayRatio", grayRatio);
        
        // 获取各组指标
        result.put("gray", getGroupMetrics("gray", targetDate));
        result.put("control", getGroupMetrics("control", targetDate));
        
        return result;
    }

    private Map<String, Object> getGroupMetrics(String group, String date) {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        
        String exposureKey = METRICS_KEY + group + ":exposure:" + date;
        String clickKey = METRICS_KEY + group + ":click:" + date;
        String cartKey = METRICS_KEY + group + ":cart:" + date;
        String orderKey = METRICS_KEY + group + ":order:" + date;
        
        Long exposureCount = redisTemplate.opsForHyperLogLog().size(exposureKey);
        Long clickCount = redisTemplate.opsForHyperLogLog().size(clickKey);
        Long cartCount = redisTemplate.opsForHyperLogLog().size(cartKey);
        Long orderCount = redisTemplate.opsForHyperLogLog().size(orderKey);
        
        metrics.put("exposure", exposureCount != null ? exposureCount : 0);
        metrics.put("click", clickCount != null ? clickCount : 0);
        metrics.put("cart", cartCount != null ? cartCount : 0);
        metrics.put("order", orderCount != null ? orderCount : 0);
        
        long exp = exposureCount != null ? Math.max(exposureCount, 1L) : 1L;
        if (exp <= 0) {
            exp = 1L;
        }
        metrics.put("ctr", round4((double) clickCount / exp));
        metrics.put("cartRate", round4((double) cartCount / exp));
        metrics.put("orderRate", round4((double) orderCount / exp));
        
        return metrics;
    }

    /**
     * 获取灰度组和对照组的对比指标
     */
    public Map<String, Object> compareMetrics(String date) {
        if (!grayEnabled) {
            return Map.of("enabled", false, "message", "灰度发布未启用");
        }

        Map<String, Object> grayMetrics = getGroupMetrics("gray", date);
        Map<String, Object> controlMetrics = getGroupMetrics("control", date);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("date", date != null ? date : java.time.LocalDate.now().toString());
        result.put("grayRatio", grayRatio);
        
        // 对比数据
        Map<String, Object> comparison = new java.util.LinkedHashMap<>();
        
        double grayCtr = getDoubleValue(grayMetrics, "ctr", 0);
        double controlCtr = getDoubleValue(controlMetrics, "ctr", 0);
        comparison.put("ctr", Map.of(
            "gray", grayCtr,
            "control", controlCtr,
            "improvement", round4((grayCtr - controlCtr) / Math.max(controlCtr, 0.001) * 100)
        ));
        
        double grayCartRate = getDoubleValue(grayMetrics, "cartRate", 0);
        double controlCartRate = getDoubleValue(controlMetrics, "cartRate", 0);
        comparison.put("cartRate", Map.of(
            "gray", grayCartRate,
            "control", controlCartRate,
            "improvement", round4((grayCartRate - controlCartRate) / Math.max(controlCartRate, 0.001) * 100)
        ));
        
        double grayOrderRate = getDoubleValue(grayMetrics, "orderRate", 0);
        double controlOrderRate = getDoubleValue(controlMetrics, "orderRate", 0);
        comparison.put("orderRate", Map.of(
            "gray", grayOrderRate,
            "control", controlOrderRate,
            "improvement", round4((grayOrderRate - controlOrderRate) / Math.max(controlOrderRate, 0.001) * 100)
        ));
        
        result.put("comparison", comparison);
        result.put("gray", grayMetrics);
        result.put("control", controlMetrics);
        
        return result;
    }

    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public boolean isGrayEnabled() {
        return grayEnabled;
    }

    public int getGrayRatio() {
        return grayRatio;
    }
}
