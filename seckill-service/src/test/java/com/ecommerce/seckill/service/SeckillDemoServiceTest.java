package com.ecommerce.seckill.service;

import com.ecommerce.seckill.dto.SeckillDemoRequest;
import com.ecommerce.seckill.dto.SeckillDemoResetResult;
import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeckillDemoService reset tests")
class SeckillDemoServiceTest {

    @Mock
    private SeckillService seckillService;

    @Mock
    private SeckillProductMapper seckillProductMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SeckillDemoService service;

    @BeforeEach
    void setUp() {
        service = new SeckillDemoService(seckillService, seckillProductMapper, stringRedisTemplate, jdbcTemplate);
    }

    @Test
    @DisplayName("reset should update MySQL stock and rewrite Redis stock")
    void reset_updatesDatabaseAndRedisStock() {
        SeckillProduct product = new SeckillProduct();
        product.setId(1L);
        product.setActivityId(10L);
        product.setProductId(100L);
        product.setProductName("Demo Product");
        product.setTotalStock(10);
        product.setAvailableStock(10);

        when(seckillProductMapper.selectById(1L)).thenReturn(product);
        when(stringRedisTemplate.keys(anyString())).thenReturn(Collections.emptySet());
        when(stringRedisTemplate.delete(any(Set.class))).thenReturn(0L);
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        SeckillDemoRequest request = new SeckillDemoRequest();
        request.setSeckillProductId(1L);
        request.setStock(50);

        SeckillDemoResetResult result = service.resetDemo(request);

        assertNotNull(result);
        assertEquals(50, result.getStock());

        ArgumentCaptor<SeckillProduct> productCaptor = ArgumentCaptor.forClass(SeckillProduct.class);
        verify(seckillProductMapper).updateById(productCaptor.capture());
        assertEquals(50, productCaptor.getValue().getTotalStock());
        assertEquals(50, productCaptor.getValue().getAvailableStock());

        verify(valueOperations).set("seckill:stock:1", "50", 1L, TimeUnit.DAYS);
        verify(jdbcTemplate).update(
                eq("UPDATE seckill_activity SET total_stock = ?, available_stock = ?, update_time = NOW() WHERE id = ?"),
                eq(50),
                eq(50),
                eq(10L)
        );
    }
}
