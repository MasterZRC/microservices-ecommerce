package com.ecommerce.admin.service;

import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.product.ProductRequest;
import com.ecommerce.admin.dto.product.ProductResponse;

import java.util.Map;

public interface ProductAdminService {

    PageResponse<ProductResponse> getProductPage(int page, int size, String keyword, Long categoryId, Integer status);

    ProductResponse getProductById(Long id);

    ProductResponse createProduct(ProductRequest request);

    ProductResponse updateProduct(Long id, ProductRequest request);

    void deleteProduct(Long id);

    Map<String, Object> updateStock(Long id, int stock);

    PageResponse<Map<String, Object>> getCategories();
}
