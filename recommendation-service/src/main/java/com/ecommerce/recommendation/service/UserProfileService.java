package com.ecommerce.recommendation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.recommendation.entity.UserBehavior;
import com.ecommerce.recommendation.entity.UserPortrait;
import com.ecommerce.recommendation.mapper.UserBehaviorMapper;
import com.ecommerce.recommendation.mapper.UserPortraitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用户画像标签服务（增强版 - MySQL持久化 + Redis缓存）
 *
 * 核心创新点：
 * 1. Redis + MySQL 双写：Redis优先读写，异步持久化MySQL
 * 2. RFM分层模型：Recency(最近)/Frequency(频率)/Monetary(金额)
 * 3. 增量更新：行为记录触发增量更新，不触发全量重算
 * 4. 定时全量刷新：每日凌晨3点全量刷新活跃用户画像
 * 5. 多维度标签：活跃等级、消费能力、偏好类目/品牌、浏览深度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserBehaviorMapper behaviorMapper;
    private final UserPortraitMapper portraitMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CandidateRecallService candidateRecallService;

    private static final String PROFILE_KEY_PREFIX = "user:profile:";
    private static final String COUNTER_SUFFIX = ":counters";
    private static final String CATEGORY_SCORE_SUFFIX = ":category_scores";
    private static final String DIRTY_SET = "user:profile:dirty";
    private static final long PROFILE_TTL_DAYS = 30;

    @Value("${user.profile.enabled:true}")
    private boolean profileEnabled;

    @Value("${user.profile.active-days:30}")
    private int activeDaysThreshold;

    @Value("${user.profile.mysql-persist:true}")
    private boolean mysqlPersistEnabled;

    // ==================== 行为触发增量更新 ====================

    /**
     * 行为记录时增量更新用户画像
     * 在 RecommendationService.recordBehavior 之后调用
     *
     * 策略：
     * 1. 立即更新Redis（快速响应）
     * 2. 异步写入MySQL（保证持久化）
     * 3. 标记脏数据用于定时全量刷新
     */
    public void updateProfileOnBehavior(Long userId, Long productId, String behaviorType) {
        if (!profileEnabled || userId == null) return;

        try {
            String profileKey = PROFILE_KEY_PREFIX + userId;

            // 获取商品类目信息
            Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();
            Long categoryId = itemCategoryMap.get(productId);

            // 增量更新行为计数
            String countKey = profileKey + COUNTER_SUFFIX;
            redisTemplate.opsForHash().increment(countKey, "total_behaviors", 1);
            redisTemplate.opsForHash().increment(countKey, "behavior_" + behaviorType, 1);
            redisTemplate.expire(countKey, PROFILE_TTL_DAYS, TimeUnit.DAYS);

            // 更新偏好类目（购买/加购权重最高）
            if ("buy".equals(behaviorType) || "cart".equals(behaviorType)) {
                if (categoryId != null) {
                    String catKey = profileKey + CATEGORY_SCORE_SUFFIX;
                    int weight = "buy".equals(behaviorType) ? 5 : 3;
                    redisTemplate.opsForHash().increment(catKey, String.valueOf(categoryId), weight);
                    redisTemplate.expire(catKey, PROFILE_TTL_DAYS, TimeUnit.DAYS);
                }
            }

            // 更新最后活跃时间
            redisTemplate.opsForValue().set(profileKey + ":last_active", System.currentTimeMillis());

            // 标记需要定期全量刷新
            redisTemplate.opsForSet().add(DIRTY_SET, userId);

            // 异步持久化到MySQL
            if (mysqlPersistEnabled) {
                asyncPersistToMySQL(userId);
            }

            log.debug("增量更新用户画像: userId={}, behavior={}", userId, behaviorType);
        } catch (Exception e) {
            log.warn("更新用户画像失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    @Async
    public void asyncPersistToMySQL(Long userId) {
        try {
            UserPortrait portrait = buildPortraitFromRedis(userId);
            if (portrait == null) return;

            UserPortrait existing = portraitMapper.selectByUserId(userId);
            if (existing == null) {
                portrait.setCreateTime(LocalDateTime.now());
                portrait.setUpdateTime(LocalDateTime.now());
                portrait.setVersion(0);
                portraitMapper.insert(portrait);
                log.debug("MySQL新增用户画像: userId={}", userId);
            } else {
                portrait.setId(existing.getId());
                portrait.setVersion(existing.getVersion());
                portrait.setCreateTime(existing.getCreateTime());
                portrait.setUpdateTime(LocalDateTime.now());
                int rows = portraitMapper.updateById(portrait);
                if (rows == 0) {
                    log.warn("用户画像更新失败（版本冲突）: userId={}", userId);
                }
            }
        } catch (Exception e) {
            log.error("异步持久化用户画像到MySQL失败: userId={}", userId, e);
        }
    }

    private UserPortrait buildPortraitFromRedis(Long userId) {
        String profileKey = PROFILE_KEY_PREFIX + userId;

        Map<Object, Object> counters = redisTemplate.opsForHash().entries(profileKey + COUNTER_SUFFIX);
        Map<Object, Object> categoryScores = redisTemplate.opsForHash().entries(profileKey + CATEGORY_SCORE_SUFFIX);
        Object lastActive = redisTemplate.opsForValue().get(profileKey + ":last_active");

        if (counters.isEmpty() && categoryScores.isEmpty()) {
            return null;
        }

        UserPortrait portrait = new UserPortrait();
        portrait.setUserId(userId);

        // 解析行为计数
        Map<String, Integer> behaviorCounts = new HashMap<>();
        counters.forEach((k, v) -> {
            if (k.toString().startsWith("behavior_")) {
                String type = k.toString().substring("behavior_".length());
                behaviorCounts.put(type, parseInt(v));
            }
        });

        int totalBehaviors = behaviorCounts.values().stream().mapToInt(Integer::intValue).sum();
        int buyCount = behaviorCounts.getOrDefault("buy", 0);
        int cartCount = behaviorCounts.getOrDefault("cart", 0);
        int viewCount = behaviorCounts.getOrDefault("view", 0);

        portrait.setActiveLevel(computeActiveLevel(totalBehaviors, lastActive));
        portrait.setPurchasePower(computePurchasePower(buyCount, cartCount));
        portrait.setBrowseDepth(computeBrowseDepth(viewCount, totalBehaviors));
        portrait.setRfmScore(computeRfmScore(lastActive, totalBehaviors, buyCount));
        portrait.setBehaviorCount(totalBehaviors);
        portrait.setBuyCount(buyCount);
        portrait.setCartCount(cartCount);

        if (lastActive != null) {
            portrait.setLastActiveTime(
                LocalDateTime.now().minus(Duration.ofMillis(System.currentTimeMillis() - parseLong(lastActive)))
            );
        }

        return portrait;
    }

    // ==================== 定时全量刷新 ====================

    @Scheduled(cron = "${user.profile.refresh-cron:0 0 3 * * ?}")
    public void scheduledProfileRefresh() {
        if (!profileEnabled) return;

        log.info("开始全量刷新用户画像...");
        Set<Object> dirtyUsers = redisTemplate.opsForSet().members(DIRTY_SET);
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
                redisTemplate.opsForSet().remove(DIRTY_SET, userIdObj);
                refreshed++;
            } catch (Exception e) {
                failed++;
                log.warn("刷新用户画像失败: userId={}, error={}", userId, e.getMessage());
            }
        }

        log.info("全量刷新完成: 成功={}, 失败={}", refreshed, failed);
    }

    /**
     * 构建完整用户画像（基于MySQL行为数据）
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
        Map<Long, Map<String, Object>> itemFeatures = candidateRecallService.buildFullItemFeatureMap();

        // 计算标签
        String activeLevel = computeActiveLevelFromBehaviors(behaviors, now);
        String purchasePower = computePurchasePowerFromBehaviors(behaviors);
        List<String> preferCategories = computePreferCategories(behaviors, itemCategoryMap);
        String browseDepth = computeBrowseDepthFromBehaviors(behaviors);
        String priceRange = computePriceRangeFromBehaviors(behaviors, itemFeatures);
        double rfmScore = computeRfmScoreFromBehaviors(behaviors, now);

        int totalBehaviors = behaviors.size();
        int buyCount = (int) behaviors.stream().filter(b -> "buy".equalsIgnoreCase(b.getBehaviorType())).count();
        int cartCount = (int) behaviors.stream().filter(b -> "cart".equalsIgnoreCase(b.getBehaviorType())).count();
        LocalDateTime lastActive = behaviors.stream()
            .map(UserBehavior::getCreateTime)
            .max(LocalDateTime::compareTo)
            .orElse(now);

        // 写入Redis
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("active_level", activeLevel);
        profile.put("purchase_power", purchasePower);
        profile.put("prefer_categories", String.join(",", preferCategories));
        profile.put("browse_depth", browseDepth);
        profile.put("price_range", priceRange);
        profile.put("rfm_score", rfmScore);
        profile.put("last_update", now.toString());
        profile.put("behavior_count", totalBehaviors);
        profile.put("buy_count", buyCount);
        profile.put("cart_count", cartCount);

        redisTemplate.opsForHash().putAll(profileKey, profile);
        redisTemplate.expire(profileKey, PROFILE_TTL_DAYS, TimeUnit.DAYS);

        log.debug("构建用户画像: userId={}, active={}, power={}, categories={}",
            userId, activeLevel, purchasePower, preferCategories);
    }

    // ==================== 查询接口 ====================

    /**
     * 获取用户画像标签
     */
    public Map<String, Object> getProfile(Long userId) {
        String profileKey = PROFILE_KEY_PREFIX + userId;
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(profileKey);

        if (raw == null || raw.isEmpty()) {
            buildFullProfile(userId);
            raw = redisTemplate.opsForHash().entries(profileKey);
        }

        if (raw == null || raw.isEmpty()) {
            // 尝试从MySQL读取
            UserPortrait portrait = portraitMapper.selectByUserId(userId);
            if (portrait != null) {
                return convertPortraitToMap(portrait);
            }
            return Collections.emptyMap();
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        raw.forEach((k, v) -> profile.put(k.toString(), v));
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

    /**
     * 获取用户RFM分层标签
     */
    public String getRfmLevel(Long userId) {
        Map<String, Object> profile = getProfile(userId);
        Double rfmScore = profile.get("rfm_score") != null
            ? Double.parseDouble(profile.get("rfm_score").toString()) : 0.0;

        if (rfmScore >= 0.8) return "高价值用户";
        if (rfmScore >= 0.5) return "活跃用户";
        if (rfmScore >= 0.2) return "潜力用户";
        return "沉默用户";
    }

    // ==================== 标签计算方法 ====================

    private String computeActiveLevel(int totalBehaviors, Object lastActiveTime) {
        long daysSinceActive = 30;
        if (lastActiveTime != null) {
            long lastActive = parseLong(lastActiveTime);
            daysSinceActive = (System.currentTimeMillis() - lastActive) / (1000 * 60 * 60 * 24);
        }

        if (daysSinceActive > 14) return "沉默";
        if (daysSinceActive > 7) return "低活";
        if (totalBehaviors > 50) return "高活";
        return "中活";
    }

    private String computeActiveLevelFromBehaviors(List<UserBehavior> behaviors, LocalDateTime now) {
        LocalDateTime lastActive = behaviors.stream()
            .map(UserBehavior::getCreateTime)
            .max(LocalDateTime::compareTo)
            .orElse(now);
        long daysSinceActive = java.time.temporal.ChronoUnit.DAYS.between(lastActive, now);

        int totalBehaviors = behaviors.size();
        if (daysSinceActive > 14) return "沉默";
        if (daysSinceActive > 7) return "低活";
        if (totalBehaviors > 50) return "高活";
        return "中活";
    }

    private String computePurchasePower(int buyCount, int cartCount) {
        int score = buyCount * 5 + cartCount * 2;
        if (score >= 10) return "高消费";
        if (score >= 3) return "中消费";
        return "低消费";
    }

    private String computePurchasePowerFromBehaviors(List<UserBehavior> behaviors) {
        long buyCount = behaviors.stream()
            .filter(b -> "buy".equalsIgnoreCase(b.getBehaviorType())).count();
        long cartCount = behaviors.stream()
            .filter(b -> "cart".equalsIgnoreCase(b.getBehaviorType())).count();
        return computePurchasePower((int) buyCount, (int) cartCount);
    }

    private String computeBrowseDepth(int viewCount, int totalBehaviors) {
        if (viewCount > 100 || totalBehaviors > 200) return "深度浏览";
        if (viewCount > 20 || totalBehaviors > 50) return "中度浏览";
        return "浅度浏览";
    }

    private String computeBrowseDepthFromBehaviors(List<UserBehavior> behaviors) {
        long viewCount = behaviors.stream()
            .filter(b -> "view".equalsIgnoreCase(b.getBehaviorType())).count();
        return computeBrowseDepth((int) viewCount, behaviors.size());
    }

    private List<String> computePreferCategories(List<UserBehavior> behaviors, Map<Long, Long> itemCategoryMap) {
        Map<Long, Double> categoryScores = new HashMap<>();

        for (UserBehavior behavior : behaviors) {
            Long categoryId = itemCategoryMap.get(behavior.getProductId());
            if (categoryId == null) continue;

            String type = normalizeBehaviorType(behavior.getBehaviorType());
            double weight = getBehaviorWeight(type);
            categoryScores.merge(categoryId, weight, Double::sum);
        }

        // 返回Top3类目
        return categoryScores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(3)
            .map(e -> "类目" + e.getKey())
            .collect(Collectors.toList());
    }

    private String computePriceRangeFromBehaviors(List<UserBehavior> behaviors, Map<Long, Map<String, Object>> itemFeatures) {
        long buyCartCount = behaviors.stream()
            .filter(b -> "buy".equalsIgnoreCase(b.getBehaviorType())
                    || "cart".equalsIgnoreCase(b.getBehaviorType())).count();
        if (buyCartCount > 10) return "高价位";
        if (buyCartCount > 3) return "中价位";
        return "低价位";
    }

    /**
     * 计算RFM得分
     * - Recency: 最近活跃时间越近越好
     * - Frequency: 行为频率越高越好
     * - Monetary: 购买行为越多越好
     */
    private double computeRfmScore(Object lastActiveTime, int totalBehaviors, int buyCount) {
        // Recency得分 (0-1, 越近越高)
        double recencyScore = 0.5;
        if (lastActiveTime != null) {
            long lastActive = parseLong(lastActiveTime);
            long daysSinceActive = (System.currentTimeMillis() - lastActive) / (1000 * 60 * 60 * 24);
            recencyScore = Math.max(0, 1.0 - daysSinceActive / 30.0);
        }

        // Frequency得分 (0-1)
        double frequencyScore = Math.min(1.0, totalBehaviors / 100.0);

        // Monetary得分 (0-1)
        double monetaryScore = Math.min(1.0, buyCount / 20.0);

        // 综合得分
        return recencyScore * 0.4 + frequencyScore * 0.3 + monetaryScore * 0.3;
    }

    private double computeRfmScoreFromBehaviors(List<UserBehavior> behaviors, LocalDateTime now) {
        LocalDateTime lastActive = behaviors.stream()
            .map(UserBehavior::getCreateTime)
            .max(LocalDateTime::compareTo)
            .orElse(now);
        long daysSinceActive = java.time.temporal.ChronoUnit.DAYS.between(lastActive, now);

        double recencyScore = Math.max(0, 1.0 - daysSinceActive / 30.0);
        double frequencyScore = Math.min(1.0, behaviors.size() / 100.0);
        long buyCount = behaviors.stream()
            .filter(b -> "buy".equalsIgnoreCase(b.getBehaviorType())).count();
        double monetaryScore = Math.min(1.0, buyCount / 20.0);

        return recencyScore * 0.4 + frequencyScore * 0.3 + monetaryScore * 0.3;
    }

    private Map<String, Object> convertPortraitToMap(UserPortrait portrait) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("user_id", portrait.getUserId());
        map.put("active_level", portrait.getActiveLevel());
        map.put("purchase_power", portrait.getPurchasePower());
        map.put("prefer_categories", portrait.getPreferCategoryIds());
        map.put("prefer_category_names", portrait.getPreferCategoryNames());
        map.put("browse_depth", portrait.getBrowseDepth());
        map.put("price_range", portrait.getPriceRange());
        map.put("rfm_score", portrait.getRfmScore());
        map.put("rfm_level", getRfmLevelFromScore(portrait.getRfmScore()));
        map.put("behavior_count", portrait.getBehaviorCount());
        map.put("buy_count", portrait.getBuyCount());
        map.put("cart_count", portrait.getCartCount());
        map.put("last_active_time", portrait.getLastActiveTime());
        return map;
    }

    private String getRfmLevelFromScore(Double score) {
        if (score == null || score < 0.2) return "沉默用户";
        if (score < 0.5) return "潜力用户";
        if (score < 0.8) return "活跃用户";
        return "高价值用户";
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

    private double getBehaviorWeight(String type) {
        return switch (type) {
            case "buy" -> 8.0;
            case "favorite" -> 5.0;
            case "cart" -> 4.0;
            case "click" -> 2.0;
            default -> 1.0;
        };
    }

    private int parseInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
