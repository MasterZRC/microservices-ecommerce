package com.ecommerce.product.dto;

import com.ecommerce.product.entity.Product;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ProductPageResponse implements Serializable {
    private List<Product> products;
    private Long total;
    private Integer page;
    private Integer pageSize;
}