package com.ecommerce.seckill.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BloomFilterConfig 布隆过滤器测试")
class BloomFilterConfigTest {

    private BloomFilterConfig bloomFilterConfig;

    @BeforeEach
    void setUp() {
        bloomFilterConfig = new BloomFilterConfig(null);
        // 通过反射注入测试值
        setField(bloomFilterConfig, "expectedInsertions", 1000);
        setField(bloomFilterConfig, "fpp", 0.01);
        bloomFilterConfig.init();
    }

    @Nested
    @DisplayName("单个商品ID操作")
    class SingleIdTests {

        @Test
        @DisplayName("addProductId 后 mightExist 应返回 true")
        void add_thenMightExist_returnsTrue() {
            bloomFilterConfig.addProductId(12345L);
            assertTrue(bloomFilterConfig.mightExist(12345L));
        }

        @Test
        @DisplayName("未添加的商品ID mightExist 应返回 false")
        void notAdded_returnsFalse() {
            assertFalse(bloomFilterConfig.mightExist(99999L));
        }

        @Test
        @DisplayName("null 参数 mightExist 应返回 false")
        void nullParam_returnsFalse() {
            assertFalse(bloomFilterConfig.mightExist(null));
        }
    }

    @Nested
    @DisplayName("批量操作")
    class BatchTests {

        @Test
        @DisplayName("批量添加后所有ID都应可能被识别")
        void batchAdd_allIdsExist() {
            var ids = java.util.List.of(1L, 2L, 3L, 4L, 5L);
            bloomFilterConfig.addProductIds(ids);

            for (Long id : ids) {
                assertTrue(bloomFilterConfig.mightExist(id), "ID " + id + " 应被识别");
            }
        }

        @Test
        @DisplayName("批量添加含 null 值应不抛异常")
        void batchAdd_withNulls_noException() {
            var idsWithNull = java.util.Arrays.asList(1L, null, 3L);
            assertDoesNotThrow(() -> bloomFilterConfig.addProductIds(idsWithNull));
        }
    }

    @Nested
    @DisplayName("误判率验证")
    class FalsePositiveRateTests {

        @Test
        @DisplayName("大规模不存在ID查询时误判率应低于配置的 fpp")
        void falsePositiveRate_belowFpp() {
            // 添加 100 个 ID
            for (int i = 0; i < 100; i++) {
                bloomFilterConfig.addProductId((long) i);
            }
            // 查询 10000 个不存在的 ID，统计误判率
            int falsePositives = 0;
            int testCount = 10000;
            for (int i = 10000; i < 10000 + testCount; i++) {
                if (bloomFilterConfig.mightExist((long) i)) {
                    falsePositives++;
                }
            }
            double actualFpp = (double) falsePositives / testCount;
            assertTrue(actualFpp < 0.05,
                    String.format("误判率 %.4f 应低于 0.05（配置 fpp=0.01 的 5 倍容差）", actualFpp));
        }
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
