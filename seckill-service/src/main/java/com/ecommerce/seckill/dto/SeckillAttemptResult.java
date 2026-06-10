package com.ecommerce.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillAttemptResult {

    public static final long SUCCESS = 1L;
    public static final long DUPLICATE = -1L;
    public static final long STOCK_EXHAUSTED = -2L;
    public static final long STOCK_UNINITIALIZED = -3L;
    public static final long RATE_LIMITED = -4L;
    public static final long PRODUCT_NOT_FOUND = -5L;
    public static final long SCRIPT_ERROR = -6L;
    public static final long EXCEPTION = -7L;

    private boolean success;
    private long code;
    private String reason;
    private String message;

    public static SeckillAttemptResult success() {
        return new SeckillAttemptResult(true, SUCCESS, "success", "Seckill succeeded");
    }

    public static SeckillAttemptResult failure(long code, String reason, String message) {
        return new SeckillAttemptResult(false, code, reason, message);
    }
}
