package com.ecommerce.recommendation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.recommendation.algorithm.ItemCFAlgorithm;
import com.ecommerce.recommendation.entity.UserBehavior;
import com.ecommerce.recommendation.mapper.UserBehaviorMapper;
import com.ecommerce.recommendation.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecommendationService.class);

    private final UserBehaviorMapper behaviorMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;
    private final CandidateRecallService candidateRecallService;
    private final RankClientService rankClientService;
    private final GrayReleaseService grayReleaseService;
    private final UserProfileService userProfileService;

    @Value("${services.product.url:http://localhost:8002}")
    private String productServiceUrl;

    @Value("${recommendation.diversity.max-consecutive-same-category:2}")
    private int maxConsecutiveSameCategory;

    private static final String SIMILARITY_KEY = "recommendation:similarity:" + ItemCFAlgorithm.SIMILARITY_CACHE_VERSION + ":";
    private static final String POPULAR_ITEMS_KEY = "recommendation:popular:";
    private static final String USER_BEHAVIOR_KEY = "recommendation:behavior:";
    private static final Map<String, Integer> BEHAVIOR_WEIGHT = buildBehaviorWeight();
    private static final double RECENCY_DECAY = 0.94;
    private static final long MAX_DECAY_DAYS = 30;
    private static final long EVAL_RANDOM_SEED = 42L;

    private static final String[] BEHAVIOR_TYPES = {"view", "click", "cart", "favorite", "buy"};

    /**
     * 记录用户行为
     */
    public void recordBehavior(Long userId, Long productId, String behaviorType) {
        String normalizedBehavior = normalizeBehaviorType(behaviorType);

        // 保存到数据库
        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setProductId(productId);
        behavior.setBehaviorType(normalizedBehavior);
        behavior.setCreateTime(LocalDateTime.now());
        behaviorMapper.insert(behavior);

        // 更新Redis缓存
        String key = USER_BEHAVIOR_KEY + userId;
        Set<Object> behaviors = redisTemplate.opsForSet().members(key);
        if (behaviors == null) {
            behaviors = new HashSet<>();
        }
        behaviors.add(productId);
        redisTemplate.opsForSet().add(key, productId);
        redisTemplate.expire(key, 30, TimeUnit.DAYS);

        redisTemplate.delete(POPULAR_ITEMS_KEY + "all");
        redisTemplate.delete("recommendation:personal:" + userId);

        // 更新用户画像标签
        userProfileService.updateProfileOnBehavior(userId, productId, normalizedBehavior);
    }

    /**
     * 获取个性化推荐（使用多路召回 + DeepFM重排）
     */
    public List<Long> getPersonalizedRecommendations(Long userId, int limit) {
        // 尝试从缓存获取
        String cacheKey = "recommendation:personal:" + userId;
        List<Long> cached = (List<Long>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().limit(limit).collect(Collectors.toList());
        }

        // 使用多路召回获取候选商品
        List<Long> recommendations = candidateRecallService.multiChannelRecall(userId);

        // 判断是否启用灰度发布（灰度用户使用 DeepFM 重排）
        boolean useDeepFM = grayReleaseService.isGrayUser(userId);
        String algorithm = useDeepFM ? "deepfm" : "itemcf";

        // 记录曝光埋点
        if (grayReleaseService.isGrayEnabled()) {
            for (int i = 0; i < Math.min(recommendations.size(), 20); i++) {
                grayReleaseService.recordExposure(userId, algorithm, i, recommendations.get(i));
            }
        }

        // 如果是灰度用户且重排已启用，调用 DeepFM 排序服务
        if (useDeepFM && rankClientService.isRankEnabled()) {
            try {
                // 构建用户特征
                Map<String, Object> userFeatures = buildUserFeatures(userId);
                
                // 获取候选商品的详细信息（用于提取特征）
                Map<String, Map<String, Object>> itemFeatures = buildItemFeaturesForCandidates(recommendations);
                
                // 调用排序服务
                List<Long> rankedRecommendations = rankClientService.rank(
                    userId, recommendations, userFeatures, itemFeatures
                );
                
                if (!rankedRecommendations.isEmpty()) {
                    recommendations = rankedRecommendations;
                }
            } catch (Exception e) {
                log.warn("DeepFM排序失败，使用原始召回结果: {}", e.getMessage());
            }
        }

        // 缓存结果
        if (!recommendations.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, recommendations, 1, TimeUnit.HOURS);
        }

        // 类目打散：避免同一类目连续出现超过 maxConsecutiveSameCategory 个
        if (recommendations.size() > maxConsecutiveSameCategory) {
            recommendations = shuffleByCategory(recommendations, buildItemCategoryMap());
        }

        return recommendations;
    }

    /**
     * 构建用户特征（用于DeepFM排序）
     * 从商品服务获取真实的类目和品牌信息
     */
    private Map<String, Object> buildUserFeatures(Long userId) {
        Map<String, Object> features = new HashMap<>();

        // 从数据库获取用户最近的行为
        LocalDateTime now = LocalDateTime.now();

        // 近1天行为统计
        List<UserBehavior> behaviors1d = behaviorMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .ge(UserBehavior::getCreateTime, now.minusDays(1))
        );

        // 近7天行为统计
        List<UserBehavior> behaviors7d = behaviorMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .ge(UserBehavior::getCreateTime, now.minusDays(7))
        );

        // 统计各行为类型数量
        Map<String, Integer> stats1d = new HashMap<>();
        Map<String, Integer> stats7d = new HashMap<>();

        for (UserBehavior behavior : behaviors1d) {
            String type = normalizeBehaviorType(behavior.getBehaviorType());
            stats1d.merge(type, 1, Integer::sum);
        }

        for (UserBehavior behavior : behaviors7d) {
            String type = normalizeBehaviorType(behavior.getBehaviorType());
            stats7d.merge(type, 1, Integer::sum);
        }

        // 填充特征
        features.put("view_1d", stats1d.getOrDefault("view", 0));
        features.put("click_1d", stats1d.getOrDefault("click", 0));
        features.put("cart_1d", stats1d.getOrDefault("cart", 0));
        features.put("buy_1d", stats1d.getOrDefault("buy", 0));

        features.put("view_7d", stats7d.getOrDefault("view", 0));
        features.put("click_7d", stats7d.getOrDefault("click", 0));
        features.put("cart_7d", stats7d.getOrDefault("cart", 0));
        features.put("buy_7d", stats7d.getOrDefault("buy", 0));

        // 计算最后活跃时间
        LocalDateTime lastActive = now.minusDays(30);
        for (UserBehavior behavior : behaviors7d) {
            if (behavior.getCreateTime() != null &&
                behavior.getCreateTime().isAfter(lastActive)) {
                lastActive = behavior.getCreateTime();
            }
        }
        long hoursSinceActive = java.time.Duration.between(lastActive, now).toHours();
        features.put("last_active_hours", (int) hoursSinceActive);

        // 获取真实的商品-类目映射
        Map<Long, Long> itemCategoryMap = candidateRecallService.buildItemCategoryMap();

        // 获取真实的商品-品牌映射
        Map<Long, String> itemBrandMap = buildItemBrandMap();

        // 计算用户偏好类目和品牌（使用真实数据）
        List<Integer> preferCategories = new ArrayList<>();
        List<Integer> preferBrands = new ArrayList<>();

        // 统计类目偏好（购买行为权重最高）
        Map<Long, Double> categoryScores = new HashMap<>();
        Map<Long, Double> brandScores = new HashMap<>();

        for (UserBehavior behavior : behaviors7d) {
            double weight = BEHAVIOR_WEIGHT.getOrDefault(
                normalizeBehaviorType(behavior.getBehaviorType()), 1);

            Long categoryId = itemCategoryMap.get(behavior.getProductId());
            if (categoryId != null) {
                categoryScores.merge(categoryId, weight, Double::sum);
            }

            String brand = itemBrandMap.get(behavior.getProductId());
            if (brand != null && !brand.isEmpty()) {
                long brandHash = (brand.hashCode() & 0x7FFFFFFF) % 100;
                brandScores.merge(brandHash, weight * 0.5, Double::sum);
            }
        }

        // 取Top3偏好
        categoryScores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(3)
            .forEach(e -> preferCategories.add(e.getKey().intValue()));

        brandScores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(2)
            .forEach(e -> preferBrands.add(e.getKey().intValue()));

        features.put("prefer_category", preferCategories);
        features.put("prefer_brand", preferBrands);

        return features;
    }

    /**
     * 构建商品-品牌映射（从商品服务获取）
     */
    private Map<Long, String> buildItemBrandMap() {
        Map<Long, String> brandMap = new HashMap<>();
        try {
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/list")
                    .queryParam("page", 1)
                    .queryParam("pageSize", 1000)
                    .build()
                    .toUriString();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.get("products") instanceof List) {
                List<Map<String, Object>> products = (List<Map<String, Object>>) response.get("products");
                for (Map<String, Object> product : products) {
                    Object idObj = product.get("id");
                    Object brandObj = product.get("brand");
                    if (idObj != null && brandObj != null) {
                        Long productId = Long.valueOf(idObj.toString());
                        String brand = brandObj.toString();
                        brandMap.put(productId, brand);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取商品品牌信息失败: {}", e.getMessage());
        }
        return brandMap;
    }

    /**
     * 为候选商品构建特征（从商品服务获取真实数据）
     */
    private Map<String, Map<String, Object>> buildItemFeaturesForCandidates(List<Long> productIds) {
        Map<String, Map<String, Object>> itemFeatures = new HashMap<>();

        if (productIds == null || productIds.isEmpty()) {
            return itemFeatures;
        }

        Map<Long, Map<String, Object>> productInfoMap = getProductInfoMap(productIds);

        for (Long productId : productIds) {
            Map<String, Object> productInfo = productInfoMap.get(productId);
            if (productInfo == null) {
                // 无法获取商品真实信息时，跳过该商品，不生成任何假特征
                log.warn("无法获取商品 {} 的真实信息，跳过该候选", productId);
                continue;
            }

            Map<String, Object> features = new HashMap<>();

            // 真实类目 ID
            Object categoryIdObj = productInfo.get("categoryId");
            long categoryId = 0;
            if (categoryIdObj != null) {
                try {
                    categoryId = Long.parseLong(categoryIdObj.toString());
                } catch (NumberFormatException ignored) {}
            }

            // 真实品牌
            Object brandObj = productInfo.get("brand");
            long brandHash = 0;
            if (brandObj != null && !brandObj.toString().isBlank()) {
                brandHash = (brandObj.toString().hashCode() & 0x7FFFFFFF) % 100;
            }

            // 真实价格和销量
            Object priceObj = productInfo.get("price");
            Object salesObj = productInfo.get("sales");
            double price = 0;
            int sales = 0;
            if (priceObj != null) {
                try {
                    price = Double.parseDouble(priceObj.toString());
                } catch (NumberFormatException ignored) {}
            }
            if (salesObj != null) {
                try {
                    sales = Integer.parseInt(salesObj.toString());
                } catch (NumberFormatException ignored) {}
            }

            features.put("category_id", (int) categoryId);
            features.put("brand_id", (int) brandHash);
            features.put("price_bucket", (int) (price / 100));
            features.put("sales_bucket", sales / 100);
            features.put("hot_score", (double) sales);
            features.put("price_ratio", price / 1000.0);

            itemFeatures.put(String.valueOf(productId), features);
        }

        return itemFeatures;
    }

    /**
     * 批量获取商品信息（按 ID 精确获取）
     */
    private Map<Long, Map<String, Object>> getProductInfoMap(List<Long> productIds) {
        Map<Long, Map<String, Object>> result = new HashMap<>();

        if (productIds == null || productIds.isEmpty()) {
            return result;
        }

        try {
            // 使用批量接口按 ID 精确获取
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/batch")
                    .build()
                    .toUriString();

            HttpEntity<List<Long>> request = new HttpEntity<>(productIds);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response != null && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> products = (List<Map<String, Object>>) response.getBody().get("products");
                if (products != null) {
                    for (Map<String, Object> product : products) {
                        Object idObj = product.get("id");
                        if (idObj != null) {
                            Long productId = Long.valueOf(idObj.toString());
                            result.put(productId, product);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("批量获取商品信息失败，fallback到逐个查询: {}", e.getMessage());
            // 降级：逐个查询
            for (Long productId : productIds) {
                if (!result.containsKey(productId)) {
                    Map<String, Object> product = getProductDetail(productId);
                    if (product != null && product.get("id") != null) {
                        result.put(productId, product);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 获取个性化推荐（旧版，仅使用 ItemCF，保留用于对比）
     */
    public List<Long> getPersonalizedRecommendationsLegacy(Long userId, int limit) {
        // 尝试从缓存获取
        String cacheKey = "recommendation:personal:legacy:" + userId;
        List<Long> cached = (List<Long>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().limit(limit).collect(Collectors.toList());
        }

        // 构建用户-物品加权矩阵
        Map<Long, Map<Long, Double>> userItemScoreMatrix = buildUserItemScoreMatrix();
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();

        // 获取用户交互权重向量
        Map<Long, Double> userItemScores = userItemScoreMatrix.getOrDefault(userId, new HashMap<>());

        List<Long> recommendations;

        if (userItemScores.isEmpty()) {
            // 冷启动：使用热门推荐
            recommendations = getColdStartRecommendations(limit);
        } else {
            // 计算相似度矩阵
            Map<Long, Map<Long, Double>> similarityMatrix = computeSimilarityMatrix(userItemScoreMatrix, itemCategoryMap);

            // 生成推荐
            recommendations = ItemCFAlgorithm.recommendWeighted(
                userId,
                userItemScores,
                similarityMatrix,
                limit
            );
        }

        // 缓存结果
        if (!recommendations.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, recommendations, 1, TimeUnit.HOURS);
        }

        return recommendations;
    }

    /**
     * 构建用户-物品交互矩阵
     */
    private Map<Long, Set<Long>> buildUserItemMatrix() {
        Map<Long, Set<Long>> matrix = new HashMap<>();
        List<UserBehavior> behaviors = behaviorMapper.selectList(null);

        for (UserBehavior behavior : behaviors) {
            matrix.computeIfAbsent(behavior.getUserId(), k -> new HashSet<>())
                  .add(behavior.getProductId());
        }

        return matrix;
    }

    /**
     * 构建用户-物品加权矩阵
     */
    private Map<Long, Map<Long, Double>> buildUserItemScoreMatrix() {
        Map<Long, Map<Long, Double>> matrix = new HashMap<>();
        List<UserBehavior> behaviors = behaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .orderByDesc(UserBehavior::getCreateTime)
                .last("LIMIT 50000"));

        LocalDateTime now = LocalDateTime.now();
        for (UserBehavior behavior : behaviors) {
            String behaviorType = normalizeBehaviorType(behavior.getBehaviorType());
            double baseWeight = BEHAVIOR_WEIGHT.getOrDefault(behaviorType, 1);
            double recencyWeight = getRecencyWeight(behavior.getCreateTime(), now);
            double finalScore = baseWeight * recencyWeight;

            matrix
                    .computeIfAbsent(behavior.getUserId(), key -> new HashMap<>())
                    .merge(behavior.getProductId(), finalScore, Double::sum);
        }

        return matrix;
    }

    /**
     * 构建物品-类别映射（从商品服务获取）
     */
    private Map<Long, Long> buildItemCategoryMap() {
        return candidateRecallService.buildItemCategoryMap();
    }

    /**
     * 计算相似度矩阵
     */
        private Map<Long, Map<Long, Double>> computeSimilarityMatrix(
            Map<Long, Map<Long, Double>> userItemScoreMatrix,
            Map<Long, Long> itemCategoryMap) {

        String cacheKey = SIMILARITY_KEY + "all";
        Map<Long, Map<Long, Double>> cached = 
            (Map<Long, Map<Long, Double>>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            return cached;
        }

        Map<Long, Map<Long, Double>> similarityMatrix =
            ItemCFAlgorithm.computeItemSimilarityWeighted(userItemScoreMatrix, itemCategoryMap);

        redisTemplate.opsForValue().set(cacheKey, similarityMatrix, 24, TimeUnit.HOURS);
        return similarityMatrix;
    }

    /**
     * 冷启动推荐
     */
    public List<Long> getColdStartRecommendations(int limit) {
        String popKey = POPULAR_ITEMS_KEY + "all";
        List<Long> popularItems = (List<Long>) redisTemplate.opsForValue().get(popKey);

        if (popularItems == null || popularItems.isEmpty()) {
            List<UserBehavior> allBehaviors = behaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                    .orderByDesc(UserBehavior::getCreateTime)
                    .last("LIMIT 20000"));

            Map<Long, Integer> scoreMap = new HashMap<>();
            for (UserBehavior behavior : allBehaviors) {
                int score = BEHAVIOR_WEIGHT.getOrDefault(normalizeBehaviorType(behavior.getBehaviorType()), 1);
                scoreMap.merge(behavior.getProductId(), score, Integer::sum);
            }

            popularItems = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(limit * 2)
                .collect(Collectors.toList());

            if (!popularItems.isEmpty()) {
                redisTemplate.opsForValue().set(popKey, popularItems, 24, TimeUnit.HOURS);
            }
        }

        // 使用算法中的冷启动策略
        return ItemCFAlgorithm.coldStartRecommendation(
            null,
            popularItems != null ? popularItems : new ArrayList<>(),
            buildItemCategoryMap(),
            limit
        );
    }

    /**
     * 获取热门商品
     */
    public List<Long> getPopularItems(int limit) {
        return getColdStartRecommendations(limit);
    }

    public List<Map<String, Object>> getPopularProductDetails(int limit) {
        List<Long> productIds = getPopularItems(limit);
        return loadProductDetails(productIds, limit);
    }

    public List<Map<String, Object>> getPersonalizedProductDetails(Long userId, int limit) {
        List<Long> productIds = getPersonalizedRecommendations(userId, limit);
        List<Map<String, Object>> products = loadProductDetails(productIds, limit);

        // 附加推荐理由
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
        Map<Long, Double> itemScores = getItemScores(userId);
        Map<Long, String> explanations = generateExplanations(userId, productIds, itemCategoryMap, itemScores);

        for (Map<String, Object> product : products) {
            Object id = product.get("id");
            if (id != null) {
                Long productId = Long.valueOf(id.toString());
                product.put("recommendation_reason", explanations.getOrDefault(productId, "热门推荐"));
                product.put("cf_score", itemScores.getOrDefault(productId, 0.0));
            }
        }

        return products;
    }

    /**
     * 获取推荐分数（内部使用）
     */
    private Map<Long, Double> getItemScores(Long userId) {
        Map<Long, Map<Long, Double>> userItemScoreMatrix = buildUserItemScoreMatrix();
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
        Map<Long, Double> userItemScores = userItemScoreMatrix.getOrDefault(userId, new HashMap<>());
        Map<Long, Map<Long, Double>> similarityMatrix = computeSimilarityMatrix(userItemScoreMatrix, itemCategoryMap);
        Map<Long, Double> candidateScore = new HashMap<>();

        for (Map.Entry<Long, Double> interactedItem : userItemScores.entrySet()) {
            Long itemI = interactedItem.getKey();
            double historyWeight = Math.max(0.0, interactedItem.getValue());
            Map<Long, Double> simMap = similarityMatrix.getOrDefault(itemI, Collections.emptyMap());
            for (Map.Entry<Long, Double> simEntry : simMap.entrySet()) {
                Long candidate = simEntry.getKey();
                if (!userItemScores.keySet().contains(candidate)) {
                    candidateScore.merge(candidate, historyWeight * simEntry.getValue(), Double::sum);
                }
            }
        }
        return candidateScore;
    }

    /**
     * 生成推荐解释
     * @param userId 用户ID
     * @param productIds 推荐商品列表
     * @param itemCategoryMap 商品-类目映射
     * @param itemScores 商品评分
     * @return 商品ID -> 推荐理由
     */
    private Map<Long, String> generateExplanations(Long userId, List<Long> productIds,
                                                    Map<Long, Long> itemCategoryMap,
                                                    Map<Long, Double> itemScores) {
        Map<Long, String> explanations = new LinkedHashMap<>();

        // 获取用户偏好类目和品牌
        List<UserBehavior> userBehaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .orderByDesc(UserBehavior::getCreateTime)
                .last("LIMIT 200")
        );

        Map<Long, Double> categoryScores = new HashMap<>();
        Map<Long, Double> brandScores = new HashMap<>();
        Set<Long> interactedItems = new HashSet<>();

        for (UserBehavior b : userBehaviors) {
            interactedItems.add(b.getProductId());
            String type = normalizeBehaviorType(b.getBehaviorType());
            double weight = BEHAVIOR_WEIGHT.getOrDefault(type, 1);

            Long cat = itemCategoryMap.get(b.getProductId());
            if (cat != null) {
                categoryScores.merge(cat, weight, Double::sum);
            }
        }

        // 找用户最喜欢的类目
        Long topCategory = categoryScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // 获取相似商品信息
        Map<Long, Set<Long>> userItemMatrix = new HashMap<>();
        userItemMatrix.put(userId, interactedItems);
        Map<Long, Map<Long, Double>> similarityMatrix =
                ItemCFAlgorithm.computeItemSimilarity(userItemMatrix, itemCategoryMap);

        for (Long productId : productIds) {
            if (productId == null) continue;

            String reason;
            Long cat = itemCategoryMap.get(productId);
            Double score = itemScores.getOrDefault(productId, 0.0);

            // 策略1：同类目相似商品
            Map<Long, Double> similarItems = similarityMatrix.get(productId);
            if (similarItems != null && !similarItems.isEmpty()) {
                Long mostSimilar = similarItems.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);

                if (mostSimilar != null && cat != null && cat.equals(itemCategoryMap.get(mostSimilar))) {
                    reason = "与你近期浏览的商品相似";
                    explanations.put(productId, reason);
                    continue;
                }
            }

            // 策略2：偏好类目匹配
            if (topCategory != null && cat != null && cat.equals(topCategory)) {
                reason = "符合你偏好的商品类目";
                explanations.put(productId, reason);
                continue;
            }

            // 策略3：热门商品
            if (score == 0.0 || itemScores.isEmpty()) {
                reason = "当前热门推荐";
                explanations.put(productId, reason);
                continue;
            }

            // 策略4：协同过滤推荐
            reason = "为你精选推荐";
            explanations.put(productId, reason);
        }

        return explanations;
    }

    public Map<String, Object> compareBaselines(int topK, int sampleUsers) {
        int finalTopK = Math.max(1, topK);
        int finalSampleUsers = Math.max(1, sampleUsers);

        List<UserBehavior> allBehaviors = behaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .orderByAsc(UserBehavior::getCreateTime)
                .last("LIMIT 100000"));

        if (allBehaviors.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("topK", finalTopK);
            empty.put("sampleUsers", finalSampleUsers);
            empty.put("usersEvaluated", 0);
            empty.put("message", "行为数据不足，无法评估");
            return empty;
        }

        List<EvalSample> samples = buildEvaluationSamples(allBehaviors, finalSampleUsers);
        if (samples.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("topK", finalTopK);
            empty.put("sampleUsers", finalSampleUsers);
            empty.put("usersEvaluated", 0);
            empty.put("message", "有效样本不足（需用户存在可留出的测试商品）");
            return empty;
        }

        Map<Long, Set<Long>> trainBinaryMatrix = new HashMap<>();
        Map<Long, Map<Long, Double>> trainWeightedMatrix = new HashMap<>();
        Map<Long, Integer> globalPopularity = new HashMap<>();

        for (EvalSample sample : samples) {
            trainBinaryMatrix.put(sample.userId(), sample.trainItems());
            trainWeightedMatrix.put(sample.userId(), sample.trainScores());

            for (Map.Entry<Long, Double> entry : sample.trainScores().entrySet()) {
                globalPopularity.merge(entry.getKey(), Math.max(1, (int) Math.round(entry.getValue())), Integer::sum);
            }
        }

        List<Long> popularRanking = globalPopularity.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
        Map<Long, Map<Long, Double>> binarySimilarity = ItemCFAlgorithm.computeItemSimilarity(trainBinaryMatrix, itemCategoryMap);
        Map<Long, Map<Long, Double>> weightedSimilarity = ItemCFAlgorithm.computeItemSimilarityWeighted(trainWeightedMatrix, itemCategoryMap);

        Metrics popularMetrics = new Metrics();
        Metrics binaryMetrics = new Metrics();
        Metrics weightedMetrics = new Metrics();

        for (EvalSample sample : samples) {
            List<Long> popularRec = recommendPopularExcluding(popularRanking, sample.trainItems(), finalTopK);
            List<Long> binaryRec = ItemCFAlgorithm.recommend(
                    sample.userId(),
                    sample.trainItems(),
                    binarySimilarity,
                    Collections.emptyMap(),
                    finalTopK
            );
            List<Long> weightedRec = ItemCFAlgorithm.recommendWeighted(
                    sample.userId(),
                    sample.trainScores(),
                    weightedSimilarity,
                    finalTopK
            );

            popularMetrics.add(popularRec, sample.testItem(), finalTopK);
            binaryMetrics.add(binaryRec, sample.testItem(), finalTopK);
            weightedMetrics.add(weightedRec, sample.testItem(), finalTopK);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topK", finalTopK);
        result.put("sampleUsers", finalSampleUsers);
        result.put("usersEvaluated", samples.size());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("popular", popularMetrics.toMap(samples.size()));
        metrics.put("itemCfBinary", binaryMetrics.toMap(samples.size()));
        metrics.put("itemCfWeighted", weightedMetrics.toMap(samples.size()));

        result.put("metrics", metrics);
        return result;
    }

    private List<Map<String, Object>> loadProductDetails(List<Long> productIds, int limit) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> products = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Long productId : productIds) {
            if (productId == null || !seen.add(productId)) {
                continue;
            }

            Map<String, Object> product = getProductDetail(productId);
            if (product != null && product.get("id") != null) {
                products.add(product);
            }

            if (products.size() >= limit) {
                break;
            }
        }

        return products;
    }

    private Map<String, Object> getProductDetail(Long productId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/{id}")
                    .buildAndExpand(productId)
                    .toUriString();

            @SuppressWarnings("unchecked")
            Map<String, Object> product = restTemplate.getForObject(url, Map.class);
            return product;
        } catch (Exception exception) {
            log.warn("获取商品详情失败: productId={}", productId, exception);
            return null;
        }
    }

    /**
     * 类目打散：确保同一类目商品不会连续出现超过指定数量
     * 使用贪心算法，将商品分配到不同位置，保持整体相关性损失最小
     */
    private List<Long> shuffleByCategory(List<Long> ordered, Map<Long, Long> itemCategoryMap) {
        if (ordered == null || ordered.size() <= maxConsecutiveSameCategory || itemCategoryMap == null || itemCategoryMap.isEmpty()) {
            return ordered;
        }

        // 待处理的商品（因类目重复而被延迟）
        List<Long> pending = new ArrayList<>();
        List<Long> result = new ArrayList<>(ordered.size());
        int consecutiveCount = 0;
        Long lastCategory = null;

        for (Long itemId : ordered) {
            Long cat = itemCategoryMap.getOrDefault(itemId, -1L);

            if (cat.equals(lastCategory)) {
                consecutiveCount++;
            } else {
                consecutiveCount = 1;
                lastCategory = cat;
            }

            if (consecutiveCount > maxConsecutiveSameCategory) {
                // 延迟该商品，先尝试从 pending 中找一个不同类目的插入
                Long replacement = null;
                for (Long p : pending) {
                    Long pCat = itemCategoryMap.getOrDefault(p, -1L);
                    if (!pCat.equals(lastCategory)) {
                        replacement = p;
                        break;
                    }
                }
                if (replacement != null) {
                    pending.remove(replacement);
                    result.add(replacement);
                    consecutiveCount = 1;
                    lastCategory = itemCategoryMap.getOrDefault(replacement, -1L);
                }
                pending.add(itemId);
            } else {
                result.add(itemId);
            }
        }

        // 末尾追加前，尝试将 pending 分散插入结果末尾的空隙中
        if (!pending.isEmpty()) {
            result.addAll(pending);
            // 如果末尾仍有类目重复，进行局部交换
            result = fixTrailingDuplicates(result, itemCategoryMap);
        }
        return result;
    }

    /**
     * 修复末尾连续类目重复：将末尾同类商品与前面不同类商品交换
     */
    private List<Long> fixTrailingDuplicates(List<Long> list, Map<Long, Long> itemCategoryMap) {
        if (list.size() <= 2) return list;

        int n = list.size();
        int start = Math.max(0, n - 5); // 只检查末尾5个

        for (int i = n - 1; i > start; i--) {
            Long cat = itemCategoryMap.getOrDefault(list.get(i), -1L);
            Long prevCat = itemCategoryMap.getOrDefault(list.get(i - 1), -1L);
            if (cat.equals(prevCat)) {
                // 找前面最近的不同类商品进行交换
                for (int j = i - 2; j >= 0; j--) {
                    Long swapCat = itemCategoryMap.getOrDefault(list.get(j), -1L);
                    if (!swapCat.equals(cat)) {
                        // 交换位置
                        list.set(i, list.set(j, list.get(i)));
                        break;
                    }
                }
            }
        }
        return list;
    }

    /**
     * 刷新推荐缓存
     */
    public void refreshRecommendationCache() {
        redisTemplate.delete(redisTemplate.keys("recommendation:*"));
        log.info("推荐缓存已刷新");
    }

    private String normalizeBehaviorType(String behaviorType) {
        if (behaviorType == null || behaviorType.isBlank()) {
            return "view";
        }
        String normalized = behaviorType.trim().toLowerCase(Locale.ROOT);
        return BEHAVIOR_WEIGHT.containsKey(normalized) ? normalized : "view";
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
        return Math.pow(RECENCY_DECAY, days);
    }

    private List<EvalSample> buildEvaluationSamples(List<UserBehavior> allBehaviors, int sampleUsers) {
        Map<Long, List<UserBehavior>> behaviorByUser = allBehaviors.stream()
                .filter(item -> item.getUserId() != null && item.getProductId() != null)
                .collect(Collectors.groupingBy(UserBehavior::getUserId));

        List<EvalSample> samples = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<Long, List<UserBehavior>> entry : behaviorByUser.entrySet()) {
            Long userId = entry.getKey();
            List<UserBehavior> userBehaviors = entry.getValue();
            if (userBehaviors == null || userBehaviors.size() < 2) {
                continue;
            }

            userBehaviors.sort(Comparator.comparing(UserBehavior::getCreateTime, Comparator.nullsFirst(LocalDateTime::compareTo)));

            Map<Long, Integer> freqMap = new HashMap<>();
            for (UserBehavior behavior : userBehaviors) {
                freqMap.merge(behavior.getProductId(), 1, Integer::sum);
            }

            int testIndex = -1;
            for (int i = userBehaviors.size() - 1; i >= 0; i--) {
                Long productId = userBehaviors.get(i).getProductId();
                if (freqMap.getOrDefault(productId, 0) == 1) {
                    testIndex = i;
                    break;
                }
            }

            if (testIndex < 0) {
                continue;
            }

            UserBehavior testBehavior = userBehaviors.get(testIndex);
            Map<Long, Double> trainScores = new HashMap<>();
            Set<Long> trainItems = new HashSet<>();

            for (int i = 0; i < userBehaviors.size(); i++) {
                if (i == testIndex) {
                    continue;
                }
                UserBehavior behavior = userBehaviors.get(i);
                String type = normalizeBehaviorType(behavior.getBehaviorType());
                double baseWeight = BEHAVIOR_WEIGHT.getOrDefault(type, 1);
                double recencyWeight = getRecencyWeight(behavior.getCreateTime(), now);
                double finalScore = baseWeight * recencyWeight;

                trainItems.add(behavior.getProductId());
                trainScores.merge(behavior.getProductId(), finalScore, Double::sum);
            }

            if (trainItems.isEmpty() || trainItems.contains(testBehavior.getProductId())) {
                continue;
            }

            samples.add(new EvalSample(userId, trainItems, trainScores, testBehavior.getProductId()));
        }

        if (samples.size() > sampleUsers) {
            Collections.shuffle(samples, new Random(EVAL_RANDOM_SEED));
            return new ArrayList<>(samples.subList(0, sampleUsers));
        }
        return samples;
    }

    private List<Long> recommendPopularExcluding(List<Long> popularRanking, Set<Long> seenItems, int topK) {
        List<Long> result = new ArrayList<>(topK);
        for (Long itemId : popularRanking) {
            if (seenItems.contains(itemId)) {
                continue;
            }
            result.add(itemId);
            if (result.size() >= topK) {
                break;
            }
        }
        return result;
    }

    private record EvalSample(Long userId, Set<Long> trainItems, Map<Long, Double> trainScores, Long testItem) {
    }

    private static class Metrics {
        private double precision;
        private double recall;
        private double ndcg;
        private double hitRate;

        void add(List<Long> recommendations, Long truthItem, int topK) {
            int rank = -1;
            for (int i = 0; i < recommendations.size(); i++) {
                if (Objects.equals(recommendations.get(i), truthItem)) {
                    rank = i + 1;
                    break;
                }
            }

            if (rank > 0) {
                hitRate += 1.0;
                recall += 1.0;
                precision += 1.0 / topK;
                ndcg += 1.0 / (Math.log(rank + 1) / Math.log(2));
            }
        }

        Map<String, Object> toMap(int totalUsers) {
            int divisor = Math.max(totalUsers, 1);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("precisionAtK", round4(precision / divisor));
            map.put("recallAtK", round4(recall / divisor));
            map.put("ndcgAtK", round4(ndcg / divisor));
            map.put("hitRate", round4(hitRate / divisor));
            return map;
        }

        private double round4(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }
    }
}