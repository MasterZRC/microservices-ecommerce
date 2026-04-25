package com.ecommerce.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 告警管理控制器
 *
 * 接收 Prometheus AlertManager 的告警通知，并存储到 Redis 中供前端展示
 *
 * @author ecommerce
 */
@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "告警管理", description = "接收 Prometheus AlertManager 告警通知")
public class AlertController {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String ALERT_HISTORY_KEY = "alerts:history";
    private static final String ALERT_ACTIVE_KEY = "alerts:active";
    private static final long ALERT_EXPIRE_DAYS = 7;

    /**
     * 接收 AlertManager 告警通知
     *
     * AlertManager 会发送 POST 请求到该接口
     */
    @PostMapping("/webhook")
    @Operation(summary = "接收告警通知", description = "接收 Prometheus AlertManager 的 webhook 告警通知")
    public ResponseEntity<Void> receiveAlert(@RequestBody List<AlertNotification> alerts) {
        log.info("[AlertController] 收到 AlertManager 告警，数量: {}", alerts.size());

        for (AlertNotification alert : alerts) {
            try {
                processAlert(alert);
            } catch (Exception e) {
                log.error("[AlertController] 处理告警失败: {}", e.getMessage(), e);
            }
        }

        return ResponseEntity.ok().build();
    }

    /**
     * 获取活跃告警列表
     */
    @GetMapping("/active")
    @Operation(summary = "获取活跃告警", description = "获取当前未解决的告警列表")
    public ResponseEntity<List<Map<String, Object>>> getActiveAlerts() {
        try {
            List<Object> alerts = redisTemplate.opsForList().range(ALERT_ACTIVE_KEY, 0, -1);
            List<Map<String, Object>> result = alerts.stream()
                    .filter(m -> m instanceof Map)
                    .map(m -> (Map<String, Object>) m)
                    .toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[AlertController] 获取活跃告警失败: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 获取告警历史
     */
    @GetMapping("/history")
    @Operation(summary = "获取告警历史", description = "获取历史告警列表")
    public ResponseEntity<List<Map<String, Object>>> getAlertHistory(
            @RequestParam(defaultValue = "100") int limit) {
        try {
            List<Object> alerts = redisTemplate.opsForList().range(ALERT_HISTORY_KEY, 0, limit - 1);
            List<Map<String, Object>> result = alerts.stream()
                    .filter(m -> m instanceof Map)
                    .map(m -> (Map<String, Object>) m)
                    .toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[AlertController] 获取告警历史失败: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 处理告警通知
     */
    private void processAlert(AlertNotification alert) {
        Map<String, Object> alertData = new HashMap<>();
        alertData.put("name", alert.getAlertname());
        alertData.put("status", alert.getStatus());
        alertData.put("labels", alert.getLabels());
        alertData.put("annotations", alert.getAnnotations());
        alertData.put("startsAt", alert.getStartsAt());
        alertData.put("endsAt", alert.getEndsAt());
        alertData.put("generatorURL", alert.getGeneratorURL());
        alertData.put("timestamp", Instant.now().toEpochMilli());

        // 根据告警状态处理
        if ("firing".equals(alert.getStatus())) {
            // 活跃告警
            redisTemplate.opsForList().leftPush(ALERT_ACTIVE_KEY, alertData);
            redisTemplate.opsForList().trim(ALERT_ACTIVE_KEY, 0, 999); // 最多保留1000条
            log.warn("[Alert] 触发告警: {} - {}", alert.getAlertname(),
                    alert.getAnnotations() != null ? alert.getAnnotations().get("summary") : "无描述");
        } else if ("resolved".equals(alert.getStatus())) {
            // 告警恢复，移除活跃告警
            removeFromActiveAlerts(alert);
            log.info("[Alert] 告警恢复: {}", alert.getAlertname());
        }

        // 添加到历史记录
        redisTemplate.opsForList().leftPush(ALERT_HISTORY_KEY, alertData);
        redisTemplate.opsForList().trim(ALERT_HISTORY_KEY, 0, 9999); // 最多保留10000条
        redisTemplate.expire(ALERT_HISTORY_KEY, ALERT_EXPIRE_DAYS, TimeUnit.DAYS);
    }

    /**
     * 从活跃告警中移除已恢复的告警
     */
    private void removeFromActiveAlerts(AlertNotification alert) {
        try {
            List<Object> activeAlerts = redisTemplate.opsForList().range(ALERT_ACTIVE_KEY, 0, -1);
            if (activeAlerts != null) {
                for (Object obj : activeAlerts) {
                    if (obj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> existingAlert = (Map<String, Object>) obj;
                        if (alert.getAlertname().equals(existingAlert.get("name"))) {
                            redisTemplate.opsForList().remove(ALERT_ACTIVE_KEY, 1, obj);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AlertController] 移除活跃告警失败: {}", e.getMessage());
        }
    }

    // ==================== AlertManager 通知模型 ====================

    @lombok.Data
    public static class AlertNotification {
        private String status;
        private String alertname;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String startsAt;
        private String endsAt;
        private String generatorURL;
        private String fingerprint;
    }
}
