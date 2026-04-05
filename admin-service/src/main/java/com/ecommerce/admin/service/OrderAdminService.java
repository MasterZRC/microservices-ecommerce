package com.ecommerce.admin.service;

import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.order.OrderResponse;
import com.ecommerce.admin.dto.order.OrderStatusRequest;

import java.util.Map;

public interface OrderAdminService {

    PageResponse<OrderResponse> getOrderPage(int page, int size, String orderNo, Long userId, Integer status);

    OrderResponse getOrderById(Long id);

    OrderResponse updateOrderStatus(Long id, OrderStatusRequest request);

    Map<String, Object> getOrderStats();
}
