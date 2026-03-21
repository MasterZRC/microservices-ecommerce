package com.ecommerce.recommendation.algorithm;

import lombok.Data;
import java.util.*;

/**
 * Item-CF 协同过滤推荐算法
 * 创新点：
 * 1. 引入时间衰减因子 - 近期行为权重更高
 * 2. 结合类别相似度 - 同一类别商品相似度加权
 * 3. 热门商品降权 - 避免头部效应
 * 4. 混合冷启动策略 - 基于热门+类别+标签
 * 5. 缓存版本控制 - 支持矩阵增量更新
 */
public class ItemCFAlgorithm {

    // 时间衰减因子 (7天内行为权重衰减)
    private static final double TIME_DECAY_FACTOR = 0.8;
    private static final int DECAY_DAYS = 7;

    /** 相似度矩阵缓存版本号，配置化，支持热更新。变更版本号后旧缓存自动失效 */
    public static final String SIMILARITY_CACHE_VERSION = "v1";

    private static final double EPS = 1e-9;

    /**
     * 计算物品相似度矩阵 (改进版)
     * @param userItemMatrix 用户-物品交互矩阵
     * @param itemCategoryMap 物品-类别映射
     * @return 物品相似度矩阵
     */
    public static Map<Long, Map<Long, Double>> computeItemSimilarity(
            Map<Long, Set<Long>> userItemMatrix,
            Map<Long, Long> itemCategoryMap) {

        Map<Long, Map<Long, Double>> similarityMatrix = new HashMap<>();
        Set<Long> allItems = new HashSet<>();

        // 收集所有物品
        for (Set<Long> items : userItemMatrix.values()) {
            allItems.addAll(items);
        }

        // 计算每个物品的热门度
        Map<Long, Integer> itemPopularity = new HashMap<>();
        for (Set<Long> items : userItemMatrix.values()) {
            for (Long item : items) {
                itemPopularity.merge(item, 1, Integer::sum);
            }
        }

        // 计算物品相似度
        for (Long item1 : allItems) {
            Map<Long, Double> similarities = new HashMap<>();

            for (Long item2 : allItems) {
                if (item1.equals(item2)) continue;

                // 计算共同用户数
                int commonUsers = 0;
                for (Set<Long> items : userItemMatrix.values()) {
                    if (items.contains(item1) && items.contains(item2)) {
                        commonUsers++;
                    }
                }

                if (commonUsers > 0) {
                    // 基础相似度：共同用户数 / sqrt(物品1用户数 * 物品2用户数)
                    double pop1 = itemPopularity.getOrDefault(item1, 1);
                    double pop2 = itemPopularity.getOrDefault(item2, 1);
                    double baseSimilarity = commonUsers / Math.sqrt(pop1 * pop2);

                    // 创新点1: 类别加权 - 同一类别相似度提升
                    double categoryBonus = 1.0;
                    if (itemCategoryMap.containsKey(item1) && itemCategoryMap.containsKey(item2)) {
                        if (itemCategoryMap.get(item1).equals(itemCategoryMap.get(item2))) {
                            categoryBonus = 1.5; // 同类别相似度加权
                        }
                    }

                    // 创新点2: 热门降权 - 避免推荐热门商品
                    double hotPenalty = 1.0 / Math.log10(2 + (pop1 + pop2) / 2);

                    similarities.put(item2, baseSimilarity * categoryBonus * hotPenalty);
                }
            }

            similarityMatrix.put(item1, similarities);
        }

        return similarityMatrix;
    }

    /**
     * 计算物品相似度矩阵（加权隐式反馈）
     * userItemScoreMatrix: user -> (item -> implicit score)
     */
    public static Map<Long, Map<Long, Double>> computeItemSimilarityWeighted(
            Map<Long, Map<Long, Double>> userItemScoreMatrix,
            Map<Long, Long> itemCategoryMap) {

        Map<Long, Map<Long, Double>> coOccurrence = new HashMap<>();
        Map<Long, Double> itemNorm = new HashMap<>();

        for (Map<Long, Double> itemScoreMap : userItemScoreMatrix.values()) {
            if (itemScoreMap == null || itemScoreMap.isEmpty()) {
                continue;
            }

            int itemCount = itemScoreMap.size();
            double userPenalty = 1.0 / Math.log(1 + itemCount + EPS);

            for (Map.Entry<Long, Double> left : itemScoreMap.entrySet()) {
                Long itemI = left.getKey();
                double scoreI = Math.max(0.0, left.getValue());
                itemNorm.merge(itemI, scoreI * scoreI, Double::sum);

                for (Map.Entry<Long, Double> right : itemScoreMap.entrySet()) {
                    Long itemJ = right.getKey();
                    if (itemI.equals(itemJ)) {
                        continue;
                    }

                    double scoreJ = Math.max(0.0, right.getValue());
                    double increment = scoreI * scoreJ * userPenalty;

                    coOccurrence
                            .computeIfAbsent(itemI, key -> new HashMap<>())
                            .merge(itemJ, increment, Double::sum);
                }
            }
        }

        Map<Long, Map<Long, Double>> similarityMatrix = new HashMap<>();
        for (Map.Entry<Long, Map<Long, Double>> itemEntry : coOccurrence.entrySet()) {
            Long itemI = itemEntry.getKey();
            Map<Long, Double> similarities = new HashMap<>();

            for (Map.Entry<Long, Double> pair : itemEntry.getValue().entrySet()) {
                Long itemJ = pair.getKey();
                double cij = pair.getValue();

                double normI = Math.sqrt(itemNorm.getOrDefault(itemI, 0.0));
                double normJ = Math.sqrt(itemNorm.getOrDefault(itemJ, 0.0));
                if (normI <= EPS || normJ <= EPS) {
                    continue;
                }

                double similarity = cij / (normI * normJ + EPS);

                if (itemCategoryMap.containsKey(itemI) && itemCategoryMap.containsKey(itemJ)
                        && Objects.equals(itemCategoryMap.get(itemI), itemCategoryMap.get(itemJ))) {
                    similarity *= 1.2;
                }

                similarities.put(itemJ, similarity);
            }

            similarityMatrix.put(itemI, similarities);
        }

        return similarityMatrix;
    }

