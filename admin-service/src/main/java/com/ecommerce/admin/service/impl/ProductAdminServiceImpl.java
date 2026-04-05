package com.ecommerce.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.product.ProductRequest;
import com.ecommerce.admin.dto.product.ProductResponse;
import com.ecommerce.admin.entity.Category;
import com.ecommerce.admin.entity.Product;
import com.ecommerce.admin.mapper.CategoryMapper;
import com.ecommerce.admin.mapper.ProductMapper;
import com.ecommerce.admin.service.ProductAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductAdminServiceImpl implements ProductAdminService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;

    @Override
    public PageResponse<ProductResponse> getProductPage(int page, int size, String keyword, Long categoryId, Integer status) {
        Page<Product> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(Product::getName, keyword);
        }
        if (categoryId != null) {
            wrapper.eq(Product::getCategoryId, categoryId);
        }
        if (status != null) {
            wrapper.eq(Product::getStatus, status);
        }

        wrapper.orderByDesc(Product::getCreateTime);
        IPage<Product> result = productMapper.selectPage(pageParam, wrapper);

        List<ProductResponse> records = result.getRecords().stream()
                .map(this::toProductResponse)
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
    @Cacheable(value = "product:detail:#id", unless = "#result == null")
    public ProductResponse getProductById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        return toProductResponse(product);
    }

    @Override
    @CacheEvict(value = {"product:page", "product:detail"}, allEntries = true)
    public ProductResponse createProduct(ProductRequest request) {
        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setCategoryId(request.getCategoryId());
        product.setBrand(request.getBrand());
        product.setStock(request.getStock());
        product.setImageUrl(request.getImageUrl());
        product.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        product.setSales(0);

        Category category = categoryMapper.selectById(request.getCategoryId());
        if (category != null) {
            product.setCategoryName(category.getName());
        }

        productMapper.insert(product);
        return toProductResponse(product);
    }

    @Override
    @CacheEvict(value = {"product:page", "product:detail"}, allEntries = true)
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setCategoryId(request.getCategoryId());
        product.setBrand(request.getBrand());
        product.setStock(request.getStock());
        product.setImageUrl(request.getImageUrl());
        if (request.getStatus() != null) {
            product.setStatus(request.getStatus());
        }

        Category category = categoryMapper.selectById(request.getCategoryId());
        if (category != null) {
            product.setCategoryName(category.getName());
        }

        productMapper.updateById(product);
        return toProductResponse(product);
    }

    @Override
    @CacheEvict(value = {"product:page", "product:detail"}, allEntries = true)
    public void deleteProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        product.setStatus(0);
        productMapper.updateById(product);
    }

    @Override
    @CacheEvict(value = {"product:page", "product:detail"}, allEntries = true)
    public Map<String, Object> updateStock(Long id, int stock) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        product.setStock(stock);
        productMapper.updateById(product);

        Map<String, Object> result = new HashMap<>();
        result.put("id", product.getId());
        result.put("name", product.getName());
        result.put("stock", product.getStock());
        return result;
    }

    @Override
    @Cacheable(value = "categories:all", unless = "#result == null")
    public PageResponse<Map<String, Object>> getCategories() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1);
        wrapper.orderByAsc(Category::getSort);
        List<Category> categories = categoryMapper.selectList(wrapper);

        List<Map<String, Object>> records = categories.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("name", c.getName());
            map.put("icon", c.getIcon());
            map.put("sort", c.getSort());
            return map;
        }).collect(Collectors.toList());

        return new PageResponse<>(records, 1L, (long) records.size(), (long) records.size(), 1L);
    }

    private ProductResponse toProductResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setPrice(product.getPrice());
        response.setOriginalPrice(product.getOriginalPrice());
        response.setCategoryId(product.getCategoryId());
        response.setCategoryName(product.getCategoryName());
        response.setBrand(product.getBrand());
        response.setStock(product.getStock());
        response.setSales(product.getSales());
        response.setImageUrl(product.getImageUrl());
        response.setStatus(product.getStatus());
        response.setStatusName(product.getStatus() != null && product.getStatus() == 1 ? "上架" : "下架");
        response.setCreateTime(product.getCreateTime());
        response.setUpdateTime(product.getUpdateTime());
        return response;
    }
}
