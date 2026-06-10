package com.ecommerce.seckill.dto;

import lombok.Data;

@Data
public class SeckillDemoRequest {
    private Long seckillProductId;
    private Integer totalRequests;
    private Integer concurrency;
    private Integer stock;
    private Integer quantity;
    private Long userIdBase;
}
