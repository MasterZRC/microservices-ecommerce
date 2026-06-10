package com.ecommerce.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.seckill.dto.SeckillAttemptResult;
import com.ecommerce.seckill.dto.SeckillDemoJobSnapshot;
import com.ecommerce.seckill.dto.SeckillDemoPoint;
import com.ecommerce.seckill.dto.SeckillDemoRequest;
import com.ecommerce.seckill.dto.SeckillDemoResetResult;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillDemoService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String ORDER_KEY_PREFIX = "seckill:order:";
    private static final String RATE_LIMIT_KEY_PREFIX = "seckill:ratelimit:";
    private static final String ASYNC_DONE_KEY_PREFIX = "seckill:async:done:";
    private static final String RETRY_KEY_PREFIX = SeckillOrderStreamConsumer.RETRY_KEY_PREFIX;
    private static final String ACTIVE_PRODUCTS_CACHE_KEY = "seckill:cache:products:active";
    private static final String UPCOMING_PRODUCTS_CACHE_KEY_PREFIX = "seckill:cache:products:upcoming";
    private static final String ENDTIME_CACHE_KEY = "seckill:cache:endtime";
    private static final String PRODUCT_EXISTS_CACHE_KEY_PREFIX = "seckill:product:exists:";

    private static final int DEFAULT_TOTAL_REQUESTS = 1000;
    private static final int DEFAULT_CONCURRENCY = 100;
    private static final int DEFAULT_STOCK = 200;
    private static final int DEFAULT_QUANTITY = 1;
    private static final long DEFAULT_USER_ID_BASE = 900_000_000L;

    private static final int MAX_TOTAL_REQUESTS = 20_000;
    private static final int MAX_CONCURRENCY = 500;
    private static final int MAX_STOCK = 100_000;
    private static final int MAX_CLEANUP_USERS = 50_000;
    private static final long ASYNC_WAIT_TIMEOUT_MS = 15_000;

    private final SeckillService seckillService;
    private final SeckillProductMapper seckillProductMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    private final ExecutorService jobExecutor = Executors.newCachedThreadPool();
    private final Map<String, DemoJob> jobs = new ConcurrentHashMap<>();

    public List<SeckillProduct> getDemoProducts() {
        List<SeckillProduct> products = seckillProductMapper.selectList(
                new LambdaQueryWrapper<SeckillProduct>()
                        .eq(SeckillProduct::getStatus, 1)
                        .orderByDesc(SeckillProduct::getId)
        );
        for (SeckillProduct product : products) {
            Integer redisStock = seckillService.getStock(product.getId());
            if (redisStock != null) {
                product.setAvailableStock(redisStock);
            }
        }
        return products;
    }

    public SeckillDemoResetResult resetDemo(SeckillDemoRequest request) {
        DemoConfig config = normalize(request);
        ensureNoRunningJob(config.seckillProductId);
        return resetInternal(config);
    }

    public SeckillDemoJobSnapshot startJob(SeckillDemoRequest request) {
        DemoConfig config = normalize(request);
        ensureNoRunningJob(config.seckillProductId);

        SeckillProduct product = seckillProductMapper.selectById(config.seckillProductId);
        if (product == null) {
            throw new IllegalArgumentException("Seckill product does not exist: " + config.seckillProductId);
        }

        String jobId = UUID.randomUUID().toString().replace("-", "");
        DemoJob job = new DemoJob(jobId, config, product);
        jobs.put(jobId, job);
        jobExecutor.submit(() -> runJob(job));
        return snapshot(job);
    }

    public SeckillDemoJobSnapshot getJob(String jobId) {
        DemoJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Demo job does not exist: " + jobId);
        }
        return snapshot(job);
    }

    public SeckillDemoJobSnapshot cancelJob(String jobId) {
        DemoJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Demo job does not exist: " + jobId);
        }
        job.cancelRequested.set(true);
        if ("PENDING".equals(job.status.get()) || "RUNNING".equals(job.status.get())) {
            job.status.set("CANCELING");
        }
        return snapshot(job);
    }

    private void runJob(DemoJob job) {
        job.status.set("PREPARING");
        try {
            resetInternal(job.config);
            Map<String, Object> beforeQueue = seckillService.getQueueMetrics();
            job.queueBefore = getLong(beforeQueue, "queueSize");
            job.deadLetterBefore = getLong(beforeQueue, "deadLetterSize");
            job.doneMarkersBefore = getLong(beforeQueue, "doneMarkers");
            job.stockBefore = seckillService.getStock(job.config.seckillProductId);

            job.startedAtNanos = System.nanoTime();
            job.status.set("RUNNING");
            runWorkers(job);

            job.status.set(job.cancelRequested.get() ? "CANCELED" : "FINISHING");
            waitForAsyncProcessing(job);
            finishJob(job);
        } catch (Exception exception) {
            job.errorMessage.set(exception.getMessage());
            job.status.set("FAILED");
            job.finishedAtNanos = System.nanoTime();
            recordProgress(job, true);
            log.error("Seckill demo job failed: jobId={}", job.jobId, exception);
        }
    }

    private void runWorkers(DemoJob job) throws InterruptedException {
        ExecutorService workerPool = Executors.newFixedThreadPool(job.config.concurrency);
        CountDownLatch latch = new CountDownLatch(job.config.concurrency);
        AtomicInteger sequence = new AtomicInteger(0);

        for (int worker = 0; worker < job.config.concurrency; worker++) {
            workerPool.submit(() -> {
                try {
                    while (!job.cancelRequested.get()) {
                        int index = sequence.getAndIncrement();
                        if (index >= job.config.totalRequests) {
                            return;
                        }
                        runOneAttempt(job, index);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        workerPool.shutdownNow();
    }

    private void runOneAttempt(DemoJob job, int index) {
        long userId = job.config.userIdBase + index;
        long started = System.nanoTime();
        SeckillAttemptResult result;
        try {
            result = seckillService.attemptSeckill(userId, job.config.seckillProductId, job.config.quantity);
        } catch (Exception exception) {
            result = SeckillAttemptResult.failure(
                    SeckillAttemptResult.EXCEPTION,
                    "exception",
                    exception.getMessage() == null ? "Seckill attempt failed" : exception.getMessage()
            );
        }

        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        job.latencyMicros.add(elapsedMicros);
        job.completed.incrementAndGet();
        if (result.isSuccess()) {
            job.success.incrementAndGet();
        } else {
            job.fail.incrementAndGet();
            job.failReasons.computeIfAbsent(result.getReason(), ignored -> new AtomicLong()).incrementAndGet();
        }
        recordProgress(job, false);
    }

    private void waitForAsyncProcessing(DemoJob job) {
        long expected = job.success.get();
        long deadline = System.currentTimeMillis() + ASYNC_WAIT_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline && !job.cancelRequested.get()) {
            Map<String, Object> metrics = seckillService.getQueueMetrics();
            long doneDelta = getLong(metrics, "doneMarkers") - job.doneMarkersBefore;
            long dlqDelta = getLong(metrics, "deadLetterSize") - job.deadLetterBefore;
            if (doneDelta + dlqDelta >= expected) {
                return;
            }
            sleepQuietly(250);
        }
    }

    private void finishJob(DemoJob job) {
        Map<String, Object> afterQueue = seckillService.getQueueMetrics();
        job.stockAfter = seckillService.getStock(job.config.seckillProductId);
        job.queueAfter = getLong(afterQueue, "queueSize");
        job.deadLetterAfter = getLong(afterQueue, "deadLetterSize");
        job.doneMarkersAfter = getLong(afterQueue, "doneMarkers");
        job.retryingMessagesAfter = getLong(afterQueue, "retryingMessages");
        job.finishedAtNanos = System.nanoTime();

        if (!job.cancelRequested.get()) {
            job.status.set("SUCCEEDED");
        }
        recordProgress(job, true);
    }

    private SeckillDemoResetResult resetInternal(DemoConfig config) {
        SeckillProduct product = seckillProductMapper.selectById(config.seckillProductId);
        if (product == null) {
            throw new IllegalArgumentException("Seckill product does not exist: " + config.seckillProductId);
        }

        deleteProductCaches(config.seckillProductId);

        int cleanupUsers = cleanupUserCount(config);
        List<String> messageIds = findDemoMessageIds(config, cleanupUsers);
        int deletedOrders = deleteDemoOrders(messageIds);
        int deletedStreamRecords = deleteStreamRecords(messageIds);
        int deletedRedisKeys = deleteDemoRedisKeys(config, cleanupUsers, messageIds);
        int deletedLocalMessages = deleteDemoLocalMessages(config, cleanupUsers);

        product.setTotalStock(config.stock);
        product.setAvailableStock(config.stock);
        seckillProductMapper.updateById(product);
        syncActivityStock(product, config.stock);

        stringRedisTemplate.delete(STOCK_KEY_PREFIX + config.seckillProductId);
        stringRedisTemplate.opsForValue().set(
                STOCK_KEY_PREFIX + config.seckillProductId,
                String.valueOf(config.stock),
                1L,
                TimeUnit.DAYS
        );

        deleteProductCaches(config.seckillProductId);

        SeckillDemoResetResult result = new SeckillDemoResetResult();
        result.setSeckillProductId(config.seckillProductId);
        result.setActivityId(product.getActivityId());
        result.setProductName(product.getProductName());
        result.setStock(config.stock);
        result.setDeletedOrders(deletedOrders);
        result.setDeletedLocalMessages(deletedLocalMessages);
        result.setDeletedRedisKeys(deletedRedisKeys);
        result.setDeletedStreamRecords(deletedStreamRecords);
        result.setResetTime(LocalDateTime.now());
        return result;
    }

    private void deleteProductCaches(Long seckillProductId) {
        deleteKeys(Set.of(
                ACTIVE_PRODUCTS_CACHE_KEY,
                ENDTIME_CACHE_KEY,
                PRODUCT_EXISTS_CACHE_KEY_PREFIX + seckillProductId,
                STOCK_KEY_PREFIX + seckillProductId + ":success:time"
        ));
        Set<String> upcomingKeys = stringRedisTemplate.keys(UPCOMING_PRODUCTS_CACHE_KEY_PREFIX + "*");
        deleteKeys(upcomingKeys);
    }

    private int deleteDemoRedisKeys(DemoConfig config, int cleanupUsers, List<String> messageIds) {
        int deleted = 0;
        long upperUserId = config.userIdBase + cleanupUsers;
        deleted += deleteKeysInUserRange(
                ORDER_KEY_PREFIX + config.seckillProductId + ":*",
                ORDER_KEY_PREFIX + config.seckillProductId + ":",
                config.userIdBase,
                upperUserId
        );
        deleted += deleteKeysInUserRange(
                RATE_LIMIT_KEY_PREFIX + config.seckillProductId + ":*",
                RATE_LIMIT_KEY_PREFIX + config.seckillProductId + ":",
                config.userIdBase,
                upperUserId
        );

        Set<String> messageKeys = new HashSet<>();
        for (String messageId : messageIds) {
            messageKeys.add(ASYNC_DONE_KEY_PREFIX + messageId);
            messageKeys.add(RETRY_KEY_PREFIX + messageId);
        }
        deleted += deleteKeys(messageKeys);
        return deleted;
    }

    private int deleteKeysInUserRange(String pattern, String prefix, long fromInclusive, long toExclusive) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Set<String> targets = keys.stream()
                .filter(key -> isUserKeyInRange(key, prefix, fromInclusive, toExclusive))
                .collect(Collectors.toSet());
        return deleteKeys(targets);
    }

    private boolean isUserKeyInRange(String key, String prefix, long fromInclusive, long toExclusive) {
        if (key == null || !key.startsWith(prefix)) {
            return false;
        }
        try {
            long userId = Long.parseLong(key.substring(prefix.length()));
            return userId >= fromInclusive && userId < toExclusive;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int deleteKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = stringRedisTemplate.delete(keys);
        return deleted == null ? 0 : deleted.intValue();
    }

    private List<String> findDemoMessageIds(DemoConfig config, int cleanupUsers) {
        return jdbcTemplate.queryForList(
                """
                SELECT message_id
                FROM seckill_local_message
                WHERE seckill_product_id = ?
                  AND user_id >= ?
                  AND user_id < ?
                """,
                String.class,
                config.seckillProductId,
                config.userIdBase,
                config.userIdBase + cleanupUsers
        );
    }

    private int deleteDemoOrders(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (List<String> chunk : chunks(messageIds, 500)) {
            Object[] args = chunk.toArray();
            String placeholders = placeholders(chunk.size());
            deleted += jdbcTemplate.update(
                    "DELETE oi FROM order_item oi INNER JOIN order_info o ON oi.order_id = o.id WHERE o.message_id IN (" + placeholders + ")",
                    args
            );
            deleted += jdbcTemplate.update(
                    "DELETE FROM order_info WHERE message_id IN (" + placeholders + ")",
                    args
            );
        }
        return deleted;
    }

    private int deleteDemoLocalMessages(DemoConfig config, int cleanupUsers) {
        return jdbcTemplate.update(
                """
                DELETE FROM seckill_local_message
                WHERE seckill_product_id = ?
                  AND user_id >= ?
                  AND user_id < ?
                """,
                config.seckillProductId,
                config.userIdBase,
                config.userIdBase + cleanupUsers
        );
    }

    private int deleteStreamRecords(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (List<String> chunk : chunks(messageIds, 500)) {
            RecordId[] ids = chunk.stream().map(RecordId::of).toArray(RecordId[]::new);
            Long count = stringRedisTemplate.opsForStream().delete(SeckillService.SECKILL_ORDER_STREAM_KEY, ids);
            deleted += count == null ? 0 : count.intValue();
        }
        return deleted;
    }

    private void syncActivityStock(SeckillProduct product, int stock) {
        if (product.getActivityId() != null) {
            jdbcTemplate.update(
                    "UPDATE seckill_activity SET total_stock = ?, available_stock = ?, update_time = NOW() WHERE id = ?",
                    stock,
                    stock,
                    product.getActivityId()
            );
            return;
        }
        jdbcTemplate.update(
                "UPDATE seckill_activity SET total_stock = ?, available_stock = ?, update_time = NOW() WHERE product_id = ?",
                stock,
                stock,
                product.getProductId()
        );
    }

    private DemoConfig normalize(SeckillDemoRequest request) {
        if (request == null) {
            request = new SeckillDemoRequest();
        }
        if (request.getSeckillProductId() == null || request.getSeckillProductId() <= 0) {
            throw new IllegalArgumentException("seckillProductId is required");
        }

        int totalRequests = clamp(request.getTotalRequests(), DEFAULT_TOTAL_REQUESTS, 1, MAX_TOTAL_REQUESTS);
        int concurrency = clamp(request.getConcurrency(), DEFAULT_CONCURRENCY, 1, Math.min(MAX_CONCURRENCY, totalRequests));
        int stock = clamp(request.getStock(), DEFAULT_STOCK, 0, MAX_STOCK);
        int quantity = clamp(request.getQuantity(), DEFAULT_QUANTITY, 1, 10);
        long userIdBase = request.getUserIdBase() == null ? DEFAULT_USER_ID_BASE : request.getUserIdBase();
        if (userIdBase <= 0) {
            throw new IllegalArgumentException("userIdBase must be positive");
        }

        return new DemoConfig(request.getSeckillProductId(), totalRequests, concurrency, stock, quantity, userIdBase);
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int actual = value == null ? fallback : value;
        return Math.max(min, Math.min(max, actual));
    }

    private int cleanupUserCount(DemoConfig config) {
        int requested = Math.max(config.totalRequests, DEFAULT_TOTAL_REQUESTS);
        requested = Math.max(requested, config.stock);
        return Math.min(MAX_CLEANUP_USERS, requested + config.concurrency + 100);
    }

    private void ensureNoRunningJob(Long seckillProductId) {
        boolean running = jobs.values().stream()
                .anyMatch(job -> job.config.seckillProductId.equals(seckillProductId)
                        && Set.of("PENDING", "PREPARING", "RUNNING", "FINISHING", "CANCELING").contains(job.status.get()));
        if (running) {
            throw new IllegalStateException("A demo job is already running for this seckill product");
        }
    }

    private void recordProgress(DemoJob job, boolean force) {
        long now = System.nanoTime();
        long previous = job.lastProgressAtNanos.get();
        if (!force && now - previous < TimeUnit.MILLISECONDS.toNanos(500)) {
            return;
        }
        if (!job.lastProgressAtNanos.compareAndSet(previous, now) && !force) {
            return;
        }

        SeckillDemoJobSnapshot snapshot = snapshot(job);
        job.timeline.add(new SeckillDemoPoint(
                snapshot.getElapsedMs(),
                snapshot.getCompleted(),
                snapshot.getSuccess(),
                snapshot.getFail(),
                snapshot.getRequestRps(),
                snapshot.getSuccessQps(),
                snapshot.getP95Ms(),
                snapshot.getP99Ms()
        ));
    }

    private SeckillDemoJobSnapshot snapshot(DemoJob job) {
        LatencyStats latency = latencyStats(job.latencyMicros);
        long elapsedMs = elapsedMs(job);
        int completed = job.completed.get();
        int success = job.success.get();
        int fail = job.fail.get();
        Integer stockBefore = job.stockBefore;
        Integer stockAfter = job.stockAfter;
        int consumed = stockBefore != null && stockAfter != null ? stockBefore - stockAfter : 0;
        int expectedConsumed = success * job.config.quantity;

        SeckillDemoJobSnapshot snapshot = new SeckillDemoJobSnapshot();
        snapshot.setJobId(job.jobId);
        snapshot.setStatus(job.status.get());
        snapshot.setErrorMessage(job.errorMessage.get());
        snapshot.setSeckillProductId(job.config.seckillProductId);
        snapshot.setActivityId(job.product.getActivityId());
        snapshot.setProductName(job.product.getProductName());
        snapshot.setTotalRequests(job.config.totalRequests);
        snapshot.setConcurrency(job.config.concurrency);
        snapshot.setConfiguredStock(job.config.stock);
        snapshot.setQuantity(job.config.quantity);
        snapshot.setUserIdBase(job.config.userIdBase);
        snapshot.setCompleted(completed);
        snapshot.setSuccess(success);
        snapshot.setFail(fail);
        snapshot.setElapsedMs(elapsedMs);
        snapshot.setRequestRps(rate(completed, elapsedMs));
        snapshot.setSuccessQps(rate(success, elapsedMs));
        snapshot.setAvgMs(latency.avgMs);
        snapshot.setP50Ms(latency.p50Ms);
        snapshot.setP95Ms(latency.p95Ms);
        snapshot.setP99Ms(latency.p99Ms);
        snapshot.setStockBefore(stockBefore);
        snapshot.setStockAfter(stockAfter);
        snapshot.setConsumedStock(consumed);
        snapshot.setOversold(Math.max(0, expectedConsumed - job.config.stock));
        snapshot.setQueueBefore(job.queueBefore);
        snapshot.setQueueAfter(job.queueAfter);
        snapshot.setQueueDelta(job.queueAfter - job.queueBefore);
        snapshot.setDeadLetterBefore(job.deadLetterBefore);
        snapshot.setDeadLetterAfter(job.deadLetterAfter);
        long deadLetterDelta = job.deadLetterAfter - job.deadLetterBefore;
        snapshot.setDeadLetterDelta(deadLetterDelta);
        snapshot.setDlqDelta(deadLetterDelta);
        snapshot.setDoneMarkersBefore(job.doneMarkersBefore);
        snapshot.setDoneMarkersAfter(job.doneMarkersAfter);
        snapshot.setDoneMarkersDelta(job.doneMarkersAfter - job.doneMarkersBefore);
        snapshot.setRetryingMessagesAfter(job.retryingMessagesAfter);
        snapshot.setNoOversell(expectedConsumed <= job.config.stock && (stockAfter == null || stockAfter >= 0));
        snapshot.setStockMatch(stockBefore == null || stockAfter == null || consumed == expectedConsumed);
        snapshot.setNoNewDlq(job.deadLetterAfter - job.deadLetterBefore == 0);
        snapshot.setFailReasons(failReasons(job));
        synchronized (job.timeline) {
            snapshot.setTimeline(new ArrayList<>(job.timeline));
        }
        return snapshot;
    }

    private long elapsedMs(DemoJob job) {
        long started = job.startedAtNanos;
        if (started == 0L) {
            return 0L;
        }
        long finished = job.finishedAtNanos == 0L ? System.nanoTime() : job.finishedAtNanos;
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, finished - started));
    }

    private LatencyStats latencyStats(List<Long> latencyMicros) {
        List<Long> values;
        synchronized (latencyMicros) {
            values = new ArrayList<>(latencyMicros);
        }
        if (values.isEmpty()) {
            return new LatencyStats(0, 0, 0, 0);
        }
        values.sort(Comparator.naturalOrder());
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return new LatencyStats(
                round2(sum / 1000.0 / values.size()),
                percentile(values, 0.50),
                percentile(values, 0.95),
                percentile(values, 0.99)
        );
    }

    private double percentile(List<Long> sortedMicros, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sortedMicros.size()) - 1);
        return round2(sortedMicros.get(index) / 1000.0);
    }

    private double rate(int count, long elapsedMs) {
        if (elapsedMs <= 0) {
            return 0;
        }
        return round2(count * 1000.0 / elapsedMs);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Map<String, Long> failReasons(DemoJob job) {
        return job.failReasons.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().get(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private <T> List<List<T>> chunks(List<T> source, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int index = 0; index < source.size(); index += size) {
            chunks.add(source.subList(index, Math.min(source.size(), index + size)));
        }
        return chunks;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        jobExecutor.shutdownNow();
    }

    private record DemoConfig(Long seckillProductId, int totalRequests, int concurrency, int stock, int quantity, long userIdBase) {
    }

    private record LatencyStats(double avgMs, double p50Ms, double p95Ms, double p99Ms) {
    }

    private static class DemoJob {
        private final String jobId;
        private final DemoConfig config;
        private final SeckillProduct product;
        private final AtomicReference<String> status = new AtomicReference<>("PENDING");
        private final AtomicReference<String> errorMessage = new AtomicReference<>();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger fail = new AtomicInteger();
        private final AtomicReference<Boolean> cancelRequested = new AtomicReference<>(false);
        private final Map<String, AtomicLong> failReasons = new ConcurrentHashMap<>();
        private final List<Long> latencyMicros = Collections.synchronizedList(new ArrayList<>());
        private final List<SeckillDemoPoint> timeline = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong lastProgressAtNanos = new AtomicLong();

        private volatile long startedAtNanos;
        private volatile long finishedAtNanos;
        private volatile Integer stockBefore;
        private volatile Integer stockAfter;
        private volatile long queueBefore;
        private volatile long queueAfter;
        private volatile long deadLetterBefore;
        private volatile long deadLetterAfter;
        private volatile long doneMarkersBefore;
        private volatile long doneMarkersAfter;
        private volatile long retryingMessagesAfter;

        private DemoJob(String jobId, DemoConfig config, SeckillProduct product) {
            this.jobId = jobId;
            this.config = config;
            this.product = product;
        }
    }
}