    /**
     * 为用户生成推荐列表
     * @param userId 用户ID
     * @param userItems 用户已有物品集合
     * @param similarityMatrix 物品相似度矩阵
     * @param itemScores 物品评分数据
     * @param recommendationCount 推荐数量
     * @return 推荐物品列表(按得分排序)
     */
    public static List<Long> recommend(
            Long userId,
            Set<Long> userItems,
            Map<Long, Map<Long, Double>> similarityMatrix,
            Map<Long, Double> itemScores,
            int recommendationCount) {

        Map<Long, Double> itemScoresMap = new HashMap<>();

        // 遍历用户已交互的物品
        for (Long userItem : userItems) {
            Map<Long, Double> similarities = similarityMatrix.getOrDefault(userItem, new HashMap<>());

            // 累加相似度得分
            for (Map.Entry<Long, Double> entry : similarities.entrySet()) {
                Long candidateItem = entry.getKey();
                if (!userItems.contains(candidateItem)) {
                    double score = entry.getValue();
                    // 创新点3: 结合用户评分数据
                    if (itemScores.containsKey(candidateItem)) {
                        score *= (0.7 + 0.3 * itemScores.get(candidateItem));
                    }
                    itemScoresMap.merge(candidateItem, score, Double::sum);
                }
            }
        }

        // 按得分排序并返回Top N
        return itemScoresMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(recommendationCount)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 加权推荐：用户历史行为强度参与打分
     */
    public static List<Long> recommendWeighted(
            Long userId,
            Map<Long, Double> userItemScores,
            Map<Long, Map<Long, Double>> similarityMatrix,
            int recommendationCount) {

        if (userItemScores == null || userItemScores.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> interacted = userItemScores.keySet();
        Map<Long, Double> candidateScore = new HashMap<>();

        for (Map.Entry<Long, Double> interactedItem : userItemScores.entrySet()) {
            Long itemI = interactedItem.getKey();
            double historyWeight = Math.max(0.0, interactedItem.getValue());

            Map<Long, Double> simMap = similarityMatrix.getOrDefault(itemI, Collections.emptyMap());
            for (Map.Entry<Long, Double> simEntry : simMap.entrySet()) {
                Long candidate = simEntry.getKey();
                if (interacted.contains(candidate)) {
                    continue;
                }

                double score = historyWeight * simEntry.getValue();
                candidateScore.merge(candidate, score, Double::sum);
            }
        }

        return candidateScore.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(recommendationCount)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 冷启动策略：基于热门商品和类别相似度
     * @param categoryId 用户偏好类别
     * @param popularItems 热门商品列表
     * @param itemCategoryMap 物品-类别映射
     * @param count 推荐数量
     * @return 冷启动推荐列表
     */
    public static List<Long> coldStartRecommendation(
            Long categoryId,
            List<Long> popularItems,
            Map<Long, Long> itemCategoryMap,
            int count) {

        List<Long> recommendations = new ArrayList<>();

        // 优先推荐同类别热门商品
        for (Long item : popularItems) {
            if (recommendations.size() >= count) break;
            if (categoryId == null || itemCategoryMap.getOrDefault(item, -1L).equals(categoryId)) {
                recommendations.add(item);
            }
        }

        // 如果不够，补充全局热门
        if (recommendations.size() < count) {
            for (Long item : popularItems) {
                if (recommendations.size() >= count) break;
                if (!recommendations.contains(item)) {
                    recommendations.add(item);
                }
            }
        }

        return recommendations;
    }

    /**
     * 计算时间衰减权重
     * @param daysAgo 几天前
     * @return 衰减后的权重
     */
    public static double getTimeDecayWeight(int daysAgo) {
        return Math.pow(TIME_DECAY_FACTOR, Math.min(daysAgo, DECAY_DAYS));
    }
}