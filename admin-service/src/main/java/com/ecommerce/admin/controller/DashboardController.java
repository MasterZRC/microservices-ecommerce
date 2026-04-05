package com.ecommerce.admin.controller;

import com.ecommerce.admin.dto.common.ApiResponse;
import com.ecommerce.admin.dto.dashboard.DashboardStatsResponse;
import com.ecommerce.admin.dto.order.OrderResponse;
import com.ecommerce.admin.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "仪表盘", description = "数据概览统计")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    @Operation(summary = "获取核心指标统计")
    public ApiResponse<DashboardStatsResponse> getStats() {
        DashboardStatsResponse stats = dashboardService.getStats();
        return ApiResponse.success(stats);
    }

    @GetMapping("/recent-orders")
    @Operation(summary = "获取最新订单")
    public ApiResponse<List<OrderResponse>> getRecentOrders(
            @Parameter(description = "查询数量") @RequestParam(defaultValue = "10") int limit) {
        List<OrderResponse> orders = dashboardService.getRecentOrders(limit);
        return ApiResponse.success(orders);
    }
}
