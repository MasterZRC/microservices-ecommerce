package com.ecommerce.recommendation.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 推荐服务 Prometheus 指标埋点
 * 
 * 生产级推荐系统必须具备可观测性，本服务提供：
 * - 请求量指标（推荐请求数、成功率）
 * - 性能指标（响应延迟）
 * - 召回指标（各召回通道数量、质量）
 * - 排序指标（DeepFM 调用成功率、延迟）
 * - 降级指标（降级次数、降级原因）
 */
@Slf4j
@Service
public class RecommendationMetricsService {

    private final MeterRegistry meterRegistry;

    // 请求计数器
    private Counter recommendationRequestCounter;
    private Counter recommendationSuccessCounter;
    private Counter recommendationFailureCounter;

    // 召回通道计数器
    private Counter recallCfCounter;
    private Counter recallPopularCounter;
    private Counter recallCategoryCounter;
    private Counter recallContentCounter;

    // 排序计数器
    private Counter rankRequestCounter;
    private Counter rankSuccessCounter;
    private Counter rankFailureCounter;
    private Counter rankFallbackCounter;

    // 降级计数器
    private Counter degradeColdStartCounter;
    private Counter degradeItemCfFallbackCounter;

    // 性能计时器
    private Timer recommendationTimer;
    private Timer recallTimer;
    private Timer rankTimer;

    // 实时指标（用于 Gauge）
    private final AtomicInteger candidatePoolSize = new AtomicInteger(0);
    private final AtomicInteger finalResultSize = new AtomicInteger(0);
    private final Map<String, AtomicInteger> degradeReasonCount = new ConcurrentHashMap<>();

    public RecommendationMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // 请求指标
        recommendationRequestCounter = Counter.builder("recommendation_requests_total")
                .description("推荐请求总数")
                .tag("service", "recommendation")
                .register(meterRegistry);

        recommendationSuccessCounter = Counter.builder("recommendation_requests_success")
                .description("推荐请求成功数")
                .tag("service", "recommendation")
                .register(meterRegistry);

        recommendationFailureCounter = Counter.builder("recommendation_requests_failure")
                .description("推荐请求失败数")
                .tag("service", "recommendation")
                .register(meterRegistry);

        // 召回通道指标
        recallCfCounter = Counter.builder("recommendation_recall_channels_total")
                .description("各召回通道返回数量")
                .tag("channel", "itemcf")
                .register(meterRegistry);

        recallPopularCounter = Counter.builder("recommendation_recall_channels_total")
                .description("各召回通道返回数量")
                .tag("channel", "popular")
                .register(meterRegistry);

        recallCategoryCounter = Counter.builder("recommendation_recall_channels_total")
                .description("各召回通道返回数量")
                .tag("channel", "category")
                .register(meterRegistry);

        recallContentCounter = Counter.builder("recommendation_recall_channels_total")
                .description("各召回通道返回数量")
                .tag("channel", "content")
                .register(meterRegistry);

        // 排序指标
        rankRequestCounter = Counter.builder("recommendation_rank_requests_total")
                .description("排序服务请求总数")
                .tag("service", "rank")
                .register(meterRegistry);

        rankSuccessCounter = Counter.builder("recommendation_rank_success_total")
                .description("排序服务成功次数")
                .tag("service", "rank")
                .register(meterRegistry);

        rankFailureCounter = Counter.builder("recommendation_rank_failure_total")
                .description("排序服务失败次数")
                .tag("service", "rank")
                .register(meterRegistry);

        rankFallbackCounter = Counter.builder("recommendation_rank_fallback_total")
                .description("排序服务降级次数")
                .tag("service", "rank")
                .register(meterRegistry);

        // 降级指标
        degradeColdStartCounter = Counter.builder("recommendation_degrade_total")
                .description("降级触发次数")
                .tag("reason", "cold_start")
                .register(meterRegistry);

        degradeItemCfFallbackCounter = Counter.builder("recommendation_degrade_total")
                .description("降级触发次数")
                .tag("reason", "itemcf_fallback")
                .register(meterRegistry);

