package com.ecommerce.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductRequest {

    private Long id;

    @NotNull(message = "商品名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "商品价格不能为空")
    private BigDecimal price;

    private Integer stock;

    private String imageUrl;

    private Long categoryId;

    private String brand;

    private BigDecimal originalPrice;

    private Integer status;
}