package com.ecommerce.admin.controller;

import com.ecommerce.admin.dto.common.ApiResponse;
import com.ecommerce.admin.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 经营分析专用接口，主要供 agent-service 的管理 Agent 工具调用。
 *
 * 所有接口都是只读聚合，鉴权与一般 admin 接口一致（X-Admin-Id Header 由网关注入）。
 */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@Tag(name = "经营分析", description = "订单/销售/曝光/点击/取消率等聚合指标")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/order-status-distribution")
    @Operation(summary = "订单状态分布")
    public ApiResponse<List<Map<String, Object>>> orderStatusDistribution(
            @Parameter(description = "回看天数") @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(analyticsService.orderStatusDistribution(days));
    }

    @GetMapping("/sales-trend")
    @Operation(summary = "销售时序（按天）")
    public ApiResponse<List<Map<String, Object>>> salesTrend(
            @Parameter(description = "回看天数") @RequestParam(defaultValue = "14") int days) {
        return ApiResponse.success(analyticsService.salesTrendDaily(days));
    }

    @GetMapping("/top-products")
    @Operation(summary = "商品 TopN（销量/曝光/点击）")
    public ApiResponse<List<Map<String, Object>>> topProducts(
            @Parameter(description = "维度: sales|exposure|click")
            @RequestParam(defaultValue = "sales") String metric,
            @Parameter(description = "回看天数") @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(analyticsService.topProducts(metric, days, limit));
    }

    @GetMapping("/cancellation-rate")
    @Operation(summary = "订单取消率")
    public ApiResponse<Map<String, Object>> cancellationRate(
            @Parameter(description = "回看天数") @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(analyticsService.cancellationRate(days));
    }

    @GetMapping("/category-performance")
    @Operation(summary = "类目业绩")
    public ApiResponse<List<Map<String, Object>>> categoryPerformance(
            @Parameter(description = "回看天数") @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(analyticsService.categoryPerformance(days));
    }

    @PostMapping("/sql")
    @Operation(summary = "执行只读 SQL（agent-service 内部使用，调用方需自行 sql_guard 校验）",
               description = "Body: {\"sql\":\"SELECT ...\"}。强制要求 SELECT 且应已附 LIMIT。")
    public ApiResponse<List<Map<String, Object>>> executeReadonlySql(@RequestBody Map<String, String> body) {
        String sql = body == null ? null : body.get("sql");
        if (sql == null || sql.isBlank()) {
            return ApiResponse.error(400, "sql 不能为空");
        }
        // 二重防御：拒绝任何疑似写操作的关键字
        String upper = sql.toUpperCase();
        for (String kw : new String[]{"INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
                                      "ALTER", "CREATE", "GRANT", "REVOKE", "RENAME"}) {
            if (upper.contains(kw)) {
                return ApiResponse.error(400, "拒绝执行非只读 SQL：检测到 " + kw);
            }
        }
        try {
            return ApiResponse.success(analyticsService.executeReadonlySql(sql));
        } catch (Exception e) {
            return ApiResponse.error(500, "SQL 执行失败：" + e.getMessage());
        }
    }
}
