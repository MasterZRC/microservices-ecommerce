package com.ecommerce.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OrderCreateRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private List<CartItem> items;

    @Data
    public static class CartItem {
        private Long productId;
        private Integer quantity;
    }
}