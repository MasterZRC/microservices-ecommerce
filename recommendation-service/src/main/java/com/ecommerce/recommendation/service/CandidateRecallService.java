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

    private RecommendationMetricsService metricsService;

    private static final String POPULAR_ITEMS_KEY = "recommendation:popular:";
    private static final String ITEM_CATEGORY_KEY = "recommendation:item:category:";
    private static final String ITEM_FEATURE_KEY = "recommendation:item:features:";
    private static final Map<String, Integer> BEHAVIOR_WEIGHT = buildBehaviorWeight();
    private static final long MAX_DECAY_DAYS = 30;

    /**
     * 注入指标服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setMetricsService(RecommendationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    // ==================== 公共方法（供 DiversityService 使用） ====================

    /**
     * 获取商品服务 URL（供 DiversityService 调用）
     */
    public String getProductServiceUrl() {
        return productServiceUrl;
    }

    /**
     * 获取 RestTemplate（供 DiversityService 调用）
     */
    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    // ==================== 多路召回主入口 ====================

    /**
     * 执行多路召回，返回合并后的候选商品列表
     * 召回渠道：
     * - recall_cf: ItemCF 协同过滤
     * - recall_popular: 热门召回
     * - recall_category: 同类目召回
     * - recall_content: 基于标题 TF-IDF 的内容相似度召回（冷启动专用）
     */
    public List<Long> multiChannelRecall(Long userId) {
        return multiChannelRecall(userId, maxPoolSize);
    }

    /**
     * 执行多路召回（带召回数量参数）
     * 
     * 【重构版】分层召回 + 保护个性化：
     * - L1 ItemCF召回：完全不过滤，保护用户个性化推荐
     * - L2 同类目召回：基于用户偏好类目打分
     * - L3 热门商品：只补充用户偏好类目，兜底全局热门
     * - L4 内容召回：基于TF-IDF内容相似度（冷启动兜底）
     * 
     * 移除已交互商品的策略（重要！）：
     * - 对于"购买"过的商品：强烈过滤（已满足需求）
     * - 对于"加购/收藏"过的商品：谨慎过滤（可能还会买）
     * - 对于"浏览/点击"过的商品：CF召回不过滤（看完还可能买）
     */
    public List<Long> multiChannelRecall(Long userId, int limit) {
        io.micrometer.core.instrument.Timer.Sample timerSample = null;
        if (metricsService != null) {
            timerSample = metricsService.startTimer();
        }

        // 获取用户已交互商品（分层）
        Map<Long, Set<String>> userInteractedMap = getUserInteractedItemsWithTypes(userId);
        Set<Long> userInteracted = userInteractedMap.keySet();
        Set<Long> userBought = new HashSet<>();
        Set<Long> userCartFav = new HashSet<>();
        for (Map.Entry<Long, Set<String>> entry : userInteractedMap.entrySet()) {
            if (entry.getValue().contains("buy")) {
                userBought.add(entry.getKey());
            }
            if (entry.getValue().contains("cart") || entry.getValue().contains("favorite")) {
                userCartFav.add(entry.getKey());
            }
        }

        // 获取用户偏好类目（用于同类目召回和热门补充）
        Long preferredCategory = getUserPreferredCategory(userId);
        log.info("[多路召回] userId={}, 偏好类目={}", userId, preferredCategory);

        // ===== L1 ItemCF召回 =====
        // 【核心改进】CF召回不过滤任何商品（保护个性化）
        // 因为CF是基于用户行为计算出来的相似商品，用户可能还会买
        List<Long> cfCandidates = recallByItemCF(userId, cfRecallCount);
        List<Long> cfFiltered = new ArrayList<>(cfCandidates);
        // 只过滤用户已购买的商品（强烈需求已满足）
        cfFiltered.removeAll(userBought);
        log.info("[多路召回] CF召回={}, 过滤已购买后={}", cfCandidates.size(), cfFiltered.size());

        // ===== L2 同类目召回 =====
        // 基于用户偏好类目召回
        // 【改进】只过滤已购买商品，保留加购/收藏商品（强购买意向）
        List<Long> categoryCandidates = recallByCategorySmart(userId, preferredCategory, categoryRecallCount * 2);
        List<Long> catFiltered = new ArrayList<>();
        for (Long item : categoryCandidates) {
            // 只过滤已购买商品，保留加购/收藏（用户可能还会买）
            if (!userBought.contains(item)) {
                catFiltered.add(item);
            }
        }
        log.info("[多路召回] 同类目召回={}, 过滤已购买后={}", categoryCandidates.size(), catFiltered.size());

        // ===== L3 热门商品（智能补充）=====
        // 【核心改进】热门商品补充必须优先从用户偏好类目选择
        List<Long> popularCandidates = recallByPopularSmart(preferredCategory, popularRecallCount * 2);
        List<Long> popFiltered = new ArrayList<>();
        for (Long item : popularCandidates) {
            // 过滤已购买和已加购商品
            if (!userBought.contains(item) && !userCartFav.contains(item)) {
                popFiltered.add(item);
            }
        }
        log.info("[多路召回] 热门召回={}, 过滤后={}", popularCandidates.size(), popFiltered.size());

        // ===== L4 内容召回（TF-IDF冷启动兜底）=====
        List<Long> contentCandidates = Collections.emptyList();
        if (cfFiltered.isEmpty() && catFiltered.isEmpty() && popFiltered.isEmpty()) {
            log.info("[多路召回] 所有召回渠道为空，启用内容召回兜底");
            contentCandidates = recallByContent(userId, contentRecallCount);
            if (metricsService != null) {
                metricsService.recordRecallChannel("content", contentCandidates.size());
            }
        } else {
            // 即使不是空，也获取一些内容召回候选作为补充
            contentCandidates = recallByContent(userId, contentRecallCount);
        }

        // ===== 分层合并策略 =====
        // 【重构】质量驱动的动态合并：每条召回渠道根据候选质量动态分配位置
        // CF召回的候选（与用户历史相似）优先排在前面，热门/同类目兜底补充
        List<Long> result = new ArrayList<>();
        Set<Long> usedIds = new HashSet<>();

        // 【改进】CF召回商品优先加入（它们是真正的个性化推荐）
        for (Long item : cfFiltered) {
            if (usedIds.contains(item)) continue;
            result.add(item);
            usedIds.add(item);
        }

        // 【改进】同类目+热门商品按质量评分混合插入
        // 构建混合候选池：同类目×2权重，热门×1权重
        List<ItemWithScore> mixedCandidates = new ArrayList<>();
        for (Long item : catFiltered) {
            if (!usedIds.contains(item)) {
                mixedCandidates.add(new ItemWithScore(item, 2.0)); // 同类目高质量
            }
        }
        for (Long item : popFiltered) {
            if (!usedIds.contains(item)) {
                mixedCandidates.add(new ItemWithScore(item, 1.0)); // 热门兜底
            }
        }
        // 按质量分数降序插入
        mixedCandidates.sort((a, b) -> Double.compare(b.score, a.score));
        for (ItemWithScore iws : mixedCandidates) {
            if (result.size() >= maxPoolSize) break;
            if (!usedIds.contains(iws.itemId)) {
                result.add(iws.itemId);
                usedIds.add(iws.itemId);
            }
        }

        // 如果还不够，补充内容召回
        for (Long item : contentCandidates) {
            if (result.size() >= maxPoolSize) break;
            if (!usedIds.contains(item)) {
                result.add(item);
                usedIds.add(item);
            }
        }

        // 【调试】打印合并后候选池的实际类目分布
        if (log.isDebugEnabled()) {
            Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
            Map<Long, Long> poolCategoryCount = new HashMap<>();
            for (Long itemId : result) {
                Long cat = itemCategoryMap.get(itemId);
                if (cat == null) cat = -1L;
                poolCategoryCount.merge(cat, 1L, Long::sum);
            }
            log.debug("[多路召回] userId={}, 合并后候选池类目分布={}", userId, poolCategoryCount);
        }

        // ===== 最终兜底：全局热门 =====
        if (result.isEmpty()) {
            log.info("[多路召回] 所有召回渠道为空，使用全局热门兜底");
            if (metricsService != null) {
                metricsService.recordDegrade("cold_start");
            }
            List<Long> fallback = recallByPopular(maxPoolSize);
            if (timerSample != null && metricsService != null) {
                metricsService.stopRecallTimer(timerSample);
            }
            return fallback.stream().limit(limit).collect(Collectors.toList());
        }

        // 记录指标
        log.info("[多路召回] userId={}, cf={}(过滤后{}), category={}(过滤后{}), popular={}(过滤后{}), content={}, 合并后={}",
                userId, cfCandidates.size(), cfFiltered.size(), categoryCandidates.size(), catFiltered.size(), 
                popularCandidates.size(), popFiltered.size(), contentCandidates.size(), result.size());
        if (metricsService != null) {
            metricsService.recordAllRecallChannels(cfFiltered.size(), catFiltered.size(), popFiltered.size(), contentCandidates.size());
            metricsService.recordCandidatePoolSize(result.size());
        }

        if (timerSample != null && metricsService != null) {
            metricsService.stopRecallTimer(timerSample);
        }
        return result.stream().limit(limit).collect(Collectors.toList());
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
     * 获取用户已交互商品（带行为类型，用于精细化过滤）
     * @return Map<商品ID, 行为类型集合>
     */
    private Map<Long, Set<String>> getUserInteractedItemsWithTypes(Long userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        List<UserBehavior> behaviors = behaviorMapper.selectList(new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .select(UserBehavior::getProductId, UserBehavior::getBehaviorType));

        Map<Long, Set<String>> result = new HashMap<>();
        for (UserBehavior behavior : behaviors) {
            result.computeIfAbsent(behavior.getProductId(), k -> new HashSet<>())
                    .add(normalizeBehaviorType(behavior.getBehaviorType()));
        }
        return result;
    }

    /**
     * 智能同类目召回
     * @param userId 用户ID
     * @param preferredCategory 用户偏好类目
     * @param limit 召回数量
     * @return 同类目热门商品
     */
    private List<Long> recallByCategorySmart(Long userId, Long preferredCategory, int limit) {
        if (preferredCategory == null) {
            return Collections.emptyList();
        }
        return getCategoryPopularProducts(preferredCategory, limit);
    }

    /**
     * 智能热门商品召回
     * 优先从用户偏好类目选择热门商品
     * @param preferredCategory 用户偏好类目
     * @param limit 召回数量
     * @return 热门商品列表
     */
    private List<Long> recallByPopularSmart(Long preferredCategory, int limit) {
        List<Long> allPopular = recallByPopular(limit * 2);
        if (allPopular.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
        
        // 优先从用户偏好类目选择
        List<Long> preferred = new ArrayList<>();
        List<Long> others = new ArrayList<>();
        
        for (Long itemId : allPopular) {
            Long catId = itemCategoryMap.get(itemId);
            if (catId != null && catId.equals(preferredCategory)) {
                preferred.add(itemId);
            } else {
                others.add(itemId);
            }
        }

        // 混合：70%偏好类目 + 30%其他类目（保持多样性）
        List<Long> result = new ArrayList<>();
        int prefCount = (int) (limit * 0.7);
        result.addAll(preferred.stream().limit(prefCount).collect(Collectors.toList()));
        if (result.size() < limit) {
            result.addAll(others.stream().limit(limit - result.size()).collect(Collectors.toList()));
        }
        return result;
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
        log.info("[CF召回] userId={}, userItemScores大小={}, matrix总用户数={}", userId, userItemScores.size(), userItemScoreMatrix.size());
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

        // 当 user_behavior 表无数据时，从商品服务按销量获取热门商品兜底
        if (popularItems.isEmpty()) {
            log.info("user_behavior 无数据，从商品服务获取热门兜底");
            popularItems = fallbackToProductServicePopular(limit * 2);
        }

        if (!popularItems.isEmpty()) {
            redisTemplate.opsForValue().set(popKey, popularItems, 1, TimeUnit.HOURS);
        }

        return popularItems.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * 冷启动兜底：user_behavior 无数据时，从商品服务获取按销量排序的商品
     */
    private List<Long> fallbackToProductServicePopular(int limit) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/list")
                    .queryParam("page", 1)
                    .queryParam("pageSize", Math.max(limit, 100))
                    .build()
                    .toUriString();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null) return Collections.emptyList();

            Object productsObj = response.get("products");
            if (productsObj instanceof List<?> products) {
                return products.stream()
                        .filter(p -> p instanceof Map)
                        .map(p -> {
                            Object id = ((Map<?, ?>) p).get("id");
                            if (id instanceof Number) return ((Number) id).longValue();
                            if (id != null) {
                                try { return Long.parseLong(id.toString()); } catch (NumberFormatException ignored) {}
                            }
                            return null;
                        })
                        .filter(Objects::nonNull)
                        .limit(limit)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("从商品服务获取热门兜底失败: {}", e.getMessage());
        }
        return Collections.emptyList();
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
     * 【改进】直接从商品库查询同类目商品，不过滤已购买
     * 对于强购买意向的用户（如加购物车），需要推荐同类目但未购买的商品
     */
    private List<Long> getCategoryPopularProducts(Long categoryId, int limit) {
        // 如果用户偏好类目有效，直接查询商品服务获取该类目的商品
        List<Long> result = new ArrayList<>();
        
        if (categoryId == null) {
            return Collections.emptyList();
        }
        
        try {
            // 直接调用商品服务获取该类目的商品（不限销量排序）
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/list")
                    .queryParam("page", 1)
                    .queryParam("pageSize", 100)
                    .queryParam("categoryId", categoryId)
                    .build()
                    .toUriString();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            
            if (response != null && response.get("products") instanceof List) {
                List<Map<String, Object>> products = (List<Map<String, Object>>) response.get("products");
                for (Map<String, Object> product : products) {
                    Object idObj = product.get("id");
                    if (idObj != null) {
                        result.add(Long.valueOf(idObj.toString()));
                        if (result.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取类目商品列表失败: {}", e.getMessage());
        }
        
        // 如果商品服务查询失败，fallback到热门商品
        if (result.isEmpty()) {
            List<Long> allPopular = recallByPopular(limit * 10);
            Map<Long, Long> itemCategoryMap = buildItemCategoryMap();
            
            for (Long itemId : allPopular) {
                Long itemCategory = itemCategoryMap.get(itemId);
                if (itemCategory != null && itemCategory.equals(categoryId)) {
                    result.add(itemId);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
        }
        
        log.info("[同类目召回] 类目={}, 直接查询到商品数={}", categoryId, result.size());
        return result;
    }

    /**
     * 按指定类目召回热门商品（供其他服务使用，如增量ItemCF初始化）
     */
    public List<Long> recallByCategoryId(Long categoryId, int limit) {
        return getCategoryPopularProducts(categoryId, limit);
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

        log.info("[UserItemMatrix] 加载行为数={}, 矩阵用户数={}", behaviors.size(), matrix.size());

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

        log.info("[UserItemMatrix] 构建完成，矩阵用户数={}", matrix.size());
        return matrix;
    }

    /**
     * 构建物品-类别映射（从商品服务获取）
     * 【修复】确保从缓存加载时正确处理类型转换
     */
    public Map<Long, Long> buildItemCategoryMap() {
        String cacheKey = ITEM_CATEGORY_KEY + "all";
        @SuppressWarnings("unchecked")
        Map<Long, Long> cached = (Map<Long, Long>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null && !cached.isEmpty()) {
            // 检查缓存中的 key 类型，如果是 String 则转换为 Long
            Map<Long, Long> normalizedMap = new HashMap<>();
            boolean needsNormalization = false;
            for (Map.Entry<?, ?> entry : cached.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                Long k = null;
                Long v = null;
                if (key instanceof Long) {
                    k = (Long) key;
                } else if (key instanceof String) {
                    k = Long.valueOf((String) key);
                    needsNormalization = true;
                } else if (key instanceof Integer) {
                    k = ((Integer) key).longValue();
                    needsNormalization = true;
                }
                if (value instanceof Long) {
                    v = (Long) value;
                } else if (value instanceof String) {
                    v = Long.valueOf((String) value);
                    needsNormalization = true;
                } else if (value instanceof Integer) {
                    v = ((Integer) value).longValue();
                    needsNormalization = true;
                }
                if (k != null && v != null) {
                    normalizedMap.put(k, v);
                }
            }
            if (needsNormalization) {
                // 重新缓存规范化后的数据
                redisTemplate.opsForValue().set(cacheKey, normalizedMap, 1, TimeUnit.HOURS);
                return normalizedMap;
            }
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
            log.info("[类目映射] 开始从商品服务获取，URL={}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.get("products") instanceof List) {
                List<Map<String, Object>> products = (List<Map<String, Object>>) response.get("products");
                log.info("[类目映射] 收到商品数量={}", products.size());
                for (Map<String, Object> product : products) {
                    Object idObj = product.get("id");
                    Object categoryIdObj = product.get("categoryId");
                    if (idObj != null && categoryIdObj != null) {
                        Long productId = Long.valueOf(idObj.toString());
                        Long categoryId = Long.valueOf(categoryIdObj.toString());
                        categoryMap.put(productId, categoryId);
                    }
                }
                log.info("[类目映射] 解析后有效映射数={}", categoryMap.size());
            } else {
                log.warn("[类目映射] 商品服务返回异常，response={}", response);
            }
        } catch (Exception e) {
            log.error("[类目映射] 获取商品类目信息失败: {}", e.getMessage());
        }

        if (!categoryMap.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, categoryMap, 1, TimeUnit.HOURS);
            log.info("[类目映射] 已缓存，有效映射数={}", categoryMap.size());
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

    /**
     * 辅助类：带质量分数的商品（用于混合候选池排序）
     */
    private static class ItemWithScore {
        final Long itemId;
        final double score;
        ItemWithScore(Long itemId, double score) {
            this.itemId = itemId;
            this.score = score;
        }
    }
}
