package com.ecommerce.seckill.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ecommerce.seckill.config.SentinelFallbackHandler;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill")
@Tag(name = "秒杀服务", description = "秒杀活动、高并发处理接口")
public class SeckillController {

    private final SeckillService seckillService;
    private final SentinelFallbackHandler sentinelHandler;

    public SeckillController(SeckillService seckillService, SentinelFallbackHandler sentinelHandler) {
        this.seckillService = seckillService;
        this.sentinelHandler = sentinelHandler;
    }

    @GetMapping("/products")
    @Operation(summary = "获取进行中的秒杀商品", description = "获取当前正在进行秒杀活动的商品列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    @SentinelResource(
            value = "seckill-products",
            blockHandler = "productsBlockHandler",
            fallback = "productsFallbackHandler",
            blockHandlerClass = SentinelFallbackHandler.class,
            fallbackClass = SentinelFallbackHandler.class
    )
    public ResponseEntity<Map<String, Object>> getActiveProducts() {
        List<SeckillProduct> products = seckillService.getActiveSeckillProducts();
        Map<String, Object> result = new HashMap<>();
        result.put("products", products);
        result.put("count", products.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/products/upcoming")
    @Operation(summary = "获取即将开始的秒杀商品", description = "获取即将开始的秒杀商品列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    public ResponseEntity<Map<String, Object>> getUpcomingProducts(
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "6") int limit) {
        List<SeckillProduct> products = seckillService.getUpcomingSeckillProducts(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("products", products);
        result.put("count", products.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/activity")
    @Operation(summary = "获取秒杀活动信息", description = "获取最近秒杀活动的结束时间")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    public ResponseEntity<Map<String, Object>> getActivityInfo() {
        LocalDateTime endTime = seckillService.getNearestEndTime();
        Map<String, Object> result = new HashMap<>();
        if (endTime != null) {
            result.put("endTime", endTime);
            result.put("hasActiveActivity", true);
        } else {
            result.put("hasActiveActivity", false);
            result.put("message", "当前无进行中的秒杀活动");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    @Operation(summary = "开始秒杀", description = "用户参与秒杀活动，尝试抢购商品")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "秒杀结果"),
            @ApiResponse(responseCode = "429", description = "请求过于频繁"),
            @ApiResponse(responseCode = "503", description = "服务降级")
    })
    @SentinelResource(
            value = "seckill-start",
            blockHandler = "startBlockHandler",
            fallback = "startFallbackHandler",
            blockHandlerClass = SentinelFallbackHandler.class,
            fallbackClass = SentinelFallbackHandler.class
    )
    public ResponseEntity<Map<String, Object>> startSeckill(
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "秒杀商品ID") @RequestParam Long seckillProductId,
            @Parameter(description = "抢购数量") @RequestParam(defaultValue = "1") Integer quantity) {
        boolean success = seckillService.trySeckill(userId, seckillProductId, quantity);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "秒杀成功" : "秒杀失败");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stock")
    @Operation(summary = "获取秒杀商品库存", description = "获取指定秒杀商品的剩余库存")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    public ResponseEntity<Map<String, Object>> getStock(
            @Parameter(description = "秒杀商品ID", required = true) @RequestParam Long seckillProductId) {
        Integer stock = seckillService.getStock(seckillProductId);
        Map<String, Object> result = new HashMap<>();
        result.put("seckillProductId", seckillProductId);
        result.put("stock", stock);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/queue/size")
    @Operation(summary = "获取消息队列大小", description = "获取秒杀消息队列的当前大小")
    public ResponseEntity<Map<String, Object>> getQueueSize() {
        Map<String, Object> result = new HashMap<>();
        result.put("queueSize", seckillService.getQueueSize());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/queue/metrics")
    @Operation(summary = "获取队列指标", description = "获取秒杀队列的处理指标")
    public ResponseEntity<Map<String, Object>> getQueueMetrics() {
        return ResponseEntity.ok(seckillService.getQueueMetrics());
    }

    @PostMapping("/init")
    @Operation(summary = "初始化秒杀库存", description = "初始化指定秒杀商品的库存（用于测试）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "初始化成功")
    })
    public ResponseEntity<Map<String, String>> initStock(
            @Parameter(description = "秒杀商品ID") @RequestParam Long seckillProductId,
            @Parameter(description = "初始库存") @RequestParam Integer stock) {
        seckillService.initStock(seckillProductId, stock);
        Map<String, String> result = new HashMap<>();
        result.put("message", "库存初始化成功");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/cache")
    @Operation(summary = "清除秒杀缓存", description = "清除秒杀服务的所有缓存数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "清除成功")
    })
    public ResponseEntity<Map<String, String>> clearCache() {
        seckillService.clearCache();
        Map<String, String> result = new HashMap<>();
        result.put("message", "缓存已清除");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查 Redis、MySQL、消息队列状态")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "seckill-service");
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now().toString());

        // 检查 Redis
        try {
            String pong = seckillService.pingRedis();
            health.put("redis", pong.equals("PONG") ? "UP" : "DOWN");
        } catch (Exception e) {
            health.put("redis", "DOWN");
            health.put("status", "DEGRADED");
        }

        // 检查消息队列积压
        try {
            long queueSize = seckillService.getQueueSize();
            health.put("queueSize", queueSize);
            health.put("queueStatus", queueSize > 1000 ? "BACKLOGGED" : "HEALTHY");
        } catch (Exception e) {
            health.put("queueStatus", "UNKNOWN");
        }

        return ResponseEntity.ok(health);
    }
}