package com.ecommerce.seckill.controller;

import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import com.ecommerce.seckill.service.SeckillCacheService;
import com.ecommerce.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/seckill")
@RequiredArgsConstructor
public class SeckillAdminController {

    private final SeckillProductMapper seckillProductMapper;
    private final SeckillCacheService seckillCacheService;

    @PostMapping("/product")
    public ResponseEntity<Map<String, Object>> createProduct(@RequestBody Map<String, Object> request) {
        SeckillProduct product = new SeckillProduct();
        product.setProductId(Long.valueOf(request.get("productId").toString()));
        product.setProductName((String) request.get("productName"));
        product.setProductImage((String) request.get("productImage"));
        product.setSeckillPrice(new BigDecimal(request.get("seckillPrice").toString()));
        product.setTotalStock(Integer.valueOf(request.get("stock").toString()));
        product.setAvailableStock(Integer.valueOf(request.get("stock").toString()));
        product.setStartTime(LocalDateTime.parse((String) request.get("startTime")));
        product.setEndTime(LocalDateTime.parse((String) request.get("endTime")));
        product.setStatus(Integer.valueOf(request.get("status").toString()));

        if (request.containsKey("activityId") && request.get("activityId") != null) {
            product.setActivityId(Long.valueOf(request.get("activityId").toString()));
        }

        seckillProductMapper.insert(product);

        seckillCacheService.clearProductCache();
        seckillCacheService.initStock(product.getId(), product.getAvailableStock());

        Map<String, Object> result = new HashMap<>();
        result.put("id", product.getId());
        result.put("message", "秒杀商品创建成功");
        return ResponseEntity.ok(result);
    }

    @PutMapping("/product/{id}")
    public ResponseEntity<Map<String, Object>> updateProduct(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        SeckillProduct product = seckillProductMapper.selectById(id);
        if (product == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("message", "秒杀商品不存在");
            return ResponseEntity.badRequest().body(error);
        }

        if (request.containsKey("productName")) {
            product.setProductName((String) request.get("productName"));
        }
        if (request.containsKey("productImage")) {
            product.setProductImage((String) request.get("productImage"));
        }
        if (request.containsKey("seckillPrice")) {
            product.setSeckillPrice(new BigDecimal(request.get("seckillPrice").toString()));
        }
        if (request.containsKey("stock")) {
            int newStock = Integer.valueOf(request.get("stock").toString());
            product.setAvailableStock(newStock);
        }
        if (request.containsKey("startTime")) {
            product.setStartTime(LocalDateTime.parse((String) request.get("startTime")));
        }
        if (request.containsKey("endTime")) {
            product.setEndTime(LocalDateTime.parse((String) request.get("endTime")));
        }
        if (request.containsKey("status")) {
            product.setStatus(Integer.valueOf(request.get("status").toString()));
        }

        seckillProductMapper.updateById(product);
        seckillCacheService.clearProductCache();
        seckillCacheService.initStock(product.getId(), product.getAvailableStock());

        Map<String, Object> result = new HashMap<>();
        result.put("id", product.getId());
        result.put("message", "秒杀商品更新成功");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/product/{id}")
    public ResponseEntity<Map<String, Object>> disableProduct(@PathVariable Long id) {
        SeckillProduct product = seckillProductMapper.selectById(id);
        if (product == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("message", "秒杀商品不存在");
            return ResponseEntity.badRequest().body(error);
        }

        product.setStatus(0);
        seckillProductMapper.updateById(product);
        seckillCacheService.clearProductCache();

        Map<String, Object> result = new HashMap<>();
        result.put("id", product.getId());
        result.put("message", "秒杀商品已禁用");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/product/by-activity/{activityId}")
    public ResponseEntity<Map<String, Object>> getProductByActivityId(@PathVariable Long activityId) {
        SeckillProduct product = seckillProductMapper.selectByActivityId(activityId);

        Map<String, Object> result = new HashMap<>();
        if (product != null) {
            result.put("id", product.getId());
            result.put("productId", product.getProductId());
            result.put("productName", product.getProductName());
            result.put("seckillPrice", product.getSeckillPrice());
            result.put("stock", product.getAvailableStock());
            result.put("status", product.getStatus());
            result.put("found", true);
        } else {
            result.put("found", false);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/stock/{id}")
    public ResponseEntity<Map<String, Object>> syncStock(
            @PathVariable Long id,
            @RequestParam Integer stock) {
        SeckillProduct product = seckillProductMapper.selectById(id);
        if (product == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("message", "秒杀商品不存在");
            return ResponseEntity.badRequest().body(error);
        }

        product.setAvailableStock(stock);
        seckillProductMapper.updateById(product);
        seckillCacheService.initStock(id, stock);

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("stock", stock);
        result.put("message", "库存同步成功");
        return ResponseEntity.ok(result);
    }
}
