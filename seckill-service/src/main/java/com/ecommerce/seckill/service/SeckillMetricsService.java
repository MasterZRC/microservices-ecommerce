package com.ecommerce.seckill.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SeckillMetricsService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";

    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;
    private final Counter requestCounter;
    private final Counter successCounter;
    private final Counter rateLimitedCounter;
    private final Counter luaErrorCounter;
    private final Timer durationTimer;

    public SeckillMetricsService(MeterRegistry meterRegistry, StringRedisTemplate redisTemplate) {
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        this.requestCounter = Counter.builder("seckill_requests_total")
                .tag("type", "all")
                .description("Total seckill requests")
                .register(meterRegistry);
        this.successCounter = Counter.builder("seckill_requests_success_total")
                .description("Total successful seckill requests")
                .register(meterRegistry);
        this.rateLimitedCounter = Counter.builder("seckill_rate_limited_total")
                .description("Total rate limited seckill requests")
                .register(meterRegistry);
        this.luaErrorCounter = Counter.builder("seckill_lua_script_errors_total")
                .description("Total seckill Lua script errors")
                .register(meterRegistry);
        this.durationTimer = Timer.builder("seckill_duration_seconds")
                .description("Seckill handling duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        Gauge.builder("seckill_queue_size", this, SeckillMetricsService::getQueueSize)
                .description("Current seckill Redis Stream length")
                .register(meterRegistry);
    }

    public void recordRequest() {
        requestCounter.increment();
    }

    public void recordSuccess() {
        successCounter.increment();
    }

    public void recordFailed(String reason) {
        Counter.builder("seckill_requests_failed_total")
                .tag("reason", reason)
                .description("Total failed seckill requests by reason")
                .register(meterRegistry)
                .increment();
        log.debug("Seckill failure metric recorded: reason={}", reason);
    }

    public void recordRateLimited() {
        rateLimitedCounter.increment();
    }

    public void recordLuaError() {
        luaErrorCounter.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimer(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(durationTimer);
        }
    }

    public double getQueueSize() {
        try {
            Long size = redisTemplate.opsForStream().size(SeckillService.SECKILL_ORDER_STREAM_KEY);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.warn("Failed to read seckill queue size: {}", e.getMessage());
            return 0;
        }
    }

    public double getAvailableStock(Long productId) {
        try {
            String stock = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + productId);
            return stock != null ? Double.parseDouble(stock) : 0;
        } catch (Exception e) {
            log.warn("Failed to read seckill stock metric: productId={}, error={}", productId, e.getMessage());
            return 0;
        }
    }

    public void registerProductStockGauge(Long productId, int stock) {
        Gauge.builder("seckill_available_stock", () -> getAvailableStock(productId))
                .tag("product_id", String.valueOf(productId))
                .description("Available stock for a seckill product")
                .register(meterRegistry);
    }
}
