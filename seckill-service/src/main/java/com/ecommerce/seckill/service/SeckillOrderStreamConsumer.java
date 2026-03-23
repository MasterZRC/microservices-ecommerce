package com.ecommerce.seckill.service;

import com.ecommerce.seckill.entity.LocalMessage;
import com.ecommerce.seckill.mapper.LocalMessageMapper;
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
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀订单Stream消费者（支持多实例部署）
 *
 * 核心创新点：
 * 1. 多消费者实例支持：消费者名称动态生成，基于主机名+UUID
 * 2. 本地消息表备份：作为Redis Stream的备份，保证消息不丢失
 * 3. 定时重试机制：定时扫描本地消息表，重试失败消息
 * 4. 消息确认优化：成功处理后及时ACK，避免消息重复消费
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderStreamConsumer {

    private static final String GROUP = "seckill-order-group";
    private static final String CONSUMER_PREFIX = "seckill-order-consumer-";
    public static final String RETRY_KEY_PREFIX = "seckill:retry:";
    public static final String DLQ_STREAM_KEY = "seckill:stream:orders:dlq";
    public static final String LOCAL_MESSAGE_CONSUMER_NAME = "seckill-local-consumer";

    @Value("${seckill.queue.max-retry:5}")
    private int maxRetry;

    @Value("${spring.application.name:seckill-service}")
    private String applicationName;

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillService seckillService;
    private final LocalMessageMapper localMessageMapper;

    private String consumerName;
    private String consumerInstanceId;

    @PostConstruct
    public void initConsumerGroup() {
        // 动态生成消费者名称（支持多实例部署）
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            consumerInstanceId = hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
            consumerName = CONSUMER_PREFIX + consumerInstanceId;
        } catch (Exception e) {
            consumerName = CONSUMER_PREFIX + UUID.randomUUID().toString().substring(0, 8);
            consumerInstanceId = consumerName;
        }
        log.info("秒杀消费者初始化: consumerName={}", consumerName);

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
                    Consumer.from(GROUP, consumerName),
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
                    Consumer.from(GROUP, consumerName),
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
        String messageId = record.getId().getValue();

        // 从本地消息表查询（保证消息不丢失）
        LocalMessage localMsg = localMessageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getMessageId, messageId)
                .eq(LocalMessage::getStatus, "confirmed")
        ).stream().findFirst().orElse(null);

        if (localMsg != null) {
            // 消息已确认，直接ACK
            acknowledge(record.getId());
            clearRetry(messageId);
            return;
        }

        try {
            if (seckillService.isAsyncDone(messageId)) {
                // 保存到本地消息表作为备份
                saveLocalMessage(messageId, null, null, null, "confirmed");
                acknowledge(record.getId());
                clearRetry(messageId);
                return;
            }

            Long userId = Long.parseLong(String.valueOf(record.getValue().get("userId")));
            Long seckillProductId = Long.parseLong(String.valueOf(record.getValue().get("seckillProductId")));
            Integer quantity = Integer.parseInt(String.valueOf(record.getValue().get("quantity")));

            // 先保存到本地消息表（保证消息不丢失）
            saveLocalMessage(messageId, userId, seckillProductId, quantity, "pending");

            boolean created = seckillService.submitSeckillOrder(userId, seckillProductId, quantity, messageId);
            if (created) {
                seckillService.markAsyncDone(messageId);
                seckillService.asyncOrder(userId, seckillProductId, quantity, messageId);
                clearRetry(messageId);
                // 更新本地消息表状态为已确认
                updateLocalMessageStatus(messageId, "confirmed");
                acknowledge(record.getId());
                return;
            }

            int retry = increaseRetry(messageId);
            if (retry >= maxRetry) {
                seckillService.compensateAfterAsyncFailure(userId, seckillProductId, quantity);
                seckillService.markAsyncDone(messageId);
                writeDeadLetter(messageId, userId, seckillProductId, quantity, retry);
                clearRetry(messageId);
                // 更新本地消息表状态为失败
                updateLocalMessageStatus(messageId, "failed");
                acknowledge(record.getId());
                log.error("秒杀消息重试耗尽并补偿: messageId={}, retry={}", messageId, retry);
            } else {
                log.warn("秒杀消息处理失败，等待重试: messageId={}, retry={}", messageId, retry);
            }
        } catch (Exception perRecordException) {
            log.error("处理秒杀异步消息失败: recordId={}", record.getId(), perRecordException);
            // 更新本地消息表状态为失败
            updateLocalMessageStatus(messageId, "failed");
        }
    }

    /**
     * 保存消息到本地消息表（备份）
     */
    private void saveLocalMessage(String messageId, Long userId, Long seckillProductId, Integer quantity, String status) {
        try {
            LocalMessage msg = new LocalMessage();
            msg.setMessageId(messageId);
            msg.setUserId(userId);
            msg.setSeckillProductId(seckillProductId);
            msg.setQuantity(quantity);
            msg.setStatus(status);
            msg.setRetryCount(0);
            msg.setCreateTime(LocalDateTime.now());
            msg.setUpdateTime(LocalDateTime.now());
            localMessageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存本地消息失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    /**
     * 更新本地消息状态
     */
    private void updateLocalMessageStatus(String messageId, String status) {
        try {
            var msgs = localMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LocalMessage>()
                    .eq(LocalMessage::getMessageId, messageId)
            );
            if (!msgs.isEmpty()) {
                LocalMessage msg = msgs.get(0);
                msg.setStatus(status);
                if ("confirmed".equals(status)) {
                    msg.setConfirmTime(LocalDateTime.now());
                }
                msg.setUpdateTime(LocalDateTime.now());
                localMessageMapper.updateById(msg);
            }
        } catch (Exception e) {
            log.warn("更新本地消息状态失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    /**
     * 定时扫描本地消息表，重试失败消息
     */
    @Scheduled(fixedDelayString = "${seckill.queue.local-retry-interval-ms:60000}")
    public void retryLocalMessages() {
        try {
            List<LocalMessage> pendingMessages = localMessageMapper.selectPendingMessages("pending", maxRetry, 100);
            if (pendingMessages.isEmpty()) {
                return;
            }
            log.info("本地消息表重试: 发现{}条待处理消息", pendingMessages.size());

            for (LocalMessage msg : pendingMessages) {
                try {
                    boolean created = seckillService.submitSeckillOrder(
                        msg.getUserId(), msg.getSeckillProductId(), msg.getQuantity(), msg.getMessageId());
                    if (created) {
                        seckillService.asyncOrder(msg.getUserId(), msg.getSeckillProductId(), msg.getQuantity(), msg.getMessageId());
                        localMessageMapper.confirmMessage(msg.getId());
                        log.debug("本地消息重试成功: messageId={}", msg.getMessageId());
                    } else {
                        localMessageMapper.updateFailed(msg.getId(), "重试提交失败");
                    }
                } catch (Exception e) {
                    localMessageMapper.updateFailed(msg.getId(), e.getMessage());
                    log.warn("本地消息重试失败: messageId={}, error={}", msg.getMessageId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("本地消息表重试异常", e);
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
