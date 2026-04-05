package com.ecommerce.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.admin.dto.dashboard.DashboardStatsResponse;
import com.ecommerce.admin.dto.order.OrderResponse;
import com.ecommerce.admin.entity.Order;
import com.ecommerce.admin.entity.Product;
import com.ecommerce.admin.entity.User;
import com.ecommerce.admin.mapper.OrderMapper;
import com.ecommerce.admin.mapper.ProductMapper;
import com.ecommerce.admin.mapper.UserMapper;
import com.ecommerce.admin.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;

    private static final Map<Integer, String> ORDER_STATUS_MAP = new HashMap<>();
    static {
        ORDER_STATUS_MAP.put(0, "待支付");
        ORDER_STATUS_MAP.put(1, "已支付");
        ORDER_STATUS_MAP.put(2, "已发货");
        ORDER_STATUS_MAP.put(3, "已完成");
        ORDER_STATUS_MAP.put(4, "已取消");
    }

    @Override
    @Cacheable(value = "dashboard:stats")
    public DashboardStatsResponse getStats() {
        Long userCount = userMapper.selectCount(null);
        Long productCount = productMapper.selectCount(null);
        Long orderCount = orderMapper.selectCount(null);

        LocalDateTime startOfToday = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LambdaQueryWrapper<Order> todayWrapper = new LambdaQueryWrapper<>();
        todayWrapper.ge(Order::getCreateTime, startOfToday);
        Long todayOrderCount = orderMapper.selectCount(todayWrapper);

        LambdaQueryWrapper<Order> todayAmountWrapper = new LambdaQueryWrapper<>();
        todayAmountWrapper.ge(Order::getCreateTime, startOfToday);
        todayAmountWrapper.eq(Order::getStatus, 1);
        List<Order> todayOrders = orderMapper.selectList(todayAmountWrapper);
        BigDecimal todaySales = todayOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LambdaQueryWrapper<Order> allWrapper = new LambdaQueryWrapper<>();
        allWrapper.eq(Order::getStatus, 1);
        List<Order> allOrders = orderMapper.selectList(allWrapper);
        BigDecimal totalSales = allOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LambdaQueryWrapper<Order> pendingWrapper = new LambdaQueryWrapper<>();
        pendingWrapper.in(Order::getStatus, 0, 1, 2);
        Long pendingOrderCount = orderMapper.selectCount(pendingWrapper);

        LambdaQueryWrapper<Product> lowStockWrapper = new LambdaQueryWrapper<>();
        lowStockWrapper.le(Product::getStock, 10);
        lowStockWrapper.eq(Product::getStatus, 1);
        Long lowStockProductCount = productMapper.selectCount(lowStockWrapper);

        return new DashboardStatsResponse(
                userCount,
                productCount,
                orderCount,
                todayOrderCount,
                todaySales,
                totalSales,
                pendingOrderCount,
                lowStockProductCount
        );
    }

    @Override
    public List<OrderResponse> getRecentOrders(int limit) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Order::getCreateTime);
        wrapper.last("LIMIT " + limit);
        List<Order> orders = orderMapper.selectList(wrapper);
        return orders.stream().map(this::toOrderResponse).collect(Collectors.toList());
    }

    private OrderResponse toOrderResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderNo(order.getOrderNo());
        response.setUserId(order.getUserId());
        response.setUserName(order.getUserName());
        response.setTotalAmount(order.getTotalAmount());
        response.setStatus(order.getStatus());
        response.setStatusName(order.getStatusName() != null ? order.getStatusName() :
                ORDER_STATUS_MAP.getOrDefault(order.getStatus(), "未知"));
        response.setReceiverName(order.getReceiverName());
        response.setReceiverPhone(order.getReceiverPhone());
        response.setReceiverAddress(order.getReceiverAddress());
        response.setRemark(order.getRemark());
        response.setCreateTime(order.getCreateTime());
        response.setUpdateTime(order.getUpdateTime());
        return response;
    }
}
