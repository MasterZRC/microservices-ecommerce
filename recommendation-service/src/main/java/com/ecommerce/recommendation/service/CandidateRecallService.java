package com.ecommerce.recommendation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.recommendation.algorithm.ContentBasedAlgorithm;
import com.ecommerce.recommendation.algorithm.ItemCFAlgorithm;
import com.ecommerce.recommendation.entity.UserBehavior;
import com.ecommerce.recommendation.mapper.UserBehaviorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 多路召回服务
 * - recall_cf: ItemCF 召回
 * - recall_popular: 热门召回
 * - recall_category: 同类目召回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateRecallService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CandidateRecallService.class);

    private final UserBehaviorMapper behaviorMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;

    @Value("${services.product.url:http://localhost:8002}")
    private String productServiceUrl;

    @Value("${recommendation.recall.cf-count:80}")
    private int cfRecallCount;

    @Value("${recommendation.recall.popular-count:40}")
    private int popularRecallCount;

    @Value("${recommendation.recall.category-count:40}")
    private int categoryRecallCount;

    @Value("${recommendation.recall.content-count:30}")
    private int contentRecallCount;

    @Value("${recommendation.recall.max-pool-size:120}")
    private int maxPoolSize;

    @Value("${recommendation.popular.time-decay-factor:0.95}")
    private double popularTimeDecayFactor;

    @Value("${recommendation.similarity-cache-version:v1}")
    private String similarityCacheVersion;

    private static final String POPULAR_ITEMS_KEY = "recommendation:popular:";
    private static final String ITEM_CATEGORY_KEY = "recommendation:item:category:";
    private static final String ITEM_FEATURE_KEY = "recommendation:item:features:";
    private static final Map<String, Integer> BEHAVIOR_WEIGHT = buildBehaviorWeight();
    private static final long MAX_DECAY_DAYS = 30;

    /**
     * 执行多路召回，返回合并后的候选商品列表
     * 召回渠道：
     * - recall_cf: ItemCF 协同过滤
     * - recall_popular: 热门召回
     * - recall_category: 同类目召回
     * - recall_content: 基于标题 TF-IDF 的内容相似度召回（冷启动专用）
     */
    public List<Long> multiChannelRecall(Long userId) {
        List<Long> cfCandidates = recallByItemCF(userId, cfRecallCount);
        List<Long> popularCandidates = recallByPopular(popularRecallCount);
        List<Long> categoryCandidates = recallByCategory(userId, categoryRecallCount);

        // 合并去重
        Set<Long> recallPool = new LinkedHashSet<>();
        recallPool.addAll(cfCandidates);
        recallPool.addAll(popularCandidates);
        recallPool.addAll(categoryCandidates);

        // 移除用户已交互的商品
        Set<Long> userInteracted = getUserInteractedItems(userId);
        recallPool.removeAll(userInteracted);

        // 如果召回结果为空，使用热门商品兜底
        if (recallPool.isEmpty()) {
            log.info("多路召回结果为空，使用热门商品兜底");
            List<Long> fallback = getColdStartFallback(maxPoolSize);
            if (fallback != null && !fallback.isEmpty()) {
                return fallback;
            }
            return Collections.emptyList();
        }

        // 限制候选池大小
        List<Long> result = recallPool.stream().limit(maxPoolSize).collect(Collectors.toList());

        // 双重保障：如果最终结果为空，强制使用冷启动兜底
        if (result.isEmpty()) {
            log.warn("所有召回渠道均无结果，返回空列表");
            List<Long> fallback = getColdStartFallback(maxPoolSize);
            return (fallback != null && !fallback.isEmpty()) ? fallback : Collections.emptyList();
        }

        return result;
    }

    /**
     * 冷启动兜底：始终使用真实热门商品，不生成任何假数据
     */
    private List<Long> getColdStartFallback(int limit) {
        // 唯一兜底来源：真实热门商品
        List<Long> popularItems = recallByPopular(limit);
        if (popularItems != null && !popularItems.isEmpty()) {
            log.info("使用热门商品兜底，返回 {} 个商品", popularItems.size());
            return popularItems;
        }

        // 热门也没有 → 返回空列表，让上层服务决定如何处理
        // 禁止生成任何假商品ID
        log.warn("所有召回渠道均无结果，返回空列表，不生成假数据");
        return Collections.emptyList();
    }

    /**
     * 召回方式1：ItemCF 协同过滤召回
     */
    public List<Long> recallByItemCF(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }

        Map<Long, Map<Long, Double>> userItemScoreMatrix = buildUserItemScoreMatrix();
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();

        Map<Long, Double> userItemScores = userItemScoreMatrix.getOrDefault(userId, new HashMap<>());
        if (userItemScores.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Map<Long, Double>> similarityMatrix = computeSimilarityMatrix(userItemScoreMatrix, itemCategoryMap);

        return ItemCFAlgorithm.recommendWeighted(
            userId,
            userItemScores,
            similarityMatrix,
            limit
        );
    }

    /**
     * 召回方式2：热门召回（带时间衰减）
     * 近期行为权重更高：score = baseWeight * decayFactor^(daysAgo)
     */
    public List<Long> recallByPopular(int limit) {
        // 缓存键包含时间衰减因子版本，确保配置变更后缓存失效
        String popKey = POPULAR_ITEMS_KEY + "decay-" + popularTimeDecayFactor + ":all";
        List<Long> cached = (List<Long>) redisTemplate.opsForValue().get(popKey);
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().limit(limit).collect(Collectors.toList());
        }

        List<UserBehavior> allBehaviors = behaviorMapper.selectList(
            new LambdaQueryWrapper<UserBehavior>()
                .orderByDesc(UserBehavior::getCreateTime)
                .last("LIMIT 20000")
        );

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Double> scoreMap = new HashMap<>();
        for (UserBehavior behavior : allBehaviors) {
            String type = normalizeBehaviorType(behavior.getBehaviorType());
            double baseWeight = BEHAVIOR_WEIGHT.getOrDefault(type, 1);
            double recencyWeight = getRecencyWeight(behavior.getCreateTime(), now);
            double finalScore = baseWeight * recencyWeight;
            scoreMap.merge(behavior.getProductId(), finalScore, Double::sum);
        }

        List<Long> popularItems = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(limit * 2)
                .collect(Collectors.toList());

        if (!popularItems.isEmpty()) {
            redisTemplate.opsForValue().set(popKey, popularItems, 1, TimeUnit.HOURS);
        }

        return popularItems.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * 召回方式3：同类目召回（基于用户历史偏好类目）
     */
    public List<Long> recallByCategory(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }

        // 获取用户偏好类目
        Long preferredCategoryId = getUserPreferredCategory(userId);
        if (preferredCategoryId == null) {
            return Collections.emptyList();
        }

        // 获取该类目下的热门商品
        List<Long> categoryProducts = getCategoryPopularProducts(preferredCategoryId, limit);
        return categoryProducts;
    }

    // ============================================================
    //  召回方式4：基于商品标题 TF-IDF 的内容相似度召回
    // ============================================================

    /**
     * 召回方式4：基于商品标题 TF-IDF 的内容相似度召回（冷启动专用）
     *
     * 当用户历史行为数据稀少（协同过滤效果差）时，
     * 根据用户浏览商品的标题语义，找出内容上最相似的新商品。
     *
     * 核心逻辑：
     * 1. 拉取商品标题文本
     * 2. 构建 TF-IDF 向量库（全局预计算）
     * 3. 对用户历史商品做向量聚合（MM 池化）
     * 4. 计算候选商品与用户画像的余弦相似度
     * 5. 返回 Top-K 最相似商品
     *
     * @param userId 用户 ID
     * @param limit 返回数量上限
     * @return 与用户兴趣最相似的新商品 ID 列表
     */
    public List<Long> recallByContent(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }

        // ① 获取用户历史浏览商品（用于提取语义）
        List<Long> userHistory = getUserRecentViewedItems(userId, 50);
        if (userHistory.isEmpty()) {
            log.debug("用户 {} 无浏览历史，跳过内容召回", userId);
            return Collections.emptyList();
        }

        // ② 从 Redis 缓存获取 TF-IDF 向量库（预计算，每小时更新一次）
        Map<Long, ContentBasedAlgorithm.TextVector> vectors = loadContentVectors();
        if (vectors.isEmpty()) {
            log.warn("商品 TF-IDF 向量库为空，内容召回跳过");
            return Collections.emptyList();
        }

        // ③ 获取候选商品池（从热门商品中选择，减少计算量）
        List<Long> candidatePool = recallByPopular(limit * 3);
        if (candidatePool.isEmpty()) {
            return Collections.emptyList();
        }

        // ④ 执行内容相似度召回
        List<Long> result = ContentBasedAlgorithm.recommendByContentSimilarity(
            userHistory,
            candidatePool,
            vectors,
            limit
        );

        if (!result.isEmpty()) {
            log.info("内容召回完成：用户 {} 从 {} 个候选中返回 {} 个相似商品",
                    userId, candidatePool.size(), result.size());

            // ⑤ 记录关键词（用于可解释性）
            List<String> topKeywords = ContentBasedAlgorithm.extractTopKeywords(
                    userHistory, vectors, 10);
            if (!topKeywords.isEmpty()) {
                log.debug("用户 {} 兴趣关键词: {}", userId, String.join(", ", topKeywords));
            }
        }

        return result;
    }

    /**
     * 加载商品 TF-IDF 向量库（从 Redis 缓存）
     * 缓存命中失败时自动构建并写入 Redis
     */
    private Map<Long, ContentBasedAlgorithm.TextVector> loadContentVectors() {
        String cacheKey = "recommendation:content:tfidf:vectors";
        try {
            @SuppressWarnings("unchecked")
            Map<Long, Map<String, Double>> cached = (Map<Long, Map<String, Double>>)
                    redisTemplate.opsForValue().get(cacheKey);

            if (cached != null && !cached.isEmpty()) {
                Map<Long, ContentBasedAlgorithm.TextVector> result = new HashMap<>();
                for (Map.Entry<Long, Map<String, Double>> entry : cached.entrySet()) {
                    result.put(entry.getKey(),
                            new ContentBasedAlgorithm.TextVector(entry.getKey(), entry.getValue()));
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("加载 TF-IDF 向量缓存失败，将重新构建: {}", e.getMessage());
        }

        // 缓存未命中：重新构建向量库
        return rebuildContentVectors(cacheKey);
    }

    /**
     * 从 product-service 拉取商品标题，构建 TF-IDF 向量库并缓存
     */
    private synchronized Map<Long, ContentBasedAlgorithm.TextVector> rebuildContentVectors(String cacheKey) {
        try {
            // 从 product-service 获取所有商品（分页，每页 500）
            Map<Long, String> itemTitles = new LinkedHashMap<>();
            int page = 1;
            int pageSize = 500;
            boolean hasMore = true;

            while (hasMore) {
                String url = UriComponentsBuilder
                        .fromHttpUrl(productServiceUrl + "/api/product/list")
                        .queryParam("page", page)
                        .queryParam("pageSize", pageSize)
                        .toUriString();

                @SuppressWarnings("unchecked")
                Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
                if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> products = (List<Map<String, Object>>) resp.get("products");
                if (products == null || products.isEmpty()) {
                    break;
                }

                for (Map<String, Object> product : products) {
                    Object idObj = product.get("id");
                    Object nameObj = product.get("name");
                    if (idObj != null && nameObj != null) {
                        long itemId = Long.parseLong(idObj.toString());
                        String title = nameObj.toString().trim();
                        if (!title.isEmpty()) {
                            itemTitles.put(itemId, title);
                        }
                    }
                }

                if (products.size() < pageSize) {
                    hasMore = false;
                } else {
                    page++;
                }

                // 最多拉取 3000 个商品（避免内存开销）
                if (itemTitles.size() >= 3000) {
                    break;
                }
            }

            if (itemTitles.size() < 10) {
                log.warn("商品标题数量过少（{}），跳过 TF-IDF 构建", itemTitles.size());
                return Collections.emptyMap();
            }

            log.info("开始构建 TF-IDF 向量库，商品数量: {}", itemTitles.size());

            // 计算文档频率
            Map<String, Integer> docFreq = ContentBasedAlgorithm.computeDocumentFrequency(itemTitles);

            // 构建 TF-IDF 向量
            Map<Long, ContentBasedAlgorithm.TextVector> vectors =
                    ContentBasedAlgorithm.buildTfidfVectors(itemTitles, docFreq, itemTitles.size());

            if (!vectors.isEmpty()) {
                // 转换为可序列化格式存入 Redis
                Map<Long, Map<String, Double>> serializable = new HashMap<>();
                for (Map.Entry<Long, ContentBasedAlgorithm.TextVector> entry : vectors.entrySet()) {
                    serializable.put(entry.getKey(), new HashMap<>(entry.getValue().tfidf));
                }
                redisTemplate.opsForValue().set(cacheKey, serializable, 1, TimeUnit.HOURS);
                log.info("TF-IDF 向量库构建完成，共 {} 个商品，已缓存 1 小时", vectors.size());
            }

            return vectors;

        } catch (Exception e) {
            log.error("构建 TF-IDF 向量库失败: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 获取用户最近浏览的商品 ID 列表
     */
    private List<Long> getUserRecentViewedItems(Long userId, int limit) {
        List<UserBehavior> behaviors = behaviorMapper.selectList(
                new LambdaQueryWrapper<UserBehavior>()
                        .eq(UserBehavior::getUserId, userId)
                        .in(UserBehavior::getBehaviorType, Arrays.asList("view", "click"))
                        .orderByDesc(UserBehavior::getCreateTime)
                        .last("LIMIT " + limit)
        );

        return behaviors.stream()
                .map(UserBehavior::getProductId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取用户偏好类目
     */
    private Long getUserPreferredCategory(Long userId) {
        List<UserBehavior> behaviors = behaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .orderByDesc(UserBehavior::getCreateTime)
                .last("LIMIT 500"));

        Map<Long, Double> categoryScore = new HashMap<>();
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();

        LocalDateTime now = LocalDateTime.now();
        for (UserBehavior behavior : behaviors) {
            Long categoryId = itemCategoryMap.get(behavior.getProductId());
            if (categoryId == null) continue;

            String behaviorType = normalizeBehaviorType(behavior.getBehaviorType());
            double baseWeight = BEHAVIOR_WEIGHT.getOrDefault(behaviorType, 1);
            double recencyWeight = getRecencyWeight(behavior.getCreateTime(), now);
            double score = baseWeight * recencyWeight;

            categoryScore.merge(categoryId, score, Double::sum);
        }

        return categoryScore.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 获取类目热门商品
     */
    private List<Long> getCategoryPopularProducts(Long categoryId, int limit) {
        List<Long> allPopular = recallByPopular(limit * 3);
        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();

        return allPopular.stream()
                .filter(itemId -> {
                    Long itemCategory = itemCategoryMap.get(itemId);
                    return itemCategory != null && itemCategory.equals(categoryId);
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户已交互的商品
     */
    private Set<Long> getUserInteractedItems(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        List<UserBehavior> behaviors = behaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .select(UserBehavior::getProductId));

        return behaviors.stream()
                .map(UserBehavior::getProductId)
                .collect(Collectors.toSet());
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
    public Map<Long, Long> buildItemCategoryMap() {
        String cacheKey = ITEM_CATEGORY_KEY + "all";
        Map<Long, Long> cached = (Map<Long, Long>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            return cached;
        }

        Map<Long, Long> categoryMap = new HashMap<>();
        try {
            // 调用商品服务获取商品列表（含类目信息）
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
                    Object categoryIdObj = product.get("categoryId");
                    if (idObj != null && categoryIdObj != null) {
                        Long productId = Long.valueOf(idObj.toString());
                        Long categoryId = Long.valueOf(categoryIdObj.toString());
                        categoryMap.put(productId, categoryId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取商品类目信息失败，使用空映射", e);
        }

        if (!categoryMap.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, categoryMap, 1, TimeUnit.HOURS);
        }

        return categoryMap;
    }

    /**
     * 构建完整商品特征缓存（包含类目、品牌、价格、销量）
     * 供 OnlineLearningService 增量学习使用真实特征
     */
    public Map<Long, Map<String, Object>> buildFullItemFeatureMap() {
        String cacheKey = ITEM_FEATURE_KEY + "all";
        @SuppressWarnings("unchecked")
        Map<Long, Map<String, Object>> cached =
                (Map<Long, Map<String, Object>>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Map<Long, Map<String, Object>> featureMap = new HashMap<>();
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
                    if (idObj == null) continue;
                    Long productId = Long.valueOf(idObj.toString());

                    Map<String, Object> feat = new HashMap<>();

                    // 类目
                    Object categoryIdObj = product.get("categoryId");
                    feat.put("category_id", categoryIdObj != null
                            ? Integer.parseInt(categoryIdObj.toString()) : 0);

                    // 品牌哈希
                    Object brandObj = product.get("brand");
                    int brandHash = 0;
                    if (brandObj != null && !brandObj.toString().isBlank()) {
                        brandHash = (brandObj.toString().hashCode() & 0x7FFFFFFF) % 100;
                    }
                    feat.put("brand_id", brandHash);

                    // 价格分桶
                    Object priceObj = product.get("price");
                    double price = 0;
                    if (priceObj != null) {
                        try {
                            price = Double.parseDouble(priceObj.toString());
                        } catch (NumberFormatException ignored) {}
                    }
                    feat.put("price_bucket", (int) (price / 100));

                    // 销量分桶
                    Object salesObj = product.get("sales");
                    int sales = 0;
                    if (salesObj != null) {
                        try {
                            sales = Integer.parseInt(salesObj.toString());
                        } catch (NumberFormatException ignored) {}
                    }
                    feat.put("sales_bucket", sales / 100);
                    feat.put("hot_score", (double) sales);

                    featureMap.put(productId, feat);
                }
            }
        } catch (Exception e) {
            log.warn("获取完整商品特征失败，增量学习将使用默认值: {}", e.getMessage());
        }

        if (!featureMap.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, featureMap, 1, TimeUnit.HOURS);
        }
        return featureMap;
    }

    /**
     * 计算相似度矩阵
     */
    private Map<Long, Map<Long, Double>> computeSimilarityMatrix(
            Map<Long, Map<Long, Double>> userItemScoreMatrix,
            Map<Long, Long> itemCategoryMap) {

        String cacheKey = "recommendation:similarity:" + similarityCacheVersion + ":all";
        @SuppressWarnings("unchecked")
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
        return Math.pow(popularTimeDecayFactor, days);
    }
}
