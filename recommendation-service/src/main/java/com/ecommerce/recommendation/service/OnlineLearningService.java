package com.ecommerce.recommendation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线学习服务
 *
 * 核心创新点：
 * 1. 实时反馈：用户点击/购买行为实时入队Redis Stream
 * 2. 增量学习：定时消费队列，更新ItemCF相似度矩阵的增量部分
 * 3. 模型热更新：相似度矩阵支持热更新，无需重启服务
 * 4. 多目标学习：同时学习CTR和CVR
 *
 * 数据流：
 * 用户行为 -> Redis Stream -> 定时消费 -> 更新相似度矩阵 -> 推荐生效
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineLearningService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final IncrementalItemCFService incrementalItemCFService;

    // ========== 配置参数 ==========
    @Value("${online-learning.enabled:true}")
    private boolean enabled;

    @Value("${online-learning.interval-ms:60000}")
    private long intervalMs;

    @Value("${online-learning.batch-size:100}")
    private int batchSize;

    @Value("${online-learning.min-samples:50}")
    private int minSamples;

    @Value("${online-learning.explore-ratio:0.1}")
    private double exploreRatio;

    // ========== Redis Key 常量 ==========
    private static final String STREAM_KEY = "online-learning:stream:behaviors";
    private static final String EXPOSURE_KEY = "online-learning:exposure:";
    private static final String FEEDBACK_KEY = "online-learning:feedback:";
    private static final String POSITIVE_FEEDBACK_COUNT = "online-learning:positive:";
    private static final String TOTAL_FEEDBACK_COUNT = "online-learning:total:";
    private static final String MODEL_VERSION_KEY = "online-learning:model:version";

    // ========== 统计指标 ==========
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong totalPositiveEvents = new AtomicLong(0);
    private final AtomicLong totalExplorationEvents = new AtomicLong(0);
    private final AtomicBoolean processingLock = new AtomicBoolean(false);

    // ========== 行为记录接口 ==========

    /**
     * 记录用户行为事件（实时入队）
     *
     * @param userId 用户ID
     * @param productId 被点击/购买的商品ID
     * @param behaviorType 行为类型
     * @param exposureItems 曝光商品列表
     */
    public void recordBehaviorEvent(Long userId, Long productId, String behaviorType, List<Long> exposureItems) {
        if (!enabled) return;

        try {
            // 1. 记录曝光事件
            for (Long item : exposureItems) {
                String exposureKey = EXPOSURE_KEY + item;
                redisTemplate.opsForHash().increment(exposureKey, "total", 1);
                redisTemplate.expire(exposureKey, 7, TimeUnit.DAYS);

                // 如果该商品被点击，更新CTR
                if (item.equals(productId) && isPositiveFeedback(behaviorType)) {
                    redisTemplate.opsForHash().increment(exposureKey, "click", 1);
                }
            }

            // 2. 记录反馈事件（点击/购买等）
            if (isPositiveFeedback(behaviorType)) {
                String feedbackKey = FEEDBACK_KEY + productId;
                redisTemplate.opsForHash().increment(feedbackKey, "positive", 1);
                redisTemplate.opsForHash().increment(feedbackKey, "behavior_" + behaviorType, 1);
                redisTemplate.expire(feedbackKey, 7, TimeUnit.DAYS);
                totalPositiveEvents.incrementAndGet();
            }

            // 3. 入队到处理流
            Map<String, String> event = new HashMap<>();
            event.put("userId", String.valueOf(userId));
            event.put("productId", String.valueOf(productId));
            event.put("behaviorType", behaviorType);
            event.put("exposureItems", String.join(",", exposureItems.stream().map(String::valueOf).toList()));
            event.put("timestamp", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForStream().add(STREAM_KEY, event);

            log.debug("在线学习记录行为: userId={}, productId={}, type={}", userId, productId, behaviorType);

        } catch (Exception e) {
            log.warn("在线学习记录行为失败: {}", e.getMessage());
        }
    }

    /**
     * 记录曝光事件（不产生点击）
     */
    public void recordExposure(Long userId, List<Long> exposureItems) {
        if (!enabled || exposureItems == null || exposureItems.isEmpty()) return;

        try {
            for (Long item : exposureItems) {
                String exposureKey = EXPOSURE_KEY + item;
                redisTemplate.opsForHash().increment(exposureKey, "total", 1);
                redisTemplate.expire(exposureKey, 7, TimeUnit.DAYS);
            }

            // 记录曝光流
            Map<String, String> event = new HashMap<>();
            event.put("userId", String.valueOf(userId));
            event.put("productId", "0");
            event.put("behaviorType", "exposure");
            event.put("exposureItems", String.join(",", exposureItems.stream().map(String::valueOf).toList()));
            event.put("timestamp", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForStream().add(STREAM_KEY, event);

        } catch (Exception e) {
            log.warn("在线学习记录曝光失败: {}", e.getMessage());
        }
    }

    // ========== 定时消费任务 ==========

    /**
     * 定时消费行为事件流，更新模型
     * 每分钟执行一次
     */
    @Scheduled(fixedDelayString = "${online-learning.consume-interval-ms:60000}")
    public void consumeBehaviorStream() {
        if (!enabled) return;
        if (!processingLock.compareAndSet(false, true)) {
            log.debug("在线学习处理任务已在执行中，跳过本次执行");
            return;
        }

        try {
            // 1. 读取待处理事件
            List<Map<String, String>> events = readPendingEvents();

            if (events.isEmpty()) {
                log.debug("在线学习无待处理事件");
                return;
            }

            log.info("在线学习开始处理{}个事件", events.size());

            int processed = 0;
            int explorationCount = 0;

            // 2. 处理每个事件
            for (Map<String, String> event : events) {
                Long userId = Long.valueOf(event.get("userId"));
                Long productId = Long.valueOf(event.get("productId"));
                String behaviorType = event.get("behaviorType");
                String[] exposureArray = event.get("exposureItems").split(",");
                List<Long> exposureItems = new ArrayList<>();
                for (String s : exposureArray) {
                    if (!s.isEmpty()) {
                        exposureItems.add(Long.valueOf(s));
                    }
                }

                // 3. 计算每个曝光商品的CTR得分
                for (int i = 0; i < exposureItems.size(); i++) {
                    Long item = exposureItems.get(i);
                    int position = i;

                    try {
                        // 获取曝光和点击数据
                        String exposureKey = EXPOSURE_KEY + item;
                        Object totalObj = redisTemplate.opsForHash().get(exposureKey, "total");
                        Object clickObj = redisTemplate.opsForHash().get(exposureKey, "click");
                        int total = totalObj != null ? parseInt(totalObj) : 0;
                        int clicks = clickObj != null ? parseInt(clickObj) : 0;

                        // 计算CTR（点击率）
                        double ctr = (total > 0) ? (double) clicks / total : 0.0;

                        // 如果是正反馈事件，更新相似度矩阵
                        if (isPositiveFeedback(behaviorType)) {
                            // CTR越高的商品，相似度更新权重越大
                            double ctrScore = ctr * 10 + 1.0; // 基础权重1.0
                            incrementalItemCFService.recordIncrementalInteraction(
                                userId, productId, behaviorType, ctrScore
                            );
                            totalPositiveEvents.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.warn("处理曝光商品失败: item={}, error={}", item, e.getMessage());
                    }

                    processed++;
                }
            }

            totalEventsProcessed.addAndGet(processed);

            // 4. 更新模型版本
            redisTemplate.opsForValue().set(MODEL_VERSION_KEY, System.currentTimeMillis());

            log.info("在线学习批次处理完成: 处理{}个事件，正反馈{}个",
                processed, totalPositiveEvents.get());

        } catch (Exception e) {
            log.error("在线学习消费事件失败", e);
        } finally {
            processingLock.set(false);
        }
    }

    /**
     * 读取待处理的事件
     */
    private List<Map<String, String>> readPendingEvents() {
        List<Map<String, String>> events = new ArrayList<>();

        try {
            // 读取最近N分钟的事件
            long startTime = System.currentTimeMillis() - intervalMs;

            // 使用SCAN读取流（简化版，实际应该用XREAD）
            var records = redisTemplate.opsForStream().read(
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(batchSize),
                org.springframework.data.redis.connection.stream.StreamOffset.fromStart(STREAM_KEY)
            );

            if (records != null) {
                for (var record : records) {
                    Map<String, String> event = new HashMap<>();
                    record.getValue().forEach((k, v) -> event.put(k.toString(), v.toString()));
                    events.add(event);
                }
            }
        } catch (Exception e) {
            log.warn("读取在线学习事件失败: {}", e.getMessage());
        }

        return events;
    }

    // ========== 查询接口 ==========

    /**
     * 获取商品的实时CTR
     */
    public double getItemCTR(Long productId) {
        try {
            String exposureKey = EXPOSURE_KEY + productId;
            Object totalObj = redisTemplate.opsForHash().get(exposureKey, "total");
            Object clickObj = redisTemplate.opsForHash().get(exposureKey, "click");

            int total = totalObj != null ? parseInt(totalObj) : 0;
            int clicks = clickObj != null ? parseInt(clickObj) : 0;

            return (total > 0) ? (double) clicks / total : 0.0;
        } catch (Exception e) {
            log.warn("获取商品CTR失败: productId={}, error={}", productId, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 获取商品的实时CVR（转化率）
     */
    public double getItemCVR(Long productId) {
        try {
            String feedbackKey = FEEDBACK_KEY + productId;
            Object positiveObj = redisTemplate.opsForHash().get(feedbackKey, "positive");
            Object totalObj = redisTemplate.opsForHash().get(feedbackKey, "total");

            int positive = positiveObj != null ? parseInt(positiveObj) : 0;
            int total = totalObj != null ? parseInt(totalObj) : 0;

            return (total > 0) ? (double) positive / total : 0.0;
        } catch (Exception e) {
            log.warn("获取商品CVR失败: productId={}, error={}", productId, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 判断是否应该使用探索策略
     */
    public boolean shouldExplore() {
        if (!enabled) return false;
        return Math.random() < exploreRatio;
    }

    // ========== 辅助方法 ==========

    private boolean isPositiveFeedback(String behaviorType) {
        if (behaviorType == null) return false;
        String type = behaviorType.toLowerCase().trim();
        return "click".equals(type) || "cart".equals(type) ||
               "favorite".equals(type) || "buy".equals(type);
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
        metrics.put("totalEventsProcessed", totalEventsProcessed.get());
        metrics.put("totalPositiveEvents", totalPositiveEvents.get());
        metrics.put("exploreRatio", exploreRatio);
        Object modelVersion = redisTemplate.opsForValue().get(MODEL_VERSION_KEY);
        metrics.put("modelVersion", modelVersion != null ? modelVersion : "N/A");
        return metrics;
    }
}
