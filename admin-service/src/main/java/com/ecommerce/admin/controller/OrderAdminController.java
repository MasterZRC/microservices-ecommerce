package com.ecommerce.admin.controller;

import com.ecommerce.admin.dto.common.ApiResponse;
import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.order.OrderResponse;
import com.ecommerce.admin.dto.order.OrderStatusRequest;
import com.ecommerce.admin.service.OrderAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@Tag(name = "订单管理", description = "订单查询、状态管理")
public class OrderAdminController {

    private final OrderAdminService orderAdminService;

    @GetMapping
    @Operation(summary = "分页查询订单列表")
    public ApiResponse<PageResponse<OrderResponse>> getOrderPage(
            @Parameter(description = "当前页") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "订单号") @RequestParam(required = false) String orderNo,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "订单状态: 0-待支付, 1-已支付, 2-已发货, 3-已完成, 4-已取消") @RequestParam(required = false) Integer status) {
        PageResponse<OrderResponse> result = orderAdminService.getOrderPage(page, size, orderNo, userId, status);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取订单详情")
    public ApiResponse<OrderResponse> getOrderById(@PathVariable Long id) {
        OrderResponse order = orderAdminService.getOrderById(id);
        return ApiResponse.success(order);
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "更新订单状态")
    public ApiResponse<OrderResponse> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusRequest request) {
        OrderResponse order = orderAdminService.updateOrderStatus(id, request);
        return ApiResponse.success("订单状态更新成功", order);
    }

    @GetMapping("/stats")
    @Operation(summary = "获取订单统计")
    public ApiResponse<Map<String, Object>> getOrderStats() {
        Map<String, Object> stats = orderAdminService.getOrderStats();
        return ApiResponse.success(stats);
    }
}
