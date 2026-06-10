package com.ecommerce.seckill.controller;

import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.service.SeckillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeckillController API 接口测试")
class SeckillControllerTest {

    @Mock
    private SeckillService seckillService;

    private SeckillController controller;

    @BeforeEach
    void setUp() {
        controller = new SeckillController(seckillService, new com.ecommerce.seckill.config.SentinelFallbackHandler());
    }

    // ==================== /api/seckill/products ====================

    @Nested
    @DisplayName("GET /products 获取秒杀商品列表")
    class GetProductsTests {

        @Test
        @DisplayName("有商品时应返回商品列表和数量")
        void hasProducts_returnsListAndCount() {
            SeckillProduct product = createTestProduct(1L, "iPhone 15", new BigDecimal("6999.00"), 10);
            when(seckillService.getActiveSeckillProducts()).thenReturn(List.of(product));

            ResponseEntity<Map<String, Object>> response = controller.getActiveProducts();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(1, response.getBody().get("count"));
            assertNotNull(response.getBody().get("products"));
        }

        @Test
        @DisplayName("无商品时应返回空列表和 count=0")
        void noProducts_returnsEmptyList() {
            when(seckillService.getActiveSeckillProducts()).thenReturn(Collections.emptyList());

            ResponseEntity<Map<String, Object>> response = controller.getActiveProducts();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(0, response.getBody().get("count"));
        }
    }

    // ==================== /api/seckill/activity ====================

    @Nested
    @DisplayName("GET /activity 获取活动信息")
    class GetActivityInfoTests {

        @Test
        @DisplayName("有进行中活动时应返回 endTime 和 hasActiveActivity=true")
        void hasActiveActivity_returnsEndTime() {
            LocalDateTime endTime = LocalDateTime.now().plusHours(2);
            when(seckillService.getNearestEndTime()).thenReturn(endTime);

            ResponseEntity<Map<String, Object>> response = controller.getActivityInfo();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(true, response.getBody().get("hasActiveActivity"));
            assertNotNull(response.getBody().get("endTime"));
        }

        @Test
        @DisplayName("无进行中活动时应返回 hasActiveActivity=false 和 message")
        void noActiveActivity_returnsFalseWithMessage() {
            when(seckillService.getNearestEndTime()).thenReturn(null);

            ResponseEntity<Map<String, Object>> response = controller.getActivityInfo();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(false, response.getBody().get("hasActiveActivity"));
            assertEquals("当前无进行中的秒杀活动", response.getBody().get("message"));
        }
    }

    // ==================== /api/seckill/start ====================

    @Nested
    @DisplayName("POST /start 秒杀接口")
    class StartSeckillTests {

        @Test
        @DisplayName("秒杀成功时应返回 success=true")
        void seckillSuccess_returnsTrue() {
            when(seckillService.trySeckill(100L, 1L, 1)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.startSeckill(null, 100L, 1L, 1);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(true, response.getBody().get("success"));
            assertEquals("秒杀成功", response.getBody().get("message"));
        }

        @Test
        @DisplayName("秒杀失败时应返回 success=false")
        void seckillFail_returnsFalse() {
            when(seckillService.trySeckill(100L, 1L, 1)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.startSeckill(null, 100L, 1L, 1);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(false, response.getBody().get("success"));
            assertEquals("秒杀失败", response.getBody().get("message"));
        }

        @Test
        @DisplayName("认证 Header 存在时应优先使用 Header 用户ID")
        void authenticatedHeader_takesPrecedenceOverQueryUserId() {
            when(seckillService.trySeckill(200L, 1L, 1)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.startSeckill(200L, 100L, 1L, 1);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(true, response.getBody().get("success"));
            verify(seckillService).trySeckill(200L, 1L, 1);
            verify(seckillService, never()).trySeckill(100L, 1L, 1);
        }
    }

    // ==================== /api/seckill/stock ====================

    @Nested
    @DisplayName("GET /stock 获取库存")
    class GetStockTests {

        @Test
        @DisplayName("应返回正确的商品ID和库存")
        void returnsCorrectStock() {
            when(seckillService.getStock(1L)).thenReturn(50);

            ResponseEntity<Map<String, Object>> response = controller.getStock(1L);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(1L, response.getBody().get("seckillProductId"));
            assertEquals(50, response.getBody().get("stock"));
        }
    }

    // ==================== /api/seckill/health ====================

    @Nested
    @DisplayName("GET /health 健康检查")
    class HealthTests {

        @Test
        @DisplayName("Redis 正常时应返回 status=UP")
        void redisUp_returnsUp() {
            when(seckillService.pingRedis()).thenReturn("PONG");
            when(seckillService.getQueueSize()).thenReturn(0L);

            ResponseEntity<Map<String, Object>> response = controller.health();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("UP", response.getBody().get("status"));
            assertEquals("UP", response.getBody().get("redis"));
            assertEquals("HEALTHY", response.getBody().get("queueStatus"));
        }

        @Test
        @DisplayName("队列积压超过1000时应返回 QUEUE_STATUS=BACKLOGGED")
        void queueBacklogged_returnsBacklogged() {
            when(seckillService.pingRedis()).thenReturn("PONG");
            when(seckillService.getQueueSize()).thenReturn(2000L);

            ResponseEntity<Map<String, Object>> response = controller.health();

            assertNotNull(response.getBody());
            assertEquals("BACKLOGGED", response.getBody().get("queueStatus"));
            assertEquals(2000L, response.getBody().get("queueSize"));
        }
    }

    // ==================== 辅助方法 ====================

    private SeckillProduct createTestProduct(Long id, String name, BigDecimal price, int stock) {
        SeckillProduct product = new SeckillProduct();
        product.setId(id);
        product.setProductName(name);
        product.setSeckillPrice(price);
        product.setAvailableStock(stock);
        product.setTotalStock(stock);
        product.setStatus(1);
        product.setStartTime(LocalDateTime.now().minusHours(1));
        product.setEndTime(LocalDateTime.now().plusHours(2));
        return product;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("无法设置字段 " + fieldName, e);
        }
    }
}
