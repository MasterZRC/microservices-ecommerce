package com.ecommerce.seckill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀业务自定义 Prometheus 指标服务
 *
 * 提供的指标：
 * - seckill_requests_total: 秒杀请求总数
 * - seckill_requests_success_total: 秒杀成功总数
 * - seckill_requests_failed_total: 秒杀失败总数（按失败原因分类）
 * - seckill_rate_limited_total: 限流次数
 * - seckill_lua_script_errors_total: Lua脚本执行错误次数
 * - seckill_queue_size: 消息队列当前大小
 * - seckill_duration_seconds: 秒杀处理耗时（P50/P95/P99）
 * - seckill_available_stock: 可用库存（按商品ID）
 *
 * @author ecommerce
 */
@Service
@Slf4j
public class SeckillMetricsService {

    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;

    private static final String QUEUE_SIZE_KEY = "seckill:queue:size";
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";

    private final Counter requestCounter;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter rateLimitedCounter;
    private final Counter luaErrorCounter;
    private final Timer durationTimer;

    public SeckillMetricsService(MeterRegistry meterRegistry, StringRedisTemplate redisTemplate) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;

        // 秒杀请求计数器
        this.requestCounter = Counter.builder("seckill_requests_total")
                .tag("type", "all")
                .description("秒杀请求总数")
                .register(meterRegistry);

        // 秒杀成功计数器
        this.successCounter = Counter.builder("seckill_requests_success_total")
                .description("秒杀成功总数")
                .register(meterRegistry);

        // 秒杀失败计数器（带失败原因标签）
        this.failedCounter = Counter.builder("seckill_requests_failed_total")
                .tag("reason", "unknown")
                .description("秒杀失败总数")
                .register(meterRegistry);

        // 限流计数器
        this.rateLimitedCounter = Counter.builder("seckill_rate_limited_total")
                .description("秒杀限流次数")
                .register(meterRegistry);

        // Lua脚本错误计数器
        this.luaErrorCounter = Counter.builder("seckill_lua_script_errors_total")
                .description("秒杀Lua脚本执行错误次数")
                .register(meterRegistry);

        // 秒杀处理耗时计时器
        this.durationTimer = Timer.builder("seckill_duration_seconds")
                .description("秒杀处理耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        // 注册队列大小仪表
        Gauge.builder("seckill_queue_size", this, SeckillMetricsService::getQueueSize)
                .description("秒杀消息队列当前大小")
                .register(meterRegistry);
    }

    /**
     * 记录秒杀请求
     */
    public void recordRequest() {
        requestCounter.increment();
    }

    /**
     * 记录秒杀成功
     */
    public void recordSuccess() {
        successCounter.increment();
    }

    /**
     * 记录秒杀失败
     *
     * @param reason 失败原因：stock_exhausted/rate_limited/duplicate/error
     */
    public void recordFailed(String reason) {
        // 使用带原因标签的计数器
        Counter.builder("seckill_requests_failed_total")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();

        // 也增加通用失败计数器
        failedCounter.increment();

        log.debug("秒杀失败统计 - 原因: {}", reason);
    }

    /**
     * 记录限流触发
     */
    public void recordRateLimited() {
        rateLimitedCounter.increment();
    }

    /**
     * 记录Lua脚本错误
     */
    public void recordLuaError() {
        luaErrorCounter.increment();
    }

    /**
     * 获取秒杀处理耗时计时器（用于自动计时）
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 停止计时并记录耗时
     */
    public void stopTimer(Timer.Sample sample) {
        sample.stop(durationTimer);
    }

    /**
     * 获取消息队列大小
     */
    public double getQueueSize() {
        try {
            Long size = redisTemplate.opsForList().size("seckill:order:pending");
            return size != null ? size : 0;
        } catch (Exception e) {
            log.warn("获取队列大小失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 获取指定商品的可用库存
     *
     * @param productId 商品ID
     * @return 可用库存
     */
    public double getAvailableStock(Long productId) {
        try {
            String stock = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + productId);
            return stock != null ? Double.parseDouble(stock) : 0;
        } catch (Exception e) {
            log.warn("获取商品库存失败 - productId: {}, error: {}", productId, e.getMessage());
            return 0;
        }
    }

    /**
     * 注册商品库存仪表
     *
     * @param productId 商品ID
     * @param stock    库存
     */
    public void registerProductStockGauge(Long productId, int stock) {
        Gauge.builder("seckill_available_stock", () -> getAvailableStock(productId))
                .tag("product_id", String.valueOf(productId))
                .description("秒杀商品可用库存")
                .register(meterRegistry);
    }
}
