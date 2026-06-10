package com.ecommerce.seckill.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class SeckillDemoJobSnapshot {
    private String jobId;
    private String status;
    private String errorMessage;

    private Long seckillProductId;
    private Long activityId;
    private String productName;
    private int totalRequests;
    private int concurrency;
    private int configuredStock;
    private int quantity;
    private long userIdBase;

    private int completed;
    private int success;
    private int fail;
    private long elapsedMs;
    private double requestRps;
    private double successQps;

    private double avgMs;
    private double p50Ms;
    private double p95Ms;
    private double p99Ms;

    private Integer stockBefore;
    private Integer stockAfter;
    private int consumedStock;
    private int oversold;

    private long queueBefore;
    private long queueAfter;
    private long queueDelta;
    private long deadLetterBefore;
    private long deadLetterAfter;
    private long deadLetterDelta;
    private long dlqDelta;
    private long doneMarkersBefore;
    private long doneMarkersAfter;
    private long doneMarkersDelta;
    private long retryingMessagesAfter;

    private boolean noOversell;
    private boolean stockMatch;
    private boolean noNewDlq;

    private Map<String, Long> failReasons = new LinkedHashMap<>();
    private List<SeckillDemoPoint> timeline = new ArrayList<>();
}