        // 性能计时器
        recommendationTimer = Timer.builder("recommendation_request_duration_seconds")
                .description("推荐请求耗时")
                .tag("service", "recommendation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        recallTimer = Timer.builder("recommendation_recall_duration_seconds")
                .description("召回阶段耗时")
                .tag("service", "recommendation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        rankTimer = Timer.builder("recommendation_rank_duration_seconds")
                .description("排序阶段耗时")
                .tag("service", "rank")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Gauge 指标
        Gauge.builder("recommendation_candidate_pool_size", candidatePoolSize, AtomicInteger::get)
                .description("当前候选池大小")
                .register(meterRegistry);

        Gauge.builder("recommendation_final_result_size", finalResultSize, AtomicInteger::get)
                .description("最终推荐结果数量")
                .register(meterRegistry);

        Gauge.builder("recommendation_degrade_reason_count", degradeReasonCount,
                map -> map.values().stream().mapToInt(AtomicInteger::get).sum())
                .description("各类降级原因累计次数")
                .register(meterRegistry);

        log.info("[Metrics] 推荐服务指标埋点初始化完成");
    }

    // ==================== 请求指标 ====================

    public void recordRequest() {
        recommendationRequestCounter.increment();
    }

    public void recordSuccess() {
        recommendationSuccessCounter.increment();
    }

    public void recordFailure() {
        recommendationFailureCounter.increment();
    }

    // ==================== 召回指标 ====================

    public void recordRecallChannel(String channel, int count) {
        if (count <= 0) return;

        switch (channel.toLowerCase()) {
            case "itemcf", "cf" -> recallCfCounter.increment(count);
            case "popular" -> recallPopularCounter.increment(count);
            case "category" -> recallCategoryCounter.increment(count);
            case "content" -> recallContentCounter.increment(count);
        }
    }

    public void recordCandidatePoolSize(int size) {
        candidatePoolSize.set(size);
    }

    public void recordFinalResultSize(int size) {
        finalResultSize.set(size);
    }

    // ==================== 排序指标 ====================

    public void recordRankRequest() {
        rankRequestCounter.increment();
    }

    public void recordRankSuccess() {
        rankSuccessCounter.increment();
    }

    public void recordRankFailure() {
        rankFailureCounter.increment();
    }

    public void recordRankFallback() {
        rankFallbackCounter.increment();
    }

    // ==================== 降级指标 ====================

    public void recordDegrade(String reason) {
        degradeReasonCount.computeIfAbsent(reason, k -> new AtomicInteger(0)).incrementAndGet();

        switch (reason.toLowerCase()) {
            case "cold_start" -> degradeColdStartCounter.increment();
            case "itemcf_fallback" -> degradeItemCfFallbackCounter.increment();
        }
    }

    // ==================== 性能计时 ====================

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopRecommendationTimer(Timer.Sample sample) {
        sample.stop(recommendationTimer);
    }

    public void stopRecallTimer(Timer.Sample sample) {
        sample.stop(recallTimer);
    }

    public void stopRankTimer(Timer.Sample sample) {
        sample.stop(rankTimer);
    }

    // ==================== 批量记录（减少指标数量） ====================

    public void recordAllRecallChannels(int cfCount, int popularCount, int categoryCount, int contentCount) {
        recordRecallChannel("itemcf", cfCount);
        recordRecallChannel("popular", popularCount);
        recordRecallChannel("category", categoryCount);
        recordRecallChannel("content", contentCount);
    }

    // ==================== 额外指标（可观测性增强） ====================

    // 推荐结果空率指标（用于告警）
    private final AtomicInteger emptyResultCount = new AtomicInteger(0);
    private final AtomicInteger totalRequestCount = new AtomicInteger(0);

    // 在线学习指标
    private Counter onlineLearningUpdateCounter;
    private Counter onlineLearningErrorCounter;

    // 商品曝光指标
    private Counter productExposureCounter;

    @PostConstruct
    public void initAdditionalMetrics() {
        // 初始化额外指标
        onlineLearningUpdateCounter = Counter.builder("recommendation_online_learning_updates_total")
                .description("在线学习更新次数")
                .tag("type", "incremental")
                .register(meterRegistry);

        onlineLearningErrorCounter = Counter.builder("recommendation_online_learning_errors_total")
                .description("在线学习错误次数")
                .tag("type", "error")
                .register(meterRegistry);

        productExposureCounter = Counter.builder("recommendation_product_exposure_total")
                .description("商品曝光次数")
                .register(meterRegistry);

        // 空结果率 Gauge
        Gauge.builder("recommendation_empty_result_ratio", this, service -> {
            int total = totalRequestCount.get();
            if (total == 0) return 0.0;
            return (double) emptyResultCount.get() / total;
        }).description("推荐结果空率")
                .register(meterRegistry);

        log.info("[Metrics] 推荐服务额外指标初始化完成");
    }

    /**
     * 记录推荐结果为空（冷启动）
     */
    public void recordEmptyResult() {
        emptyResultCount.incrementAndGet();
    }

    /**
     * 记录总请求数（用于计算空率）
     */
    public void recordTotalRequest() {
        totalRequestCount.incrementAndGet();
    }

    /**
     * 记录在线学习更新
     */
    public void recordOnlineLearningUpdate() {
        onlineLearningUpdateCounter.increment();
    }

    /**
     * 记录在线学习错误
     */
    public void recordOnlineLearningError() {
        onlineLearningErrorCounter.increment();
    }

    /**
     * 记录商品曝光
     */
    public void recordProductExposure(int count) {
        productExposureCounter.increment(count);
    }

    /**
     * 获取空结果数
     */
    public int getEmptyResultCount() {
        return emptyResultCount.get();
    }

    /**
     * 获取总请求数
     */
    public int getTotalRequestCount() {
        return totalRequestCount.get();
    }
}
