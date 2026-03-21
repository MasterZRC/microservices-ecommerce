package com.ecommerce.order.dto;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrderDetailResponse {

    private Long id;

    private String orderNo;

    private Long userId;

    private java.math.BigDecimal totalAmount;

    private Integer status;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String messageId;

    private java.time.LocalDateTime createTime;

    private java.time.LocalDateTime updateTime;

    private List<OrderItem> items = new ArrayList<>();

    public static OrderDetailResponse from(Order order, List<OrderItem> items) {
        OrderDetailResponse response = new OrderDetailResponse();
        response.setId(order.getId());
        response.setOrderNo(order.getOrderNo());
        response.setUserId(order.getUserId());
        response.setTotalAmount(order.getTotalAmount());
        response.setStatus(order.getStatus());
        response.setReceiverName(order.getReceiverName());
        response.setReceiverPhone(order.getReceiverPhone());
        response.setReceiverAddress(order.getReceiverAddress());
        response.setMessageId(order.getMessageId());
        response.setCreateTime(order.getCreateTime());
        response.setUpdateTime(order.getUpdateTime());
        response.setItems(items == null ? new ArrayList<>() : items);
        return response;
    }
}
