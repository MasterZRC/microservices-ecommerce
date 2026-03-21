package com.ecommerce.product.controller;

import com.ecommerce.product.dto.ProductPageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
@Tag(name = "商品服务", description = "商品浏览、搜索、分类、库存管理接口")
public class ProductController {

    private final ProductService productService;

    @GetMapping("/list")
    @Operation(
        summary = "获取商品列表",
        description = "分页获取商品列表，支持关键词搜索和分类筛选，使用 Redis 缓存加速"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "查询成功",
            content = @Content(schema = @Schema(implementation = ProductPageResponse.class))
        )
    })
    public ResponseEntity<ProductPageResponse> getProductList(
            @Parameter(description = "页码（从1开始）", example = "1")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "搜索关键词（匹配商品名和描述）")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "商品分类ID")
            @RequestParam(required = false) Long categoryId) {
        ProductPageResponse response = productService.getProductList(page, pageSize, keyword, categoryId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "获取商品详情",
        description = "根据商品ID获取商品详细信息，包含价格、库存、分类、品牌等"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "查询成功",
            content = @Content(schema = @Schema(implementation = Product.class))
        ),
        @ApiResponse(responseCode = "404", description = "商品不存在")
    })
    public ResponseEntity<Product> getProductById(
            @Parameter(description = "商品ID", required = true)
            @PathVariable Long id) {
        Product product = productService.getProductById(id);
        return ResponseEntity.ok(product);
    }

    @PostMapping("/create")
    @Operation(
        summary = "创建商品",
        description = "创建新商品，需提供商品名称、价格、库存、分类、品牌等信息"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "创建成功",
            content = @Content(schema = @Schema(implementation = Product.class))
        ),
        @ApiResponse(responseCode = "400", description = "参数校验失败")
    })
    public ResponseEntity<Product> createProduct(
            @Parameter(description = "商品创建请求信息", required = true)
            @Valid @RequestBody ProductRequest request) {
        Product product = productService.createProduct(request);
        return ResponseEntity.ok(product);
    }

    @PutMapping("/update")
    @Operation(
        summary = "更新商品",
        description = "更新商品信息，包含价格、库存、上下架状态等"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "更新成功",
            content = @Content(schema = @Schema(implementation = Product.class))
        ),
        @ApiResponse(responseCode = "400", description = "参数无效或商品不存在")
    })
    public ResponseEntity<Product> updateProduct(
            @Parameter(description = "商品更新请求信息（需包含id）", required = true)
            @Valid @RequestBody ProductRequest request) {
        Product product = productService.updateProduct(request);
        return ResponseEntity.ok(product);
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "删除商品",
        description = "删除指定商品（逻辑删除，将商品状态置为下架）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "删除成功"),
        @ApiResponse(responseCode = "404", description = "商品不存在")
    })
    public ResponseEntity<Map<String, String>> deleteProduct(
            @Parameter(description = "商品ID", required = true)
            @PathVariable Long id) {
        productService.deleteProduct(id);
        Map<String, String> result = new HashMap<>();
        result.put("message", "删除成功");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/category/list")
    @Operation(
        summary = "获取商品分类列表",
        description = "获取所有启用的商品分类，按排序字段升序排列"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "查询成功",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = Category.class)))
        )
    })
    public ResponseEntity<List<Category>> getCategoryList() {
        List<Category> categories = productService.getCategoryList();
        return ResponseEntity.ok(categories);
    }

    @PostMapping("/stock/reduce")
    @Operation(
        summary = "减少商品库存",
        description = "原子性扣减商品库存，用于订单创建时库存预扣，支持并发安全"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作完成")
    })
    public ResponseEntity<Map<String, Boolean>> reduceStock(
            @Parameter(description = "商品ID", required = true)
            @RequestParam Long productId,
            @Parameter(description = "扣减数量", required = true)
            @RequestParam Integer quantity) {
        boolean success = productService.reduceStock(productId, quantity);
        Map<String, Boolean> result = new HashMap<>();
        result.put("success", success);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/stock/increase")
    @Operation(
        summary = "增加商品库存",
        description = "增加商品库存，用于订单取消时的库存回补"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作完成")
    })
    public ResponseEntity<Map<String, String>> increaseStock(
            @Parameter(description = "商品ID", required = true)
            @RequestParam Long productId,
            @Parameter(description = "增加数量", required = true)
            @RequestParam Integer quantity) {
        productService.increaseStock(productId, quantity);
        Map<String, String> result = new HashMap<>();
        result.put("message", "库存更新成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch")
    @Operation(
        summary = "批量获取商品",
        description = "根据商品ID列表批量获取商品详情，用于推荐系统等需要批量获取商品信息的场景"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Map<String, Object>> getProductsByIds(
            @Parameter(description = "商品ID列表", required = true)
            @RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of("products", List.of()));
        }
        List<Product> products = productService.getProductsByIds(ids);
        return ResponseEntity.ok(Map.of("products", products));
    }
}