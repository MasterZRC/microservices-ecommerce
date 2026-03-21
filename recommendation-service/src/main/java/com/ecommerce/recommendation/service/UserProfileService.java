package com.ecommerce.recommendation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.recommendation.entity.UserBehavior;
import com.ecommerce.recommendation.mapper.UserBehaviorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用户画像标签服务
 *
 * 存储结构：Redis Hash
 *   key: "user:profile:{userId}"
 *   fields:
 *     - active_level: 高活/中活/低活/沉默
 *     - purchase_power: 高消费/中消费/低消费
 *     - prefer_categories: ["类目1", "类目2", "类目3"]
 *     - prefer_price_range: low/middle/high
 *     - browse_depth: 浅度/中度/深度
 *     - last_update: 时间戳
 *
 * 自动更新策略：
 *   - 行为记录时：增量更新实时标签
 *   - 定时任务：每日全量刷新所有活跃用户画像
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserBehaviorMapper behaviorMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CandidateRecallService candidateRecallService;

    private static final String PROFILE_KEY_PREFIX = "user:profile:";
    private static final long PROFILE_TTL_DAYS = 30;

    @Value("${USER_PROFILE_ENABLED:true}")
    private boolean profileEnabled;

    @Value("${USER_PROFILE_ACTIVE_DAYS:30}")
    private int activeDaysThreshold;

    // ========== 行为记录触发增量更新 ==========

    /**
     * 行为记录时增量更新用户画像
     * 在 RecommendationService.recordBehavior 之后调用
     */
    public void updateProfileOnBehavior(Long userId, Long productId, String behaviorType) {
        if (!profileEnabled) return;

        try {
            String profileKey = PROFILE_KEY_PREFIX + userId;
            Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();
            Long categoryId = itemCategoryMap.get(productId);

            // 增量更新行为计数
            String countKey = profileKey + ":counters";
            redisTemplate.opsForHash().increment(countKey, "total_behaviors", 1);
            redisTemplate.opsForHash().increment(countKey, "behavior_" + behaviorType, 1);
            redisTemplate.expire(countKey, PROFILE_TTL_DAYS, TimeUnit.DAYS);

            // 更新偏好类目（购买/加购权重最高）
            if ("buy".equals(behaviorType) || "cart".equals(behaviorType)) {
                if (categoryId != null) {
                    String catKey = profileKey + ":category_scores";
                    int weight = "buy".equals(behaviorType) ? 5 : 3;
                    redisTemplate.opsForHash().increment(catKey, String.valueOf(categoryId), weight);
                    redisTemplate.expire(catKey, PROFILE_TTL_DAYS, TimeUnit.DAYS);
                }
            }

            // 更新最后活跃时间
            redisTemplate.opsForValue().set(profileKey + ":last_active", System.currentTimeMillis());

            // 标记需要定期全量刷新
            redisTemplate.opsForSet().add("user:profile:dirty", userId);

            log.debug("更新用户画像标签: userId={}, behavior={}", userId, behaviorType);
        } catch (Exception e) {
            log.warn("更新用户画像失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    // ========== 定时全量刷新 ==========

    /**
     * 每日凌晨 3 点全量刷新活跃用户画像
     */
    @Scheduled(cron = "${USER_PROFILE_REFRESH_CRON:0 0 3 * * ?}")
    public void scheduledProfileRefresh() {
        if (!profileEnabled) return;

        log.info("开始全量刷新用户画像...");
        try {
            Set<Object> dirtyUsers = redisTemplate.opsForSet().members("user:profile:dirty");
            if (dirtyUsers == null || dirtyUsers.isEmpty()) {
                log.info("无脏数据需要刷新");
                return;
            }

            int refreshed = 0;
            int failed = 0;
            for (Object userIdObj : dirtyUsers) {
                Long userId = Long.valueOf(userIdObj.toString());
                try {
                    buildFullProfile(userId);
                    redisTemplate.opsForSet().remove("user:profile:dirty", userIdObj);
                    refreshed++;
                } catch (Exception e) {
                    failed++;
                    log.warn("刷新用户画像失败: userId={}, error={}", userId, e.getMessage());
                }
            }

            log.info("全量刷新完成: 成功={}, 失败={}", refreshed, failed);
        } catch (Exception e) {
            log.error("全量刷新用户画像异常: {}", e.getMessage());
        }
    }

    /**
     * 构建完整用户画像
     */
    public void buildFullProfile(Long userId) {
        String profileKey = PROFILE_KEY_PREFIX + userId;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thirtyDaysAgo = now.minusDays(activeDaysThreshold);

        List<UserBehavior> behaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .ge(UserBehavior::getCreateTime, thirtyDaysAgo)
                .last("LIMIT 2000")
        );

        if (behaviors.isEmpty()) {
            return;
        }

        Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();

        // 1. 活跃度标签
        String activeLevel = computeActiveLevel(behaviors, now);

        // 2. 消费能力标签
        String purchasePower = computePurchasePower(behaviors);

        // 3. 偏好类目
        List<String> preferCategories = computePreferCategories(userId, itemCategoryMap);

        // 4. 浏览深度
        String browseDepth = computeBrowseDepth(behaviors);

        // 5. 价格偏好（如果有商品价格数据）
        String priceRange = computePriceRange(behaviors, itemCategoryMap);

        // 写入 Redis
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("active_level", activeLevel);
        profile.put("purchase_power", purchasePower);
        profile.put("prefer_categories", String.join(",", preferCategories));
        profile.put("browse_depth", browseDepth);
        profile.put("price_range", priceRange);
        profile.put("last_update", now.toString());
        profile.put("behavior_count", String.valueOf(behaviors.size()));

        Map<String, String> counters = new HashMap<>();
        Map<String, Integer> behaviorCounts = behaviors.stream()
                .collect(Collectors.groupingBy(
                        b -> normalizeBehaviorType(b.getBehaviorType()),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
        for (Map.Entry<String, Integer> e : behaviorCounts.entrySet()) {
            counters.put("behavior_" + e.getKey(), String.valueOf(e.getValue()));
        }

        Map<String, Object> hashValues = new HashMap<>(profile);
        counters.forEach(hashValues::put);

        redisTemplate.opsForHash().putAll(profileKey, hashValues);
        redisTemplate.expire(profileKey, PROFILE_TTL_DAYS, TimeUnit.DAYS);

        // 清理临时数据
        redisTemplate.delete(profileKey + ":counters");
        redisTemplate.delete(profileKey + ":category_scores");

        log.debug("构建用户画像: userId={}, active={}, power={}, categories={}",
                userId, activeLevel, purchasePower, preferCategories);
    }

    // ========== 查询 ==========

    /**
     * 获取用户画像标签
     */
    public Map<String, Object> getProfile(Long userId) {
        String profileKey = PROFILE_KEY_PREFIX + userId;
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(profileKey);

        if (raw == null || raw.isEmpty()) {
            // 缓存未命中，构建一次
            buildFullProfile(userId);
            raw = redisTemplate.opsForHash().entries(profileKey);
        }

        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        raw.forEach((k, v) -> profile.put(k.toString(), v));

        // 解析 prefer_categories
        Object cats = profile.get("prefer_categories");
        if (cats != null) {
            String[] catArray = cats.toString().split(",");
            profile.put("prefer_categories", Arrays.stream(catArray)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList()));
        }

        return profile;
    }

    /**
     * 批量获取用户画像
     */
    public Map<Long, Map<String, Object>> getProfiles(List<Long> userIds) {
        Map<Long, Map<String, Object>> results = new HashMap<>();
        for (Long userId : userIds) {
            results.put(userId, getProfile(userId));
        }
        return results;
    }

    // ========== 标签计算方法 ==========

    private String computeActiveLevel(List<UserBehavior> behaviors, LocalDateTime now) {
        LocalDateTime lastActive = behaviors.stream()
                .map(UserBehavior::getCreateTime)
                .max(LocalDateTime::compareTo)
                .orElse(now);
        long daysSinceActive = ChronoUnit.DAYS.between(lastActive, now);

        long totalBehaviors = behaviors.size();
        if (daysSinceActive > 14) {
            return "沉默";
        } else if (daysSinceActive > 7) {
            return "低活";
        } else if (totalBehaviors > 50) {
            return "高活";
        } else {
            return "中活";
        }
    }

    private String computePurchasePower(List<UserBehavior> behaviors) {
        long buyCount = behaviors.stream()
                .filter(b -> "buy".equalsIgnoreCase(b.getBehaviorType()))
                .count();
        long cartCount = behaviors.stream()
                .filter(b -> "cart".equalsIgnoreCase(b.getBehaviorType()))
                .count();

        int score = (int) (buyCount * 5 + cartCount * 2);
        if (score >= 10) {
            return "高消费";
        } else if (score >= 3) {
            return "中消费";
        } else {
            return "低消费";
        }
    }

    private List<String> computePreferCategories(Long userId, Map<Long, Long> itemCategoryMap) {
        // 优先使用 Redis 中预计算的类目评分
        String catKey = PROFILE_KEY_PREFIX + userId + ":category_scores";
        Map<Object, Object> scores = redisTemplate.opsForHash().entries(catKey);

        if (scores != null && !scores.isEmpty()) {
            return scores.entrySet().stream()
                    .sorted((a, b) -> {
                        double va = parseDouble(a.getValue());
                        double vb = parseDouble(b.getValue());
                        return Double.compare(vb, va);
                    })
                    .limit(3)
                    .map(e -> "类目" + e.getKey())
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private String computeBrowseDepth(List<UserBehavior> behaviors) {
        long viewCount = behaviors.stream()
                .filter(b -> "view".equalsIgnoreCase(b.getBehaviorType()))
                .count();
        if (viewCount > 100) {
            return "深度浏览";
        } else if (viewCount > 20) {
            return "中度浏览";
        } else {
            return "浅度浏览";
        }
    }

    private String computePriceRange(List<UserBehavior> behaviors, Map<Long, Long> itemCategoryMap) {
        // 简化版本：基于用户购买/加购行为估算
        long buyCartCount = behaviors.stream()
                .filter(b -> "buy".equalsIgnoreCase(b.getBehaviorType())
                        || "cart".equalsIgnoreCase(b.getBehaviorType()))
                .count();
        if (buyCartCount > 10) {
            return "高价位";
        } else if (buyCartCount > 3) {
            return "中价位";
        } else {
            return "低价位";
        }
    }

    private String normalizeBehaviorType(String type) {
        if (type == null) return "view";
        String t = type.trim().toLowerCase();
        return switch (t) {
            case "view", "browse" -> "view";
            case "click" -> "click";
            case "cart", "add_cart" -> "cart";
            case "favorite", "like" -> "favorite";
            case "buy", "purchase" -> "buy";
            default -> "view";
        };
    }

    private double parseDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
