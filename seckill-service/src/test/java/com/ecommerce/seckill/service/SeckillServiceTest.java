package com.ecommerce.seckill.service;

import com.ecommerce.seckill.entity.SeckillProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeckillService 核心业务逻辑测试")
class SeckillServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private SeckillCacheService seckillCacheService;

    @Mock
    private SeckillMetricsService seckillMetricsService;

    private SeckillService seckillService;

    @BeforeEach
    void setUp() {
        seckillService = new SeckillService();
        // 通过反射注入 mock 依赖
        setField(seckillService, "stringRedisTemplate", stringRedisTemplate);
        setField(seckillService, "restTemplate", restTemplate);
        setField(seckillService, "seckillCacheService", seckillCacheService);
        setField(seckillService, "seckillMetricsService", seckillMetricsService);
        setField(seckillService, "maxRequestsPerSecond", 100);
        setField(seckillService, "rateLimitEnabled", false);
        setField(seckillService, "orderServiceUrl", "http://localhost:8003");
        setField(seckillService, "productServiceUrl", "http://localhost:8002");
    }

    // ==================== initStock 测试 ====================

    @Nested
    @DisplayName("initStock 库存初始化")
    class InitStockTests {

        @Test
        @DisplayName("正常初始化库存应成功")
        void initStock_normal_success() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

            assertDoesNotThrow(() -> seckillService.initStock(1L, 100));
            verify(valueOperations).set(eq("seckill:stock:1"), eq("100"), eq(1L), any());
        }

        @Test
        @DisplayName("stock 为 null 应抛 IllegalArgumentException")
        void initStock_null_throws() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> seckillService.initStock(1L, null)
            );
            assertEquals("库存不能为空", ex.getMessage());
        }

        @Test
        @DisplayName("stock 为负数应抛 IllegalArgumentException")
        void initStock_negative_throws() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> seckillService.initStock(1L, -5)
            );
            assertEquals("库存不能为负数", ex.getMessage());
        }
    }

    // ==================== getStock 测试 ====================

    @Nested
    @DisplayName("getStock 库存查询")
    class GetStockTests {

        @Test
        @DisplayName("Redis 中有库存应返回正确值")
        void getStock_exists_returnsValue() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("seckill:stock:1")).thenReturn("50");

            Integer stock = seckillService.getStock(1L);

            assertEquals(50, stock);
        }

        @Test
        @DisplayName("Redis 中无库存应返回 0")
        void getStock_notExists_returnsNull() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("seckill:stock:999")).thenReturn(null);

            Integer stock = seckillService.getStock(999L);

            assertNull(stock);
        }
    }

    // ==================== getActiveSeckillProducts 测试 ====================

    @Nested
    @DisplayName("getActiveSeckillProducts 商品列表")
    class GetActiveProductsTests {

        @Test
        @DisplayName("Redis 无缓存时应从数据库查询并存入缓存")
        void getActiveProducts_noCache_queriesDatabase() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            // 缓存未命中
            when(valueOperations.get("seckill:cache:products:active")).thenReturn(null);

            // 模拟数据库查询
            var mapper = mock(com.ecommerce.seckill.mapper.SeckillProductMapper.class);
            SeckillProduct product = new SeckillProduct();
            product.setId(1L);
            product.setProductName("测试商品");
            product.setStatus(1);
            product.setAvailableStock(100);
            when(mapper.selectList(any())).thenReturn(List.of(product));

            setField(seckillService, "seckillProductMapper", mapper);

            List<SeckillProduct> result = seckillService.getActiveSeckillProducts();

            assertNotNull(result);
            assertEquals(1, result.size());
            // 验证商品列表被写入缓存
            verify(valueOperations, atLeastOnce()).set(
                    eq("seckill:cache:products:active"),
                    anyString(),
                    eq(60L),
                    eq(java.util.concurrent.TimeUnit.SECONDS)
            );
        }

        @Test
        @DisplayName("Redis 有缓存时应直接返回缓存数据，不查数据库")
        void getActiveProducts_hasCache_returnsCachedData() {
            String cachedJson = "[{\"id\":1,\"productName\":\"缓存商品\",\"seckillPrice\":99.00,\"availableStock\":80}]";
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("seckill:cache:products:active")).thenReturn(cachedJson);
            when(valueOperations.get("seckill:stock:1")).thenReturn("80");

            var mapper = mock(com.ecommerce.seckill.mapper.SeckillProductMapper.class);
            setField(seckillService, "seckillProductMapper", mapper);

            List<SeckillProduct> result = seckillService.getActiveSeckillProducts();

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("缓存商品", result.get(0).getProductName());
            assertEquals(80, result.get(0).getAvailableStock());
            // 确认 mapper 未被调用（缓存命中）
            verify(mapper, never()).selectList(any());
        }

        @Test
        @DisplayName("空列表结果不应写入缓存")
        void getActiveProducts_emptyList_noCacheWrite() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("seckill:cache:products:active")).thenReturn(null);

            var mapper = mock(com.ecommerce.seckill.mapper.SeckillProductMapper.class);
            when(mapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
            setField(seckillService, "seckillProductMapper", mapper);

            List<SeckillProduct> result = seckillService.getActiveSeckillProducts();

            assertNotNull(result);
            assertTrue(result.isEmpty());
            // 空结果不应写入缓存
            verify(valueOperations, never()).set(eq("seckill:cache:products:active"), anyString(), anyLong(), any());
        }
    }

    // ==================== trySeckill 测试 ====================

    @Nested
    @DisplayName("trySeckill 秒杀资格获取")
    class TrySeckillTests {

        @Test
        @DisplayName("商品不存在应直接拒绝，不执行 Lua 脚本")
        void trySeckill_productNotExists_returnsFalse() {
            when(seckillCacheService.productExists(1L)).thenReturn(false);

            boolean result = seckillService.trySeckill(100L, 1L, 1);

            assertFalse(result);
            // Lua 脚本不应被执行（幂等检查在 productExists 之后）
        }

        @Test
        @DisplayName("quantity 为 null 时不应抛异常")
        void trySeckill_nullQuantity_noException() {
            when(seckillCacheService.productExists(1L)).thenReturn(true);
            // Lua 返回 null 表示执行失败，不抛异常
            assertDoesNotThrow(() -> seckillService.trySeckill(100L, 1L, null));
        }

        @Test
        @DisplayName("秒杀成功时应记录成功指标")
        void trySeckill_success_recordsSuccessMetrics() {
            var streamOps = mock(org.springframework.data.redis.core.StreamOperations.class);
            when(seckillCacheService.productExists(1L)).thenReturn(true);
            when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
            when(streamOps.add(eq("seckill:stream:orders"), any(Map.class)))
                    .thenReturn(org.springframework.data.redis.connection.stream.RecordId.autoGenerate());
            when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(1L);

            boolean result = seckillService.trySeckill(100L, 1L, 1);

            assertTrue(result);
            verify(seckillMetricsService).recordRequest();
            verify(seckillMetricsService).recordSuccess();
        }

        @Test
        @DisplayName("库存不足时应记录 stock_exhausted 指标")
        void trySeckill_stockExhausted_recordsFailureMetrics() {
            when(seckillCacheService.productExists(1L)).thenReturn(true);
            when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(-2L);

            boolean result = seckillService.trySeckill(100L, 1L, 1);

            assertFalse(result);
            verify(seckillMetricsService).recordFailed("stock_exhausted");
        }

        @Test
        @DisplayName("重复购买时应记录 duplicate 指标")
        void trySeckill_duplicate_recordsFailureMetrics() {
            when(seckillCacheService.productExists(1L)).thenReturn(true);
            when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(-1L);

            boolean result = seckillService.trySeckill(100L, 1L, 1);

            assertFalse(result);
            verify(seckillMetricsService).recordFailed("duplicate");
        }

        @Test
        @DisplayName("限流时应记录 rate_limited 指标")
        void trySeckill_rateLimited_recordsFailureMetrics() {
            when(seckillCacheService.productExists(1L)).thenReturn(true);
            setField(seckillService, "rateLimitEnabled", true);
            when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(-4L);

            boolean result = seckillService.trySeckill(100L, 1L, 1);

            assertFalse(result);
            verify(seckillMetricsService).recordRateLimited();
            verify(seckillMetricsService).recordFailed("rate_limited");
        }

        @Test
        @DisplayName("异常时应记录 exception 指标")
        void trySeckill_exception_recordsFailureMetrics() {
            when(seckillCacheService.productExists(1L)).thenReturn(true);
            when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("redis down"));

            boolean result = seckillService.trySeckill(100L, 1L, 1);

            assertFalse(result);
            verify(seckillMetricsService).recordFailed("exception");
        }
    }

    // ==================== getQueueSize 测试 ====================

    @Nested
    @DisplayName("getQueueSize 消息队列大小")
    class GetQueueSizeTests {

        @Test
        @DisplayName("队列大小为 null 时应返回 0")
        void getQueueSize_null_returnsZero() {
            var streamOps = mock(org.springframework.data.redis.core.StreamOperations.class);
            when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
            when(streamOps.size(anyString())).thenReturn(null);

            long size = seckillService.getQueueSize();

            assertEquals(0L, size);
        }

        @Test
        @DisplayName("队列有消息时应返回正确数量")
        void getQueueSize_hasMessages_returnsCount() {
            var streamOps = mock(org.springframework.data.redis.core.StreamOperations.class);
            when(stringRedisTemplate.opsForStream()).thenReturn(streamOps);
            when(streamOps.size("seckill:stream:orders")).thenReturn(42L);

            long size = seckillService.getQueueSize();

            assertEquals(42L, size);
        }
    }

    // ==================== 反射注入工具 ====================

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
