package com.ecommerce.seckill.controller;

import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.service.SeckillDemoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeckillDemoController admin-only API tests")
class SeckillDemoControllerTest {

    @Mock
    private SeckillDemoService seckillDemoService;

    private SeckillDemoController controller;

    @BeforeEach
    void setUp() {
        controller = new SeckillDemoController(seckillDemoService);
    }

    @Test
    @DisplayName("missing X-Admin-Id should be forbidden")
    void missingAdminHeader_forbidden() {
        ResponseEntity<Map<String, Object>> response = controller.products(null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(403, response.getBody().get("code"));
    }

    @Test
    @DisplayName("admin header should allow product list")
    void adminHeader_allowsProductList() {
        SeckillProduct product = new SeckillProduct();
        product.setId(1L);
        product.setProductName("Demo Product");
        when(seckillDemoService.getDemoProducts()).thenReturn(List.of(product));

        ResponseEntity<Map<String, Object>> response = controller.products(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().get("code"));
    }
}
