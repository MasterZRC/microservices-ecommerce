package com.ecommerce.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.order.OrderResponse;
import com.ecommerce.admin.dto.order.OrderStatusRequest;
import com.ecommerce.admin.entity.Order;
import com.ecommerce.admin.mapper.OrderMapper;
import com.ecommerce.admin.service.OrderAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderAdminServiceImpl implements OrderAdminService {

    private final OrderMapper orderMapper;

    private static final Map<Integer, String> STATUS_MAP = new HashMap<>();
    static {
        STATUS_MAP.put(0, "待支付");
        STATUS_MAP.put(1, "已支付");
        STATUS_MAP.put(2, "已发货");
        STATUS_MAP.put(3, "已完成");
        STATUS_MAP.put(4, "已取消");
    }

    @Override
    public PageResponse<OrderResponse> getOrderPage(int page, int size, String orderNo, Long userId, Integer status) {
        Page<Order> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();

        if (orderNo != null && !orderNo.isEmpty()) {
            wrapper.eq(Order::getOrderNo, orderNo);
        }
        if (userId != null) {
            wrapper.eq(Order::getUserId, userId);
        }
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }

        wrapper.orderByDesc(Order::getCreateTime);
        IPage<Order> result = orderMapper.selectPage(pageParam, wrapper);

        List<OrderResponse> records = result.getRecords().stream()
                .map(this::toOrderResponse)
                .collect(Collectors.toList());

        return new PageResponse<>(
                records,
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages()
        );
    }

    @Override
    @Cacheable(value = "order:detail:#id", unless = "#result == null")
    public OrderResponse getOrderById(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        return toOrderResponse(order);
    }

    @Override
    @CacheEvict(value = {"order:page", "order:detail"}, allEntries = true)
    public OrderResponse updateOrderStatus(Long id, OrderStatusRequest request) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        if (request.getStatus() != null) {
            order.setStatus(request.getStatus());
            order.setStatusName(STATUS_MAP.getOrDefault(request.getStatus(), "未知"));
        }
        if (request.getRemark() != null) {
            order.setRemark(request.getRemark());
        }

        orderMapper.updateById(order);
        return toOrderResponse(order);
    }

    @Override
    public Map<String, Object> getOrderStats() {
        LambdaQueryWrapper<Order> totalWrapper = new LambdaQueryWrapper<>();
        Long totalCount = orderMapper.selectCount(totalWrapper);

        LambdaQueryWrapper<Order> pendingWrapper = new LambdaQueryWrapper<>();
        pendingWrapper.in(Order::getStatus, 0, 1, 2);
        Long pendingCount = orderMapper.selectCount(pendingWrapper);

        LambdaQueryWrapper<Order> paidWrapper = new LambdaQueryWrapper<>();
        paidWrapper.eq(Order::getStatus, 1);
        List<Order> paidOrders = orderMapper.selectList(paidWrapper);
        BigDecimal totalAmount = paidOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", totalCount);
        stats.put("pendingCount", pendingCount);
        stats.put("totalAmount", totalAmount);
        return stats;
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
                STATUS_MAP.getOrDefault(order.getStatus(), "未知"));
        response.setReceiverName(order.getReceiverName());
        response.setReceiverPhone(order.getReceiverPhone());
        response.setReceiverAddress(order.getReceiverAddress());
        response.setRemark(order.getRemark());
        response.setCreateTime(order.getCreateTime());
        response.setUpdateTime(order.getUpdateTime());
        return response;
    }
}
