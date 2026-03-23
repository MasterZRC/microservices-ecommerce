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

import jakarta.annotation.PostConstruct;
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
    private final IncrementalItemCFService incrementalItemCFService;
    private final OnlineLearningService onlineLearningService;
    private DiversityService diversityService;
    private RecommendationMetricsService metricsService;

    @Value("${services.product.url:http://localhost:8002}")
    private String productServiceUrl;

    @Value("${recommendation.diversity.enabled:true}")
    private boolean diversityEnabled;

    @Value("${recommendation.diversity.max-consecutive-same-category:2}")
    private int maxConsecutiveSameCategory;

    @Value("${recommendation.degrade.enableFallback:true}")
    private boolean enableFallback;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDiversityService(DiversityService diversityService) {
        this.diversityService = diversityService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMetricsService(RecommendationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * 服务启动时初始化用户画像
     */
    @PostConstruct
    public void init() {
        log.info("推荐服务启动，开始初始化...");
        // 启动时预热：构建活跃用户画像
        try {
            List<UserBehavior> recentBehaviors = behaviorMapper.selectList(
                new LambdaQueryWrapper<UserBehavior>()
                    .ge(UserBehavior::getCreateTime, LocalDateTime.now().minusDays(30))
                    .select(UserBehavior::getUserId)
            );
            Set<Long> activeUserIds = recentBehaviors.stream()
                .map(UserBehavior::getUserId)
                .collect(Collectors.toSet());
            log.info("发现{}个活跃用户，开始构建画像...", activeUserIds.size());
            for (Long userId : activeUserIds) {
                try {
                    userProfileService.buildFullProfile(userId);
                } catch (Exception e) {
                    log.warn("构建用户画像失败: userId={}", userId, e);
                }
            }
            log.info("用户画像初始化完成");
        } catch (Exception e) {
            log.warn("初始化用户画像失败: {}", e.getMessage());
        }
    }

    private static final String SIMILARITY_KEY = "recommendation:similarity:" + ItemCFAlgorithm.SIMILARITY_CACHE_VERSION + ":";
    private static final String POPULAR_ITEMS_KEY = "recommendation:popular:";
    private static final String USER_BEHAVIOR_KEY = "recommendation:behavior:";
    private static final String USER_REC_KEY = "recommendation:personal:";
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
        redisTemplate.delete(SIMILARITY_KEY + "all");

        // 更新用户画像标签（增量更新）
        try {
            userProfileService.updateProfileOnBehavior(userId, productId, normalizedBehavior);
        } catch (Exception e) {
            log.warn("更新用户画像失败: userId={}, productId={}, error={}", userId, productId, e.getMessage());
        }

        // 在线学习：记录行为事件（实时反馈到推荐模型）
        try {
            List<Long> exposureItems = candidateRecallService.multiChannelRecall(userId);
            onlineLearningService.recordBehaviorEvent(userId, productId, normalizedBehavior, exposureItems);
        } catch (Exception e) {
            log.warn("在线学习记录失败: userId={}, productId={}, error={}", userId, productId, e.getMessage());
        }

        // 增量ItemCF：记录交互（更新相似度矩阵）
        try {
            double behaviorWeight = BEHAVIOR_WEIGHT.getOrDefault(normalizedBehavior, 1);
            incrementalItemCFService.recordIncrementalInteraction(userId, productId, normalizedBehavior, behaviorWeight);
        } catch (Exception e) {
            log.warn("增量ItemCF记录失败: userId={}, productId={}, error={}", userId, productId, e.getMessage());
        }
    }

    /**
     * 获取个性化推荐（使用多路召回 + DeepFM重排）
     * 
     * 生产级改造：
     * 1. 集成 Prometheus 指标埋点
     * 2. 集成多样性控制（MMR 算法）
     * 3. 集成多级降级策略
     */
    public List<Long> getPersonalizedRecommendations(Long userId, int limit) {
        // 记录请求
        if (metricsService != null) {
            metricsService.recordRequest();
        }

        io.micrometer.core.instrument.Timer.Sample timerSample = metricsService != null ? metricsService.startTimer() : null;

        try {
            // 尝试从缓存获取
            String cacheKey = USER_REC_KEY + userId;
            @SuppressWarnings("unchecked")
            List<Long> cached = (List<Long>) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                List<Long> result = cached.stream().limit(limit).collect(Collectors.toList());
                if (metricsService != null) {
                    metricsService.recordSuccess();
                    metricsService.recordFinalResultSize(result.size());
                    if (timerSample != null) metricsService.stopRecommendationTimer(timerSample);
                }
                return result;
            }

            // 使用多路召回获取候选商品
            List<Long> recommendations = candidateRecallService.multiChannelRecall(userId);

            // 如果召回为空且启用了降级，返回热门商品
            if (recommendations.isEmpty() && enableFallback) {
                log.warn("[降级] userId={} 召回结果为空，返回热门商品", userId);
                if (metricsService != null) {
                    metricsService.recordDegrade("cold_start");
                }
                recommendations = getColdStartRecommendations(limit);
            }

            if (recommendations.isEmpty()) {
                if (metricsService != null) {
                    metricsService.recordFailure();
                    if (timerSample != null) metricsService.stopRecommendationTimer(timerSample);
                }
                return Collections.emptyList();
            }

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
                if (metricsService != null) {
                    metricsService.recordRankRequest();
                }
                io.micrometer.core.instrument.Timer.Sample rankTimer = metricsService != null ? metricsService.startTimer() : null;

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
                        if (metricsService != null) {
                            metricsService.recordRankSuccess();
                        }
                    }
                } catch (Exception e) {
                    log.warn("DeepFM排序失败，使用原始召回结果: {}", e.getMessage());
                    if (metricsService != null) {
                        metricsService.recordRankFailure();
                        metricsService.recordRankFallback();
                    }
                } finally {
                    if (rankTimer != null && metricsService != null) {
                        metricsService.stopRankTimer(rankTimer);
                    }
                }
            }

            // 缓存结果（使用较长的过期时间）
            if (!recommendations.isEmpty()) {
                redisTemplate.opsForValue().set(cacheKey, recommendations, 2, TimeUnit.HOURS);
            }

            // 多样性打散（生产级核心功能）
            if (diversityEnabled && diversityService != null && recommendations.size() > maxConsecutiveSameCategory) {
                List<Long> beforeDiversity = new ArrayList<>(recommendations);
                recommendations = diversityService.shuffleByMultiDimensional(recommendations);
                // 【调试】追踪多样性前后的变化
                Map<Long, Long> diversityItemCategoryMap = candidateRecallService.buildItemCategoryMap();
                Map<Long, Long> beforeCatCount = countCategories(beforeDiversity, diversityItemCategoryMap);
                Map<Long, Long> afterCatCount = countCategories(recommendations, diversityItemCategoryMap);
                log.info("[多样性追踪] userId={}, 多样性前Top3类目={}, 多样性后Top3类目={}", 
                         userId, getTop3Categories(beforeCatCount), getTop3Categories(afterCatCount));
            }

            // 【调试】在缓存和返回前记录最终推荐结果
            if (log.isDebugEnabled()) {
                log.debug("[最终推荐] userId={}, 推荐数量={}, 前10个商品ID={}", 
                         userId, recommendations.size(), 
                         recommendations.stream().limit(10).collect(Collectors.toList()));
            }

            // 记录最终结果
            if (metricsService != null) {
                metricsService.recordSuccess();
                metricsService.recordFinalResultSize(recommendations.size());
                if (timerSample != null) metricsService.stopRecommendationTimer(timerSample);
            }

            return recommendations.stream().limit(limit).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("获取个性化推荐失败: userId={}", userId, e);
            if (metricsService != null) {
                metricsService.recordFailure();
                if (timerSample != null) metricsService.stopRecommendationTimer(timerSample);
            }
            // 降级：返回热门商品
            if (enableFallback) {
                log.info("[降级] 发生异常，返回热门商品作为兜底: userId={}", userId);
                if (metricsService != null) {
                    metricsService.recordDegrade("exception_fallback");
                }
                return getColdStartRecommendations(limit);
            }
            return Collections.emptyList();
        }
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
        @SuppressWarnings("unchecked")
        Map<Long, Map<Long, Double>> cached =
            (Map<Long, Map<Long, Double>>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            return convertToLongKeyMap(cached);
        }

        log.info("[矩阵调试] 缓存未命中，重新计算相似度矩阵...");
        Map<Long, Map<Long, Double>> similarityMatrix =
            ItemCFAlgorithm.computeItemSimilarityWeighted(userItemScoreMatrix, itemCategoryMap);

        log.info("[矩阵调试] 计算完成，矩阵外层key数={}", similarityMatrix.size());

        redisTemplate.opsForValue().set(cacheKey, similarityMatrix, 24, TimeUnit.HOURS);
        return similarityMatrix;
    }

    /**
     * 将反序列化后key为String的Map转换为Long key的Map
     * Redis默认序列化会将Long key转为String，查询时必须做类型对齐
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<Long, Double>> convertToLongKeyMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return new java.util.HashMap<>();
        // 检查第一个key是否是Long类型（已对齐则直接返回）
        Object firstKey = map.keySet().iterator().next();
        if (firstKey instanceof Long) return (Map<Long, Map<Long, Double>>) map;

        // 类型不对齐，需要逐个转换
        Map<Long, Map<Long, Double>> result = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Long outerKey;
            Object key = entry.getKey();
            if (key instanceof String) {
                outerKey = Long.parseLong((String) key);
            } else {
                outerKey = (Long) key;
            }

            Object innerObj = entry.getValue();
            if (innerObj == null) {
                result.put(outerKey, new java.util.HashMap<>());
                continue;
            }
            Map<?, ?> innerMap = (Map<?, ?>) innerObj;
            if (innerMap.isEmpty()) {
                result.put(outerKey, new java.util.HashMap<>());
                continue;
            }
            Object innerFirstKey = innerMap.keySet().iterator().next();
            if (innerFirstKey instanceof Long) {
                result.put(outerKey, (Map<Long, Double>) (Map<?, ?>) innerMap);
            } else {
                Map<Long, Double> convertedInner = new java.util.HashMap<>();
                for (Map.Entry<?, ?> innerEntry : innerMap.entrySet()) {
                    Long innerKey;
                    Object ik = innerEntry.getKey();
                    if (ik instanceof String) {
                        innerKey = Long.parseLong((String) ik);
                    } else {
                        innerKey = (Long) ik;
                    }
                    convertedInner.put(innerKey, (Double) innerEntry.getValue());
                }
                result.put(outerKey, convertedInner);
            }
        }
        log.info("[矩阵调试] String->Long 转换完成，新矩阵外层key数={}", result.size());
        return result;
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

            // user_behavior 无数据时，复用多路召回的兜底逻辑（从商品服务获取）
            if (popularItems.isEmpty()) {
                popularItems = candidateRecallService.recallByPopular(limit * 2);
            }

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
        log.info("推荐候选商品IDs: {}", productIds.subList(0, Math.min(10, productIds.size())));
        List<Map<String, Object>> products = loadProductDetails(productIds, limit);
        log.info("加载后的商品数量: {}, 前5个商品的ID: {}", 
                products.size(), 
                products.stream().limit(5).map(p -> p.get("id")).toList());

        // 收集所有需要的商品ID（包括推荐商品和用户已交互商品）
        Set<Long> allProductIds = new HashSet<>(productIds);
        List<UserBehavior> userBehaviors = behaviorMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .last("LIMIT 500")
        );
        for (UserBehavior b : userBehaviors) {
            allProductIds.add(b.getProductId());
        }

        // 从商品服务批量获取所有商品的类目信息
        // 【修复】优先使用全局类目映射，fallback到按ID查询
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
        if (itemCategoryMap.isEmpty()) {
            itemCategoryMap = buildItemCategoryMapForProducts(new ArrayList<>(allProductIds));
        }
        
        // 统计推荐商品的类目分布
        Map<Long, Long> categoryCount = new HashMap<>();
        for (Map<String, Object> p : products) {
            Object id = p.get("id");
            if (id != null) {
                Long catId = itemCategoryMap.get(Long.valueOf(id.toString()));
                if (catId != null) {
                    categoryCount.merge(catId, 1L, Long::sum);
                }
            }
        }
        log.info("推荐商品类目分布: {}", categoryCount);

        // 获取用户行为特征和商品特征用于 DeepFM 排序
        Map<String, Object> userFeatures = buildUserFeatures(userId);
        Map<String, Map<String, Object>> itemFeatures = buildItemFeaturesForCandidates(productIds);

        // 调用 DeepFM-Attention 排序服务获取 CTR 分数
        Map<Long, Double> deepfmScores = rankClientService.rankWithScores(
            userId, productIds, userFeatures, itemFeatures);

        // 计算推荐理由（保留 ItemCF 用于解释）
        Map<Long, Double> userItemScores = buildUserItemScoreMatrix()
            .getOrDefault(userId, new HashMap<>());
        Map<Long, Map<Long, Double>> globalSimilarityMatrix = computeSimilarityMatrix(
                buildUserItemScoreMatrix(), itemCategoryMap);
        Map<Long, Double> itemScores = getItemScores(userId, itemCategoryMap);
        Map<Long, String> explanations = generateExplanations(
            userId, productIds, itemCategoryMap, itemScores, userItemScores, globalSimilarityMatrix);

        for (Map<String, Object> product : products) {
            Object id = product.get("id");
            if (id != null) {
                Long productId = Long.valueOf(id.toString());
                // 使用 DeepFM-Attention CTR 分数作为主要排序分数
                double score = deepfmScores.getOrDefault(productId, 0.0);
                product.put("recommendation_reason", explanations.getOrDefault(productId, "热门推荐"));
                product.put("score", score);
                product.put("cf_score", itemScores.getOrDefault(productId, 0.0));
            }
        }

        return products;
    }

    /**
     * 根据商品ID列表批量获取类目信息
     */
    private Map<Long, Long> buildItemCategoryMapForProducts(List<Long> productIds) {
        Map<Long, Long> categoryMap = new HashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            return categoryMap;
        }

        try {
            // 使用 GET 请求，通过 ids 参数传递商品ID
            String idsParam = productIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/batch")
                    .queryParam("ids", idsParam)
                    .build()
                    .toUriString();

            // 使用 GET 请求
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> products = (List<Map<String, Object>>) response.get("products");
                if (products != null) {
                    for (Map<String, Object> product : products) {
                        Object idObj = product.get("id");
                        Object categoryIdObj = product.get("categoryId");
                        if (idObj != null && categoryIdObj != null) {
                            try {
                                Long productId = Long.valueOf(idObj.toString());
                                Long categoryId = Long.valueOf(categoryIdObj.toString());
                                categoryMap.put(productId, categoryId);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("批量获取商品类目失败: {}", e.getMessage());
        }

        return categoryMap;
    }

    /**
     * 计算类目亲和度分数
     */
    private double getCategoryAffinity(Long categoryId, Map<Long, Double> userItemScores, Map<Long, Long> itemCategoryMap) {
        if (categoryId == null) return 0.0;
        return userItemScores.entrySet().stream()
                .filter(e -> categoryId.equals(itemCategoryMap.get(e.getKey())))
                .mapToDouble(Map.Entry::getValue)
                .sum();
    }

    /**
     * 获取推荐分数（内部使用）
     * @param userId 用户ID
     * @param itemCategoryMap 商品-类目映射（传入以避免重复构建）
     */
    private Map<Long, Double> getItemScores(Long userId, Map<Long, Long> itemCategoryMap) {
        Map<Long, Map<Long, Double>> userItemScoreMatrix = buildUserItemScoreMatrix();
        Map<Long, Double> userItemScores = userItemScoreMatrix.getOrDefault(userId, new HashMap<>());
        Map<Long, Map<Long, Double>> similarityMatrix = computeSimilarityMatrix(userItemScoreMatrix, itemCategoryMap);
        Map<Long, Double> candidateScore = new HashMap<>();

        // 【调试】统计：有多少个已交互商品有相似邻居
        int itemsWithNeighbors = 0;
        int totalSimEntries = 0;

        for (Map.Entry<Long, Double> interactedItem : userItemScores.entrySet()) {
            Long itemI = interactedItem.getKey();
            double historyWeight = Math.max(0.0, interactedItem.getValue());
            Map<Long, Double> simMap = similarityMatrix.getOrDefault(itemI, Collections.emptyMap());
            totalSimEntries += simMap.size();
            if (!simMap.isEmpty()) itemsWithNeighbors++;

            for (Map.Entry<Long, Double> simEntry : simMap.entrySet()) {
                Long candidate = simEntry.getKey();
                if (!userItemScores.keySet().contains(candidate)) {
                    candidateScore.merge(candidate, historyWeight * simEntry.getValue(), Double::sum);
                }
            }
        }

        log.info("[CF调试] userId={}, 已交互商品数={}, 有相似邻居的商品数={}, 总相似边数={}, 最终候选数={}",
                 userId, userItemScores.size(), itemsWithNeighbors, totalSimEntries, candidateScore.size());

        return candidateScore;
    }

    /**
     * 生成推荐解释
     * @param userId 用户ID
     * @param productIds 推荐商品列表
     * @param itemCategoryMap 商品-类目映射
     * @param itemScores 商品评分
     * @param userItemScores 用户-商品评分矩阵
     * @param similarityMatrix 全局相似度矩阵
     * @return 商品ID -> 推荐理由
     */
    private Map<Long, String> generateExplanations(Long userId, List<Long> productIds,
                                                   Map<Long, Long> itemCategoryMap,
                                                   Map<Long, Double> itemScores,
                                                   Map<Long, Double> userItemScores,
                                                   Map<Long, Map<Long, Double>> similarityMatrix) {
        Map<Long, String> explanations = new LinkedHashMap<>();

        // 获取用户偏好类目和品牌
        List<UserBehavior> userBehaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .orderByDesc(UserBehavior::getCreateTime)
                .last("LIMIT 200")
        );

        Map<Long, Double> categoryScores = new HashMap<>();
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

        for (Long productId : productIds) {
            if (productId == null) continue;

            String reason;
            Long cat = itemCategoryMap.get(productId);
            Double cfScore = itemScores.getOrDefault(productId, 0.0);

            // 策略1：协同过滤分数高 -> 个性化推荐
            if (cfScore > 0) {
                reason = "为你精选推荐";
                explanations.put(productId, reason);
                continue;
            }

            // 策略2：同类目相似商品（检查用户已交互商品与候选商品的相似度）
            boolean hasSimilarity = false;
            for (Long interacted : interactedItems) {
                Map<Long, Double> simMap = similarityMatrix.get(interacted);
                if (simMap != null) {
                    Double sim = simMap.get(productId);
                    if (sim != null && sim > 0) {
                        hasSimilarity = true;
                        break;
                    }
                }
            }
            if (hasSimilarity) {
                reason = "与你近期浏览的商品相似";
                explanations.put(productId, reason);
                continue;
            }

            // 策略3：偏好类目匹配
            if (topCategory != null && cat != null && cat.equals(topCategory)) {
                reason = "符合你偏好的商品类目";
                explanations.put(productId, reason);
                continue;
            }

            // 策略4：热门商品
            reason = "当前热门推荐";
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

        log.info("[商品加载] 开始加载商品详情，请求数量={}", productIds.size());
        
        // 批量获取商品详情
        Map<Long, Map<String, Object>> productInfoMap = getProductInfoMap(productIds);
        log.info("[商品加载] 批量获取成功，商品数量={}", productInfoMap.size());
        
        // 统计缺失的商品
        Set<Long> requestedIds = new HashSet<>(productIds);
        Set<Long> foundIds = new HashSet<>(productInfoMap.keySet());
        requestedIds.removeAll(foundIds);
        if (!requestedIds.isEmpty()) {
            log.warn("[商品加载] 无法获取商品详情的ID: {}", requestedIds);
        }

        List<Map<String, Object>> products = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Long productId : productIds) {
            if (productId == null || !seen.add(productId)) {
                continue;
            }

            Map<String, Object> product = productInfoMap.get(productId);
            if (product != null && product.get("id") != null) {
                products.add(product);
            }

            if (products.size() >= limit) {
                break;
            }
        }
        
        log.info("[商品加载] 加载完成，返回数量={}", products.size());
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

    private Map<Long, Long> countCategories(List<Long> productIds, Map<Long, Long> itemCategoryMap) {
        Map<Long, Long> categoryCount = new HashMap<>();
        for (Long itemId : productIds) {
            Long cat = itemCategoryMap.getOrDefault(itemId, -1L);
            categoryCount.merge(cat, 1L, Long::sum);
        }
        return categoryCount;
    }

    private String getTop3Categories(Map<Long, Long> categoryCount) {
        return categoryCount.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> "类目" + e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
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