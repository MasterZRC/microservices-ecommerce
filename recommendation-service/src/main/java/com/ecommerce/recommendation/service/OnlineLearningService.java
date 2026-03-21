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
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在线学习服务：定时收集近期用户行为，触发 rank 服务增量更新模型
 *
 * 工作流程：
 * 1. 每 30 分钟扫描近 1 小时的新增行为样本
 * 2. 从 Redis 获取候选商品的商品特征
 * 3. 构建增量训练样本（正例=购买/加购，负例=曝光未点击）
 * 4. 调用 rank 服务的 /model/incremental-update 接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineLearningService {

    private final UserBehaviorMapper behaviorMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;
    private final CandidateRecallService candidateRecallService;

    @Value("${ONLINE_LEARNING_ENABLED:false}")
    private boolean onlineLearningEnabled;

    @Value("${ONLINE_LEARNING_INTERVAL_MINUTES:30}")
    private int intervalMinutes;

    @Value("${RANK_SERVICE_URL:http://recommendation-rank-service:8010}")
    private String rankServiceUrl;

    @Value("${ONLINE_LEARNING_MIN_SAMPLES:100}")
    private int minSamples;

    @Value("${ONLINE_LEARNING_MAX_SAMPLES:5000}")
    private int maxSamples;

    /** 增量样本 Redis 队列键 */
    private static final String INCREMENTAL_SAMPLE_QUEUE = "online_learning:sample_queue";
    /** 已处理的行为记录键（幂等） */
    private static final String PROCESSED_BEHAVIOR_KEY = "online_learning:processed:";

    /**
     * 定时任务：收集近期行为并触发增量更新
     * 默认每 30 分钟执行一次
     */
    @Scheduled(fixedRateString = "${ONLINE_LEARNING_INTERVAL_MS:1800000}")
    public void scheduledIncrementalUpdate() {
        if (!onlineLearningEnabled) {
            log.debug("在线学习未启用，跳过本次更新");
            return;
        }

        log.info("开始在线学习增量更新...");
        try {
            // 第一步：收集近期正样本（购买/加购行为）
            List<Map<String, Object>> positiveSamples = collectPositiveSamples();

            // 第二步：收集近期负样本（曝光但未点击/购买）
            List<Map<String, Object>> negativeSamples = collectNegativeSamples();

            // 合并样本
            List<Map<String, Object>> allSamples = new ArrayList<>(positiveSamples);
            allSamples.addAll(negativeSamples);

            if (allSamples.size() < minSamples) {
                log.info("样本数量不足: {} < {}, 跳过本次更新", allSamples.size(), minSamples);
                return;
            }

            // 限制最大样本数
            if (allSamples.size() > maxSamples) {
                allSamples = allSamples.subList(0, maxSamples);
            }

            log.info("收集样本: 正例={}, 负例={}, 总计={}",
                    positiveSamples.size(), negativeSamples.size(), allSamples.size());

            // 第三步：触发 rank 服务增量更新
            triggerIncrementalUpdate(allSamples);

            log.info("在线学习增量更新完成");

        } catch (Exception e) {
            log.error("在线学习增量更新失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 调用 rank 服务触发增量更新
     */
    private void triggerIncrementalUpdate(List<Map<String, Object>> samples) {
        String url = rankServiceUrl + "/model/incremental-update";

        Map<String, Object> request = new HashMap<>();
        request.put("samples", samples);
        request.put("epochs", 3);
        request.put("minibatch_size", 64);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            if (response != null) {
                log.info("增量更新响应: updated_samples={}, loss_delta={}, version={}",
                        response.get("updated_samples"),
                        response.get("loss_delta"),
                        response.get("new_model_version"));
            }
        } catch (Exception e) {
            log.error("调用 rank 服务增量更新失败: {}", e.getMessage());
        }
    }

    /**
     * 收集正样本：购买和加购行为
     */
    private List<Map<String, Object>> collectPositiveSamples() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(intervalMinutes);
        List<UserBehavior> positiveBehaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .in(UserBehavior::getBehaviorType, Arrays.asList("buy", "cart"))
                .ge(UserBehavior::getCreateTime, since)
                .last("LIMIT " + maxSamples)
        );

        List<Map<String, Object>> samples = new ArrayList<>();
        Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();

        for (UserBehavior behavior : positiveBehaviors) {
            if (!shouldProcess(behavior.getId())) {
                continue;
            }

            Map<String, Object> userFeat = buildUserFeatureFromHistory(behavior.getUserId());
            Map<String, Object> itemFeat = buildItemFeature(behavior.getProductId(), itemCategoryMap);

            samples.add(Map.of(
                "user_features", userFeat,
                "item_features", itemFeat,
                "label", 1
            ));

            markProcessed(behavior.getId());
        }

        return samples;
    }

    /**
     * 收集负样本：近 1 小时有曝光但未产生购买/加购行为的用户-商品对
     * 正确语义：用户浏览过但最终未购买/加购的商品才是真正的负样本
     */
    private List<Map<String, Object>> collectNegativeSamples() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(intervalMinutes);
        // 收集近 intervalMinutes 内有浏览/点击但无购买/加购的商品-用户对
        // 负样本定义：用户曾经交互过（view/click），但最终没有转化（buy/cart）
        List<UserBehavior> allRecentBehaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .in(UserBehavior::getBehaviorType, Arrays.asList("view", "click"))
                .ge(UserBehavior::getCreateTime, since)
                .last("LIMIT " + (maxSamples / 2))
        );

        if (allRecentBehaviors.isEmpty()) {
            return Collections.emptyList();
        }

        // 收集同期购买/加购数据（扩大时间范围，覆盖意图窗口）
        LocalDateTime extendedSince = since.minusMinutes(intervalMinutes);
        Map<Long, Set<Long>> userPositiveItems = new HashMap<>();
        List<UserBehavior> positiveBehaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .in(UserBehavior::getBehaviorType, Arrays.asList("buy", "cart"))
                .ge(UserBehavior::getCreateTime, extendedSince)
        );
        for (UserBehavior b : positiveBehaviors) {
            userPositiveItems.computeIfAbsent(b.getUserId(), k -> new HashSet<>()).add(b.getProductId());
        }

        Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();
        Map<Long, Set<Long>> userSeenItems = new HashMap<>();
        for (UserBehavior b : allRecentBehaviors) {
            userSeenItems.computeIfAbsent(b.getUserId(), k -> new HashSet<>()).add(b.getProductId());
        }

        List<Map<String, Object>> samples = new ArrayList<>();
        Set<Long> seenKeys = new HashSet<>();

        for (Map.Entry<Long, Set<Long>> entry : userSeenItems.entrySet()) {
            if (samples.size() >= maxSamples / 2) break;

            Long userId = entry.getKey();
            Set<Long> seenItems = entry.getValue();
            Set<Long> positiveItems = userPositiveItems.getOrDefault(userId, Collections.emptySet());

            for (Long itemId : seenItems) {
                if (samples.size() >= maxSamples / 2) break;

                // 跳过已购买/加购的商品（正样本）
                if (positiveItems.contains(itemId)) continue;

                Long key = userId * 100000L + itemId;
                if (!seenKeys.add(key)) continue;

                Map<String, Object> userFeat = buildUserFeatureFromHistory(userId);
                Map<String, Object> itemFeat = buildItemFeature(itemId, itemCategoryMap);

                samples.add(Map.of(
                    "user_features", userFeat,
                    "item_features", itemFeat,
                    "label", 0
                ));
            }
        }

        return samples;
    }

    /**
     * 从用户完整历史构建用户特征（查询近 7 天数据）
     */
    private Map<String, Object> buildUserFeatureFromHistory(Long userId) {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);

        List<UserBehavior> behaviors7d = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .ge(UserBehavior::getCreateTime, sevenDaysAgo)
        );

        List<UserBehavior> behaviors1d = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .ge(UserBehavior::getCreateTime, oneDayAgo)
        );

        Map<String, Integer> counts1d = new HashMap<>();
        counts1d.put("view_1d", 0);
        counts1d.put("click_1d", 0);
        counts1d.put("cart_1d", 0);
        counts1d.put("buy_1d", 0);

        for (UserBehavior b : behaviors1d) {
            String type = normalizeType(b.getBehaviorType());
            counts1d.merge(type, 1, Integer::sum);
        }

        Map<String, Integer> counts7d = new HashMap<>();
        counts7d.put("view_7d", 0);
        counts7d.put("click_7d", 0);
        counts7d.put("cart_7d", 0);
        counts7d.put("buy_7d", 0);

        for (UserBehavior b : behaviors7d) {
            String type7d = normalizeTypeTo7d(b.getBehaviorType());
            if (type7d != null) {
                counts7d.merge(type7d, 1, Integer::sum);
            }
        }

        LocalDateTime lastActive = LocalDateTime.now();
        if (!behaviors7d.isEmpty()) {
            lastActive = behaviors7d.stream()
                    .map(UserBehavior::getCreateTime)
                    .max(LocalDateTime::compareTo)
                    .orElse(lastActive);
        }
        long hours = ChronoUnit.HOURS.between(lastActive, LocalDateTime.now());

        Map<String, Object> feat = new HashMap<>();
        feat.putAll(counts1d);
        feat.putAll(counts7d);
        feat.put("last_active_hours", (int) Math.min(hours, 720));
        feat.put("prefer_category", Collections.emptyList());
        feat.put("prefer_brand", Collections.emptyList());
        return feat;
    }

    /**
     * 构建商品特征（从 Redis 缓存获取真实数据，兜底时才用默认值）
     */
    private Map<String, Object> buildItemFeature(Long itemId, Map<Long, Long> itemCategoryMap) {
        Map<String, Object> feat = new HashMap<>();

        // 优先从 Redis 完整特征缓存获取（包含 brand/price/sales）
        Map<Long, Map<String, Object>> fullFeatureMap = candidateRecallService.buildFullItemFeatureMap();
        Map<String, Object> cached = fullFeatureMap.get(itemId);

        if (cached != null && !cached.isEmpty()) {
            feat.putAll(cached);
        } else {
            // 降级：只使用类目映射
            feat.put("category_id", itemCategoryMap.getOrDefault(itemId, 0L).intValue());
            feat.put("brand_id", 0);
            feat.put("price_bucket", 0);
            feat.put("sales_bucket", 0);
            feat.put("hot_score", 100.0);
        }
        return feat;
    }

    /** 幂等检查：是否已处理过该行为 */
    private boolean shouldProcess(Long behaviorId) {
        String key = PROCESSED_BEHAVIOR_KEY + behaviorId;
        Boolean exists = redisTemplate.hasKey(key);
        return !Boolean.TRUE.equals(exists);
    }

    /** 标记行为已处理 */
    private void markProcessed(Long behaviorId) {
        String key = PROCESSED_BEHAVIOR_KEY + behaviorId;
        redisTemplate.opsForValue().set(key, 1, 24, TimeUnit.HOURS);
    }

    private String normalizeType(String type) {
        if (type == null) return "view";
        String t = type.trim().toLowerCase();
        return switch (t) {
            case "view" -> "view_1d";
            case "click" -> "click_1d";
            case "cart" -> "cart_1d";
            case "buy" -> "buy_1d";
            default -> "view_1d";
        };
    }

    private String normalizeTypeTo7d(String type) {
        if (type == null) return null;
        String t = type.trim().toLowerCase();
        return switch (t) {
            case "view" -> "view_7d";
            case "click" -> "click_7d";
            case "cart" -> "cart_7d";
            case "buy" -> "buy_7d";
            default -> null;
        };
    }

    /**
     * 手动触发一次增量更新（供外部调用）
     */
    public Map<String, Object> triggerManualUpdate() {
        log.info("手动触发在线学习增量更新...");
        scheduledIncrementalUpdate();

        return Map.of(
            "status", "triggered",
            "message", "增量更新已触发，请查看服务日志确认结果"
        );
    }
}
