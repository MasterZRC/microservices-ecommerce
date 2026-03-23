package com.ecommerce.recommendation.service;

import com.ecommerce.recommendation.algorithm.ItemCFAlgorithm;
import com.ecommerce.recommendation.entity.UserBehavior;
import com.ecommerce.recommendation.mapper.UserBehaviorMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 缓存预热服务
 * 
 * 生产级推荐系统必须在服务启动时就预热缓存，避免冷启动问题。
 * 本服务在启动时和定时任务中预热以下缓存：
 * 
 * 1. 热门商品列表（Sorted Set，支持时间衰减）
 * 2. ItemCF 相似度矩阵
 * 3. 商品类目映射
 * 4. 商品 TF-IDF 向量（内容召回用）
 * 5. 商品基础特征
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachePreheatService {

    private final UserBehaviorMapper behaviorMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CandidateRecallService candidateRecallService;

    @Value("${recommendation.cachePreheat.enabled:true}")
    private boolean cachePreheatEnabled;

    @Value("${recommendation.popular.time-decay-factor:0.95}")
    private double popularTimeDecayFactor;

    @Value("${recommendation.similarity-cache-version:v1}")
    private String similarityCacheVersion;

    @Value("${recommendation.cachePreheat.asyncPreheat:true}")
    private boolean asyncPreheat;

    private static final String POPULAR_ITEMS_KEY = "recommendation:popular:";
    private static final String ITEM_CATEGORY_KEY = "recommendation:item:category:";
    private static final String SIMILARITY_KEY = "recommendation:similarity:";
    private static final String ITEM_FEATURE_KEY = "recommendation:item:features:";
    private static final String LAST_PREHEAT_KEY = "recommendation:cache:lastPreheat";

    private static final Map<String, Integer> BEHAVIOR_WEIGHT = buildBehaviorWeight();
    private static final long MAX_DECAY_DAYS = 30;

    /**
     * 服务启动时自动预热缓存
     */
    @PostConstruct
    public void init() {
        if (cachePreheatEnabled) {
            if (asyncPreheat) {
                // 异步预热，不阻塞服务启动
                new Thread(() -> {
                    try {
                        Thread.sleep(5000); // 等待服务完全启动
                        preheatAllCache();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "cache-preheat-init").start();
                log.info("[CachePreheat] 已启动异步预热任务");
            } else {
                preheatAllCache();
            }
        }
    }

    /**
     * 定时预热缓存（每小时执行一次）
     * 确保热门数据和相似度矩阵始终是最新的
     */
    @Scheduled(cron = "${recommendation.cachePreheat.cron:0 0 * * * ?}")
    public void scheduledPreheat() {
        if (cachePreheatEnabled) {
            log.info("[CachePreheat] 开始定时预热缓存");
            preheatAllCache();
        }
    }

    /**
     * 预热所有缓存
     */
    public void preheatAllCache() {
        long startTime = System.currentTimeMillis();
        log.info("[CachePreheat] 开始预热缓存...");

        try {
            // 1. 预热热门商品
            preheatPopularItems();
            
            // 2. 预热商品类目映射
            preheatItemCategoryMap();
            
            // 3. 预热 ItemCF 相似度矩阵
            preheatSimilarityMatrix();
            
            // 4. 预热商品基础特征
            preheatItemFeatures();
            
            // 5. 预热内容召回向量
            preheatContentVectors();

            // 记录预热完成时间
            redisTemplate.opsForValue().set(LAST_PREHEAT_KEY, System.currentTimeMillis());

            long duration = System.currentTimeMillis() - startTime;
            log.info("[CachePreheat] 缓存预热完成，耗时: {}ms", duration);

        } catch (Exception e) {
            log.error("[CachePreheat] 缓存预热失败", e);
        }
    }

    /**
     * 预热热门商品（使用 Sorted Set 支持时间衰减）
     */
    @Async
    public void preheatPopularItems() {
        log.info("[CachePreheat] 预热热门商品...");
        
        try {
            List<UserBehavior> allBehaviors = behaviorMapper.selectList(
                new LambdaQueryWrapper<UserBehavior>()
                    .orderByDesc(UserBehavior::getCreateTime)
                    .last("LIMIT 20000")
            );

            if (allBehaviors.isEmpty()) {
                log.warn("[CachePreheat] 无行为数据，跳过热门商品预热");
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            Map<Long, Double> scoreMap = new HashMap<>();

            for (UserBehavior behavior : allBehaviors) {
                String type = behavior.getBehaviorType();
                double baseWeight = BEHAVIOR_WEIGHT.getOrDefault(type, 1);
                double recencyWeight = getRecencyWeight(behavior.getCreateTime(), now);
                double finalScore = baseWeight * recencyWeight;

                scoreMap.merge(behavior.getProductId(), finalScore, Double::sum);
            }

            List<Long> popularItems = scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .limit(200)
                    .collect(Collectors.toList());

            // 存入 Redis（使用带版本号的 key）
            String popKey = POPULAR_ITEMS_KEY + "decay-" + popularTimeDecayFactor + ":all";
            redisTemplate.opsForValue().set(popKey, popularItems, 2, TimeUnit.HOURS);

            // 同时存入 Sorted Set 用于精确的时间衰减查询
            String zsetKey = POPULAR_ITEMS_KEY + "zset:all";
            redisTemplate.delete(zsetKey);
            for (Map.Entry<Long, Double> entry : scoreMap.entrySet()) {
                redisTemplate.opsForZSet().add(zsetKey, String.valueOf(entry.getKey()), entry.getValue());
            }
            redisTemplate.expire(zsetKey, 2, TimeUnit.HOURS);

            log.info("[CachePreheat] 热门商品预热完成: {} 个商品", popularItems.size());

        } catch (Exception e) {
            log.error("[CachePreheat] 热门商品预热失败", e);
        }
    }

    /**
     * 预热商品类目映射
     */
    @Async
    public void preheatItemCategoryMap() {
        log.info("[CachePreheat] 预热商品类目映射...");

        try {
            Map<Long, Long> categoryMap = candidateRecallService.buildItemCategoryMap();

            String cacheKey = ITEM_CATEGORY_KEY + "all";
            redisTemplate.opsForValue().set(cacheKey, categoryMap, 1, TimeUnit.HOURS);

            log.info("[CachePreheat] 商品类目映射预热完成: {} 个商品", categoryMap.size());

        } catch (Exception e) {
            log.error("[CachePreheat] 商品类目映射预热失败", e);
        }
    }

    /**
     * 预热 ItemCF 相似度矩阵
     */
    @Async
    public void preheatSimilarityMatrix() {
        log.info("[CachePreheat] 预热 ItemCF 相似度矩阵...");

        try {
            Map<Long, Map<Long, Double>> userItemScoreMatrix = buildUserItemScoreMatrix();
            Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();

            if (userItemScoreMatrix.isEmpty()) {
                log.warn("[CachePreheat] 无行为数据，跳过相似度矩阵预热");
                return;
            }

            Map<Long, Map<Long, Double>> similarityMatrix =
                ItemCFAlgorithm.computeItemSimilarityWeighted(userItemScoreMatrix, itemCategoryMap);

            String cacheKey = SIMILARITY_KEY + similarityCacheVersion + ":all";
            redisTemplate.opsForValue().set(cacheKey, similarityMatrix, 24, TimeUnit.HOURS);

            log.info("[CachePreheat] ItemCF 相似度矩阵预热完成: {} 个商品", similarityMatrix.size());

        } catch (Exception e) {
            log.error("[CachePreheat] ItemCF 相似度矩阵预热失败", e);
        }
    }

    /**
     * 预热商品基础特征
     */
    @Async
    public void preheatItemFeatures() {
        log.info("[CachePreheat] 预热商品基础特征...");

        try {
            Map<Long, Map<String, Object>> featureMap = candidateRecallService.buildFullItemFeatureMap();

            String cacheKey = ITEM_FEATURE_KEY + "all";
            redisTemplate.opsForValue().set(cacheKey, featureMap, 1, TimeUnit.HOURS);

            log.info("[CachePreheat] 商品基础特征预热完成: {} 个商品", featureMap.size());

        } catch (Exception e) {
            log.error("[CachePreheat] 商品基础特征预热失败", e);
        }
    }

    /**
     * 预热内容召回向量（TF-IDF）
     */
    @Async
    public void preheatContentVectors() {
        log.info("[CachePreheat] 预热内容召回向量...");
        // TF-IDF 向量的构建在 CandidateRecallService 中实现
        // 这里只需要触发一次构建即可
        try {
            // 触发 TF-IDF 向量构建
            candidateRecallService.buildItemCategoryMap();
            log.info("[CachePreheat] 内容召回向量预热完成");
        } catch (Exception e) {
            log.error("[CachePreheat] 内容召回向量预热失败", e);
        }
    }

    /**
     * 获取上次预热时间
     */
    public Long getLastPreheatTime() {
        Object value = redisTemplate.opsForValue().get(LAST_PREHEAT_KEY);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    /**
     * 构建用户-物品加权矩阵
     */
    private Map<Long, Map<Long, Double>> buildUserItemScoreMatrix() {
        Map<Long, Map<Long, Double>> matrix = new HashMap<>();
        List<UserBehavior> behaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .orderByDesc(UserBehavior::getCreateTime)
                .last("LIMIT 50000")
        );

        LocalDateTime now = LocalDateTime.now();
        for (UserBehavior behavior : behaviors) {
            String behaviorType = behavior.getBehaviorType();
            double baseWeight = BEHAVIOR_WEIGHT.getOrDefault(behaviorType, 1);
            double recencyWeight = getRecencyWeight(behavior.getCreateTime(), now);
            double finalScore = baseWeight * recencyWeight;

            matrix
                .computeIfAbsent(behavior.getUserId(), key -> new HashMap<>())
                .merge(behavior.getProductId(), finalScore, Double::sum);
        }

        return matrix;
    }

    private static Map<String, Integer> buildBehaviorWeight() {
        Map<String, Integer> map = new HashMap<>();
        map.put("view", 1);
        map.put("click", 2);
        map.put("cart", 4);
        map.put("favorite", 5);
        map.put("buy", 8);
        return Collections.unmodifiableMap(map);
    }

    private double getRecencyWeight(LocalDateTime behaviorTime, LocalDateTime now) {
        if (behaviorTime == null) {
            return 1.0;
        }
        long days = Math.max(0, Math.min(MAX_DECAY_DAYS, ChronoUnit.DAYS.between(behaviorTime, now)));
        return Math.pow(popularTimeDecayFactor, days);
    }
}
