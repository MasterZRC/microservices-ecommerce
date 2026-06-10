package com.ecommerce.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillDemoPoint {
    private long elapsedMs;
    private int completed;
    private int success;
    private int fail;
    private double requestRps;
    private double successQps;
    private double p95Ms;
    private double p99Ms;
}
