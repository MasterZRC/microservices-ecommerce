package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderDetailResponse;
import com.ecommerce.order.dto.SeckillOrderCreateRequest;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Tag(name = "订单管理", description = "订单创建、查询、支付接口")
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/create")
    @Operation(summary = "创建订单", description = "根据购物车商品创建订单")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = Order.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误")
    })
    public ResponseEntity<Order> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        Order order = orderService.createOrder(request);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/create/seckill")
    @Operation(summary = "创建秒杀订单", description = "创建秒杀活动订单")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = Order.class))),
            @ApiResponse(responseCode = "400", description = "库存不足或活动未开始")
    })
    public ResponseEntity<Order> createSeckillOrder(@Valid @RequestBody SeckillOrderCreateRequest request) {
        Order order = orderService.createSeckillOrder(request);
        return ResponseEntity.ok(order);
    }

    @GetMapping("/list")
    @Operation(summary = "获取订单列表", description = "获取用户的所有订单")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<List<OrderDetailResponse>> getOrderList(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId) {
        List<OrderDetailResponse> orders = orderService.getOrderList(userId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取订单详情", description = "根据订单ID查询订单详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功",
                    content = @Content(schema = @Schema(implementation = Order.class))),
            @ApiResponse(responseCode = "404", description = "订单不存在")
    })
    public ResponseEntity<Order> getOrderById(
            @Parameter(description = "订单ID", required = true) @PathVariable Long id) {
        Order order = orderService.getOrderById(id);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/pay")
    @Operation(summary = "支付订单", description = "模拟订单支付")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "支付成功",
                    content = @Content(schema = @Schema(implementation = Order.class)))
    })
    public ResponseEntity<Order> payOrder(
            @Parameter(description = "订单ID") @RequestParam Long orderId,
            @Parameter(description = "用户ID") @RequestParam Long userId) {
        Order order = orderService.payOrder(orderId, userId);
        return ResponseEntity.ok(order);
    }

    @Data
    public static class CartAddRequest {
        public Long userId;
        public Long productId;
        public String productName;
        public String productImage;
        public Integer quantity = 1;
    }

    @PostMapping("/cart/add")
    @Operation(summary = "添加购物车", description = "将商品添加到购物车")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "添加成功")
    })
    public ResponseEntity<Map<String, String>> addToCart(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) String productImage,
            @RequestParam(required = false, defaultValue = "1") Integer quantity,
            @RequestBody(required = false) CartAddRequest body) {
        Long uid, pid;
        String pname, pimage;
        Integer qty;
        if (body != null) {
            uid = body.userId;
            pid = body.productId;
            pname = body.productName;
            pimage = body.productImage;
            qty = body.quantity != null ? body.quantity : 1;
        } else {
            uid = userId;
            pid = productId;
            pname = productName;
            pimage = productImage;
            qty = quantity != null ? quantity : 1;
        }
        if (uid == null || pid == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "userId 和 productId 不能为空"));
        }
        orderService.addToCart(uid, pid, pname != null ? pname : "", pimage != null ? pimage : "", qty);
        Map<String, String> result = new HashMap<>();
        result.put("message", "添加成功");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/cart/list")
    @Operation(summary = "获取购物车列表", description = "获取用户购物车中的商品")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<List<Cart>> getCartList(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId) {
        List<Cart> carts = orderService.getCartList(userId);
        return ResponseEntity.ok(carts);
    }

    @GetMapping("/cart/count")
    @Operation(summary = "获取购物车数量", description = "获取用户购物车中商品数量")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功")
    })
    public ResponseEntity<Integer> getCartCount(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId) {
        Integer count = orderService.getCartCount(userId);
        return ResponseEntity.ok(count);
    }

    @DeleteMapping("/cart/remove")
    @Operation(summary = "移除购物车商品", description = "从购物车中移除指定商品")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功")
    })
    public ResponseEntity<Map<String, String>> removeFromCart(
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "商品ID") @RequestParam Long productId) {
        orderService.removeFromCart(userId, productId);
        Map<String, String> result = new HashMap<>();
        result.put("message", "删除成功");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/cart/clear")
    @Operation(summary = "清空购物车", description = "清空用户购物车")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "清空成功")
    })
    public ResponseEntity<Map<String, String>> clearCart(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId) {
        orderService.clearCart(userId);
        Map<String, String> result = new HashMap<>();
        result.put("message", "清空成功");
        return ResponseEntity.ok(result);
    }
}