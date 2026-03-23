package com.ecommerce.recommendation.service;

import com.ecommerce.recommendation.algorithm.ItemCFAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增量ItemCF协同过滤服务
 *
 * 核心创新点：
 * 1. 增量更新：无需全量重算，新商品/新行为自动融入相似度计算
 * 2. 滑动窗口：只保留最近N天的交互数据，控制计算量
 * 3. 分块合并：定期将增量更新合并到主相似度矩阵
 * 4. 热更新：相似度矩阵支持热更新，无需重启服务
 *
 * 原理：
 * - 传统ItemCF需要 O(n²) 全量重算，新增商品需重新计算所有相似度
 * - 增量方案：只更新受影响商品对的相似度，时间复杂度降至 O(n·k)，k为受影响邻居数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalItemCFService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CandidateRecallService candidateRecallService;

    // ========== 配置参数 ==========
    @Value("${incremental-itemcf.enabled:true}")
    private boolean enabled;

    @Value("${incremental-itemcf.window-days:7}")
    private int windowDays;

    @Value("${incremental-itemcf.batch-size:100}")
    private int batchSize;

    @Value("${incremental-itemcf.merge-interval-ms:300000}")
    private long mergeIntervalMs;

    @Value("${incremental-itemcf.min-interactions:3}")
    private int minInteractions;

    // ========== Redis Key 常量 ==========
    private static final String INCREMENTAL_MATRIX_KEY = "recommendation:similarity:incremental:";
    private static final String PENDING_UPDATES_KEY = "recommendation:incremental:pending";
    private static final String MAIN_MATRIX_VERSION_KEY = "recommendation:similarity:version";
    private static final String LAST_MERGE_TIME_KEY = "recommendation:incremental:last_merge";

    // ========== 统计指标 ==========
    private final AtomicLong totalIncrementalUpdates = new AtomicLong(0);
    private final AtomicLong totalMerges = new AtomicLong(0);
    private final AtomicLong incrementalUpdateLatencyMs = new AtomicLong(0);
    private final AtomicBoolean mergeInProgress = new AtomicBoolean(false);

    // ========== 初始化 ==========

    @PostConstruct
    public void init() {
        if (enabled) {
            log.info("增量ItemCF服务已启用，窗口天数={}，最小交互数={}", windowDays, minInteractions);
        } else {
            log.info("增量ItemCF服务已禁用，使用传统全量重算");
        }
    }

    // ========== 增量更新接口 ==========

    /**
     * 记录增量交互（当用户产生新行为时调用）
     *
     * @param userId 用户ID
     * @param productId 商品ID
     * @param behaviorType 行为类型
     * @param weight 行为权重
     */
    public void recordIncrementalInteraction(Long userId, Long productId, String behaviorType, double weight) {
        if (!enabled) return;

        long startTime = System.currentTimeMillis();
        try {
            // 1. 获取商品的相似商品列表
            Set<Long> similarItems = getSimilarItems(productId);
            if (similarItems.isEmpty()) {
                // 如果是新商品，计算其与热门商品的相似度
                similarItems = getPopularItemsForColdStart(productId);
            }

            // 2. 更新增量相似度矩阵
            String incrementalKey = INCREMENTAL_MATRIX_KEY + productId;
            Map<Long, Double> updates = new HashMap<>();

            for (Long similarItem : similarItems) {
                // 计算增量相似度增量
                double similarityIncrement = calculateSimilarityIncrement(productId, similarItem, weight);
                if (similarityIncrement > 0.01) { // 只更新有意义的相似度变化
                    updates.put(similarItem, similarityIncrement);
                }
            }

            // 3. 写入Redis（原子操作）
            if (!updates.isEmpty()) {
                redisTemplate.opsForHash().putAll(incrementalKey, new HashMap<>(updates));
                redisTemplate.expire(incrementalKey, windowDays * 2, TimeUnit.DAYS);

                // 4. 记录待合并的更新
                redisTemplate.opsForSet().add(PENDING_UPDATES_KEY, productId);

                totalIncrementalUpdates.addAndGet(updates.size());
                log.debug("增量更新ItemCF: productId={}, 更新了{}个相似商品", productId, updates.size());
            }

        } catch (Exception e) {
            log.warn("增量更新ItemCF失败: userId={}, productId={}, error={}", userId, productId, e.getMessage());
        } finally {
            long latency = System.currentTimeMillis() - startTime;
            incrementalUpdateLatencyMs.updateAndGet(prev -> (prev + latency) / 2);
        }
    }

    /**
     * 添加新商品到增量矩阵（冷启动场景）
     */
    public void addNewItem(Long productId) {
        if (!enabled) return;

        try {
            // 1. 获取该商品所属类目的热门商品
            Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();
            Long categoryId = itemCategoryMap.get(productId);

            if (categoryId == null) {
                log.debug("新商品{}无法确定类目，跳过增量初始化", productId);
                return;
            }

            // 2. 获取同类目热门商品作为初始相似候选
            List<Long> categoryItems = candidateRecallService.recallByCategoryId(categoryId, 20);

            // 3. 初始化增量矩阵（同类别商品相似度基础分）
            String incrementalKey = INCREMENTAL_MATRIX_KEY + productId;
            Map<Object, Object> initialSimilarities = new HashMap<>();

            for (Long similarItem : categoryItems) {
                if (!similarItem.equals(productId)) {
                    // 同类目基础相似度 0.3
                    initialSimilarities.put(similarItem, 0.3);
                }
            }

            if (!initialSimilarities.isEmpty()) {
                redisTemplate.opsForHash().putAll(incrementalKey, initialSimilarities);
                redisTemplate.expire(incrementalKey, windowDays * 2, TimeUnit.DAYS);
                log.info("新商品{}已添加到增量矩阵，初始相似商品数={}", productId, initialSimilarities.size());
            }

        } catch (Exception e) {
            log.warn("添加新商品到增量矩阵失败: productId={}, error={}", productId, e.getMessage());
        }
    }

    // ========== 定时合并任务 ==========

    /**
     * 定期将增量更新合并到主相似度矩阵
     * 每5分钟执行一次
     */
    @Scheduled(fixedDelayString = "${incremental-itemcf.merge-cron-ms:300000}")
    public void mergeIncrementalUpdates() {
        if (!enabled) return;
        if (!mergeInProgress.compareAndSet(false, true)) {
            log.debug("合并任务已在执行中，跳过本次执行");
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            // 1. 获取所有待合并的商品ID
            Set<Object> pendingItems = redisTemplate.opsForSet().members(PENDING_UPDATES_KEY);
            if (pendingItems == null || pendingItems.isEmpty()) {
                log.debug("无待合并的增量更新");
                return;
            }

            log.info("开始合并增量ItemCF更新，待合并商品数={}", pendingItems.size());

            // 2. 获取主相似度矩阵
            String mainMatrixKey = "recommendation:similarity:" + ItemCFAlgorithm.SIMILARITY_CACHE_VERSION + ":all";
            @SuppressWarnings("unchecked")
            Map<Long, Map<Long, Double>> mainMatrix = (Map<Long, Map<Long, Double>>)
                redisTemplate.opsForValue().get(mainMatrixKey);

            if (mainMatrix == null) {
                log.warn("主相似度矩阵为空，跳过合并");
                return;
            }

            int mergedCount = 0;
            int updatedItems = 0;

            // 3. 分批处理待合并的商品
            List<Object> pendingList = new ArrayList<>(pendingItems);
            for (Object itemObj : pendingList) {
                Long productId = Long.valueOf(itemObj.toString());
                String incrementalKey = INCREMENTAL_MATRIX_KEY + productId;

                // 获取该商品的增量更新
                Map<Object, Object> incrementalUpdates = redisTemplate.opsForHash().entries(incrementalKey);
                if (incrementalUpdates.isEmpty()) {
                    redisTemplate.opsForSet().remove(PENDING_UPDATES_KEY, itemObj);
                    continue;
                }

                // 合并到主矩阵
                Map<Long, Double> mainSimilarities = mainMatrix.computeIfAbsent(productId, k -> new HashMap<>());

                for (Map.Entry<Object, Object> entry : incrementalUpdates.entrySet()) {
                    Long similarItem = Long.valueOf(entry.getKey().toString());
                    double increment = parseDouble(entry.getValue());
                    double currentSim = mainSimilarities.getOrDefault(similarItem, 0.0);
                    double newSim = Math.min(1.0, currentSim + increment);
                    mainSimilarities.put(similarItem, newSim);
                }

                // 清理已合并的增量数据
                redisTemplate.delete(incrementalKey);
                redisTemplate.opsForSet().remove(PENDING_UPDATES_KEY, itemObj);
                mergedCount += incrementalUpdates.size();
                updatedItems++;
            }

            // 4. 更新主矩阵（带版本号）
            long newVersion = System.currentTimeMillis();
            redisTemplate.opsForValue().set(mainMatrixKey, mainMatrix, 24, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(MAIN_MATRIX_VERSION_KEY, newVersion);
            redisTemplate.opsForValue().set(LAST_MERGE_TIME_KEY, System.currentTimeMillis());

            totalMerges.incrementAndGet();

            long duration = System.currentTimeMillis() - startTime;
            log.info("增量合并完成: 更新了{}个商品的{}个相似度关系，耗时{}ms",
                updatedItems, mergedCount, duration);

        } catch (Exception e) {
            log.error("增量合并ItemCF失败", e);
        } finally {
            mergeInProgress.set(false);
        }
    }

    // ========== 查询接口 ==========

    /**
     * 获取商品的相似商品（合并后的）
     */
    public List<Long> getSimilarItems(Long productId, int topN) {
        if (!enabled) {
            // 降级到传统方式
            return getSimilarItemsTraditional(productId, topN);
        }

        Set<Long> allSimilar = new HashSet<>();

        // 1. 先从主矩阵获取
        String mainMatrixKey = "recommendation:similarity:" + ItemCFAlgorithm.SIMILARITY_CACHE_VERSION + ":all";
        @SuppressWarnings("unchecked")
        Map<Long, Map<Long, Double>> mainMatrix = (Map<Long, Map<Long, Double>>)
            redisTemplate.opsForValue().get(mainMatrixKey);

        if (mainMatrix != null) {
            Map<Long, Double> mainSimilarities = mainMatrix.get(productId);
            if (mainSimilarities != null) {
                allSimilar.addAll(mainSimilarities.keySet());
            }
        }

        // 2. 再从增量矩阵获取（可能包含未合并的最新更新）
        String incrementalKey = INCREMENTAL_MATRIX_KEY + productId;
        Map<Object, Object> incrementalSimilarities = redisTemplate.opsForHash().entries(incrementalKey);
        incrementalSimilarities.forEach((k, v) -> allSimilar.add(Long.valueOf(k.toString())));

        // 3. 排序并返回TopN
        return allSimilar.stream()
            .filter(id -> !id.equals(productId))
            .sorted((a, b) -> {
                double simA = getSimilarity(productId, a, mainMatrix);
                double simB = getSimilarity(productId, b, mainMatrix);
                return Double.compare(simB, simA);
            })
            .limit(topN)
            .toList();
    }

    /**
     * 获取两个商品的相似度
     */
    public double getSimilarity(Long item1, Long item2,
                               Map<Long, Map<Long, Double>> mainMatrix) {
        // 1. 主矩阵
        if (mainMatrix != null) {
            Map<Long, Double> sims1 = mainMatrix.get(item1);
            if (sims1 != null && sims1.containsKey(item2)) {
                return sims1.get(item2);
            }
            Map<Long, Double> sims2 = mainMatrix.get(item2);
            if (sims2 != null && sims2.containsKey(item1)) {
                return sims2.get(item1);
            }
        }

        // 2. 增量矩阵
        String incKey1 = INCREMENTAL_MATRIX_KEY + item1;
        Object v1 = redisTemplate.opsForHash().get(incKey1, item2);
        if (v1 != null) {
            return parseDouble(v1);
        }

        String incKey2 = INCREMENTAL_MATRIX_KEY + item2;
        Object v2 = redisTemplate.opsForHash().get(incKey2, item1);
        if (v2 != null) {
            return parseDouble(v2);
        }

        return 0.0;
    }

    // ========== 私有方法 ==========

    /**
     * 获取商品的相似商品集合
     */
    private Set<Long> getSimilarItems(Long productId) {
        Set<Long> similar = new HashSet<>();

        // 从主矩阵获取
        String mainMatrixKey = "recommendation:similarity:" + ItemCFAlgorithm.SIMILARITY_CACHE_VERSION + ":all";
        @SuppressWarnings("unchecked")
        Map<Long, Map<Long, Double>> mainMatrix = (Map<Long, Map<Long, Double>>)
            redisTemplate.opsForValue().get(mainMatrixKey);

        if (mainMatrix != null) {
            Map<Long, Double> mainSims = mainMatrix.get(productId);
            if (mainSims != null) {
                similar.addAll(mainSims.keySet());
            }
        }

        // 从增量矩阵获取
        String incrementalKey = INCREMENTAL_MATRIX_KEY + productId;
        Set<Object> incrementalSims = redisTemplate.opsForHash().keys(incrementalKey);
        if (incrementalSims != null) {
            incrementalSims.forEach(obj -> similar.add(Long.valueOf(obj.toString())));
        }

        return similar;
    }

    /**
     * 为冷启动商品获取候选相似商品
     */
    private Set<Long> getPopularItemsForColdStart(Long productId) {
        Set<Long> popular = new HashSet<>();
        // 获取热门商品作为候选
        List<Long> popularItems = candidateRecallService.recallByPopular(20);
        popular.addAll(popularItems);
        popular.remove(productId);
        return popular;
    }

    /**
     * 计算相似度增量
     */
    private double calculateSimilarityIncrement(Long item1, Long item2, double interactionWeight) {
        // 简化的增量计算：基于行为权重
        // 实际应用中应该考虑共同用户数、物品流行度等因素
        double baseIncrement = 0.01;
        double weightBonus = interactionWeight * 0.001;
        return baseIncrement + weightBonus;
    }

    /**
     * 传统方式获取相似商品（降级方案）
     */
    private List<Long> getSimilarItemsTraditional(Long productId, int topN) {
        String mainMatrixKey = "recommendation:similarity:" + ItemCFAlgorithm.SIMILARITY_CACHE_VERSION + ":all";
        @SuppressWarnings("unchecked")
        Map<Long, Map<Long, Double>> mainMatrix = (Map<Long, Map<Long, Double>>)
            redisTemplate.opsForValue().get(mainMatrixKey);

        if (mainMatrix == null) {
            return Collections.emptyList();
        }

        Map<Long, Double> similarities = mainMatrix.get(productId);
        if (similarities == null || similarities.isEmpty()) {
            return Collections.emptyList();
        }

        return similarities.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(topN)
            .map(Map.Entry::getKey)
            .toList();
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

    // ========== 监控接口 ==========

    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("enabled", enabled);
        metrics.put("totalIncrementalUpdates", totalIncrementalUpdates.get());
        metrics.put("totalMerges", totalMerges.get());
        metrics.put("avgUpdateLatencyMs", incrementalUpdateLatencyMs.get());
        metrics.put("pendingUpdates", redisTemplate.opsForSet().size(PENDING_UPDATES_KEY));
        Object lastMerge = redisTemplate.opsForValue().get(LAST_MERGE_TIME_KEY);
        metrics.put("lastMergeTime", lastMerge != null ? lastMerge : "N/A");
        return metrics;
    }
}
