package com.ecommerce.admin.controller;

import com.ecommerce.admin.dto.common.ApiResponse;
import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.product.ProductRequest;
import com.ecommerce.admin.dto.product.ProductResponse;
import com.ecommerce.admin.service.ProductAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
@Tag(name = "商品管理", description = "商品CRUD、库存管理")
public class ProductAdminController {

    private final ProductAdminService productAdminService;

    @GetMapping
    @Operation(summary = "分页查询商品列表")
    public ApiResponse<PageResponse<ProductResponse>> getProductPage(
            @Parameter(description = "当前页") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类ID") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "状态: 0-下架, 1-上架") @RequestParam(required = false) Integer status) {
        PageResponse<ProductResponse> result = productAdminService.getProductPage(page, size, keyword, categoryId, status);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取商品详情")
    public ApiResponse<ProductResponse> getProductById(@PathVariable Long id) {
        ProductResponse product = productAdminService.getProductById(id);
        return ApiResponse.success(product);
    }

    @PostMapping
    @Operation(summary = "创建商品")
    public ApiResponse<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse product = productAdminService.createProduct(request);
        return ApiResponse.success("商品创建成功", product);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新商品")
    public ApiResponse<ProductResponse> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        ProductResponse product = productAdminService.updateProduct(id, request);
        return ApiResponse.success("商品更新成功", product);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除商品(下架)")
    public ApiResponse<Void> deleteProduct(@PathVariable Long id) {
        productAdminService.deleteProduct(id);
        return ApiResponse.success("商品已下架");
    }

    @PutMapping("/{id}/stock")
    @Operation(summary = "调整库存")
    public ApiResponse<Map<String, Object>> updateStock(
            @PathVariable Long id,
            @RequestParam int stock) {
        Map<String, Object> result = productAdminService.updateStock(id, stock);
        return ApiResponse.success("库存调整成功", result);
    }

    @GetMapping("/categories")
    @Operation(summary = "获取分类列表")
    public ApiResponse<PageResponse<Map<String, Object>>> getCategories() {
        PageResponse<Map<String, Object>> categories = productAdminService.getCategories();
        return ApiResponse.success(categories);
    }
}
