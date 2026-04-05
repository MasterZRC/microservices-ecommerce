package com.ecommerce.admin.controller;

import com.ecommerce.admin.dto.common.ApiResponse;
import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.seckill.SeckillRequest;
import com.ecommerce.admin.dto.seckill.SeckillResponse;
import com.ecommerce.admin.service.SeckillAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/seckill")
@RequiredArgsConstructor
@Tag(name = "秒杀管理", description = "秒杀活动CRUD、库存管理")
public class SeckillAdminController {

    private final SeckillAdminService seckillAdminService;

    @GetMapping("/activities")
    @Operation(summary = "查询秒杀活动列表")
    public ApiResponse<PageResponse<SeckillResponse>> getActivityPage(
            @Parameter(description = "当前页") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态") @RequestParam(required = false) Integer status) {
        PageResponse<SeckillResponse> result = seckillAdminService.getActivityPage(page, size, keyword, status);
        return ApiResponse.success(result);
    }

    @GetMapping("/activities/{id}")
    @Operation(summary = "获取秒杀活动详情")
    public ApiResponse<SeckillResponse> getActivityById(@PathVariable Long id) {
        SeckillResponse activity = seckillAdminService.getActivityById(id);
        return ApiResponse.success(activity);
    }

    @PostMapping("/activities")
    @Operation(summary = "创建秒杀活动")
    public ApiResponse<SeckillResponse> createActivity(@Valid @RequestBody SeckillRequest request) {
        SeckillResponse activity = seckillAdminService.createActivity(request);
        return ApiResponse.success("秒杀活动创建成功", activity);
    }

    @PutMapping("/activities/{id}")
    @Operation(summary = "更新秒杀活动")
    public ApiResponse<SeckillResponse> updateActivity(
            @PathVariable Long id,
            @Valid @RequestBody SeckillRequest request) {
        SeckillResponse activity = seckillAdminService.updateActivity(id, request);
        return ApiResponse.success("秒杀活动更新成功", activity);
    }

    @DeleteMapping("/activities/{id}")
    @Operation(summary = "删除秒杀活动")
    public ApiResponse<Void> deleteActivity(@PathVariable Long id) {
        seckillAdminService.deleteActivity(id);
        return ApiResponse.success("秒杀活动已删除");
    }

    @PutMapping("/activities/{id}/stock")
    @Operation(summary = "调整秒杀库存")
    public ApiResponse<Map<String, Object>> updateStock(
            @PathVariable Long id,
            @RequestParam int stock) {
        Map<String, Object> result = seckillAdminService.updateStock(id, stock);
        return ApiResponse.success("库存调整成功", result);
    }
}
