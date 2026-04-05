package com.ecommerce.admin.service;

import com.ecommerce.admin.dto.dashboard.DashboardStatsResponse;
import com.ecommerce.admin.dto.order.OrderResponse;
import java.util.List;

public interface DashboardService {

    DashboardStatsResponse getStats();

    List<OrderResponse> getRecentOrders(int limit);
}
