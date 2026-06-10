package com.ecommerce.seckill.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeckillDemoResetResult {
    private Long seckillProductId;
    private Long activityId;
    private String productName;
    private int stock;
    private int deletedOrders;
    private int deletedLocalMessages;
    private int deletedRedisKeys;
    private int deletedStreamRecords;
    private LocalDateTime resetTime;
}
