package com.ecommerce.seckill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderStreamConsumer {

    private static final String GROUP = "seckill-order-group";
    private static final String CONSUMER = "seckill-order-consumer-1";
    public static final String RETRY_KEY_PREFIX = "seckill:retry:";
    public static final String DLQ_STREAM_KEY = "seckill:stream:orders:dlq";

    @Value("${seckill.queue.max-retry:5}")
    private int maxRetry;

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillService seckillService;

    @PostConstruct
    public void initConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(SeckillService.SECKILL_ORDER_STREAM_KEY, ReadOffset.latest(), GROUP);
            log.info("创建秒杀消费组成功: {}", GROUP);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("BUSYGROUP")) {
                log.info("秒杀消费组已存在: {}", GROUP);
                return;
            }

            if (message.contains("requires the key to exist")) {
                stringRedisTemplate.opsForStream().add(SeckillService.SECKILL_ORDER_STREAM_KEY,
                        Map.of("init", "1", "timestamp", String.valueOf(System.currentTimeMillis())));
                stringRedisTemplate.opsForStream().createGroup(SeckillService.SECKILL_ORDER_STREAM_KEY, ReadOffset.latest(), GROUP);
                log.info("创建秒杀消费组成功(补偿初始化): {}", GROUP);
                return;
            }

            log.warn("初始化秒杀消费组异常", exception);
        }
    }

    @Scheduled(fixedDelayString = "${seckill.queue.poll-interval-ms:200}")
    public void consume() {
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(20).block(Duration.ofMillis(200)),
                    StreamOffset.create(SeckillService.SECKILL_ORDER_STREAM_KEY, ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                processRecord(record);
            }
        } catch (Exception exception) {
            log.error("消费秒杀异步消息异常", exception);
        }
    }

    @Scheduled(fixedDelayString = "${seckill.queue.pending-poll-interval-ms:1500}")
    public void consumePending() {
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(20),
                    StreamOffset.create(SeckillService.SECKILL_ORDER_STREAM_KEY, ReadOffset.from("0"))
            );

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                processRecord(record);
            }
        } catch (Exception exception) {
            log.error("消费秒杀pending消息异常", exception);
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            String messageId = record.getId().getValue();

            if (seckillService.isAsyncDone(messageId)) {
                acknowledge(record.getId());
                clearRetry(messageId);
                return;
            }

            Long userId = Long.parseLong(String.valueOf(record.getValue().get("userId")));
            Long seckillProductId = Long.parseLong(String.valueOf(record.getValue().get("seckillProductId")));
            Integer quantity = Integer.parseInt(String.valueOf(record.getValue().get("quantity")));

            boolean created = seckillService.submitSeckillOrder(userId, seckillProductId, quantity, messageId);
            if (created) {
                seckillService.markAsyncDone(messageId);
                seckillService.asyncOrder(userId, seckillProductId, quantity, messageId);
                clearRetry(messageId);
                acknowledge(record.getId());
                return;
            }

            int retry = increaseRetry(messageId);
            if (retry >= maxRetry) {
                seckillService.compensateAfterAsyncFailure(userId, seckillProductId, quantity);
                seckillService.markAsyncDone(messageId);
                writeDeadLetter(messageId, userId, seckillProductId, quantity, retry);
                clearRetry(messageId);
                acknowledge(record.getId());
                log.error("秒杀消息重试耗尽并补偿: messageId={}, retry={}", messageId, retry);
            } else {
                log.warn("秒杀消息处理失败，等待重试: messageId={}, retry={}", messageId, retry);
            }
        } catch (Exception perRecordException) {
            log.error("处理秒杀异步消息失败: recordId={}", record.getId(), perRecordException);
        }
    }

    private void acknowledge(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(SeckillService.SECKILL_ORDER_STREAM_KEY, GROUP, recordId);
    }

    private int increaseRetry(String messageId) {
        Long retry = stringRedisTemplate.opsForValue().increment(RETRY_KEY_PREFIX + messageId);
        stringRedisTemplate.expire(RETRY_KEY_PREFIX + messageId, Duration.ofDays(1));
        return retry == null ? 1 : retry.intValue();
    }

    private void clearRetry(String messageId) {
        stringRedisTemplate.delete(RETRY_KEY_PREFIX + messageId);
    }

    private void writeDeadLetter(String messageId, Long userId, Long seckillProductId, Integer quantity, int retry) {
        stringRedisTemplate.opsForStream().add(DLQ_STREAM_KEY, Map.of(
                "messageId", messageId,
                "userId", String.valueOf(userId),
                "seckillProductId", String.valueOf(seckillProductId),
                "quantity", String.valueOf(quantity),
                "retry", String.valueOf(retry),
                "eventTime", String.valueOf(System.currentTimeMillis())
        ));
    }
}
