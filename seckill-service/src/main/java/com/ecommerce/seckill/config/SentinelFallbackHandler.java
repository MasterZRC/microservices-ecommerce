package com.ecommerce.seckill.config;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Sentinel 熔断和限流降级处理器
 * 所有 @SentinelResource 的 blockHandler 和 fallback 统一在此处理
 */
@Slf4j
@RestController
public class SentinelFallbackHandler {

    // ==================== /start 降级处理 ====================

    /**
     * 限流降级：请求被 Sentinel 限流时返回友好提示
     */
    public Map<String, Object> startBlockHandler(
            @RequestParam Long userId,
            @RequestParam Long seckillProductId,
            @RequestParam(defaultValue = "1") Integer quantity,
            HttpServletRequest request,
            BlockException ex) {
        log.warn("秒杀接口触发限流: userId={}, seckillProductId={}, blockType={}",
                userId, seckillProductId, ex.getClass().getSimpleName());
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", "RATE_LIMITED");
        result.put("message", "秒杀太火爆，请稍后再试");
        result.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        return result;
    }

    /**
     * 熔断降级：秒杀接口发生异常或熔断触发时返回降级响应
     */
    public Map<String, Object> startFallbackHandler(
            @RequestParam Long userId,
            @RequestParam Long seckillProductId,
            @RequestParam(defaultValue = "1") Integer quantity,
            HttpServletRequest request,
            Throwable throwable) {
        log.error("秒杀接口发生异常，触发熔断降级: userId={}, seckillProductId={}",
                userId, seckillProductId, throwable);
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", "SERVICE_DEGRADED");
        result.put("message", "系统繁忙，请稍后再试");
        result.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        return result;
    }

    // ==================== /products 降级处理 ====================

    public Map<String, Object> productsBlockHandler(HttpServletRequest request, BlockException ex) {
        log.warn("秒杀商品列表接口触发限流: path={}, blockType={}",
                request.getRequestURI(), ex.getClass().getSimpleName());
        Map<String, Object> result = new HashMap<>();
        result.put("products", java.util.Collections.emptyList());
        result.put("count", 0);
        result.put("code", "RATE_LIMITED");
        result.put("message", "请求过于频繁，请稍后刷新");
        return result;
    }

    public Map<String, Object> productsFallbackHandler(HttpServletRequest request, Throwable throwable) {
        log.error("秒杀商品列表接口异常: path={}", request.getRequestURI(), throwable);
        Map<String, Object> result = new HashMap<>();
        result.put("products", java.util.Collections.emptyList());
        result.put("count", 0);
        result.put("code", "SERVICE_DEGRADED");
        result.put("message", "商品加载失败，请稍后重试");
        return result;
    }
}
