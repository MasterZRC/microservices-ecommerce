package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.product.dto.ProductPageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.mapper.CategoryMapper;
import com.ecommerce.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;

        @Cacheable(
            value = "product",
            key = "'product:list:page:' + #page + ':size:' + #pageSize + ':keyword:' + (#keyword == null ? '' : #keyword) + ':category:' + (#categoryId == null ? 'all' : #categoryId)",
            unless = "#result == null"
        )
    public ProductPageResponse getProductList(Integer page, Integer pageSize, String keyword, Long categoryId) {
        Page<Product> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        
        wrapper.eq(Product::getStatus, 1);
        
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(w -> w.like(Product::getName, keyword)
                    .or().like(Product::getDescription, keyword));
        }
        if (categoryId != null) {
            wrapper.eq(Product::getCategoryId, categoryId);
        }
        
        wrapper.orderByDesc(Product::getCreateTime);
        
        Page<Product> result = productMapper.selectPage(pageParam, wrapper);
        
        ProductPageResponse response = new ProductPageResponse();
        response.setProducts(result.getRecords());
        response.setTotal(result.getTotal());
        response.setPage(page);
        response.setPageSize(pageSize);
        
        return response;
    }

    @Cacheable(value = "product", key = "'product:id:' + #id", unless = "#result == null")
    public Product getProductById(Long id) {
        return productMapper.selectById(id);
    }

    @CacheEvict(value = "product", allEntries = true)
    public Product createProduct(ProductRequest request) {
        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock() != null ? request.getStock() : 0);
        product.setImageUrl(request.getImageUrl());
        product.setCategoryId(request.getCategoryId());
        product.setBrand(request.getBrand());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setSales(0);
        product.setStatus(1);
        product.setCreateTime(LocalDateTime.now());
        
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category != null) {
                product.setCategoryName(category.getName());
            }
        }
        
        productMapper.insert(product);
        return product;
    }

    @CacheEvict(value = "product", allEntries = true)
    public Product updateProduct(ProductRequest request) {
        Product product = productMapper.selectById(request.getId());
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setImageUrl(request.getImageUrl());
        product.setCategoryId(request.getCategoryId());
        product.setBrand(request.getBrand());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setUpdateTime(LocalDateTime.now());
        
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category != null) {
                product.setCategoryName(category.getName());
            }
        }
        
        productMapper.updateById(product);
        return product;
    }

    @CacheEvict(value = "product", allEntries = true)
    public void deleteProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product != null) {
            product.setStatus(0);
            productMapper.updateById(product);
        }
    }

    public List<Category> getCategoryList() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1);
        wrapper.orderByAsc(Category::getSort);
        return categoryMapper.selectList(wrapper);
    }

    public boolean reduceStock(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity <= 0) {
            return false;
        }

        return productMapper.reduceStockAtomic(productId, quantity) > 0;
    }

    public void increaseStock(Long productId, Integer quantity) {
        if (productId != null && quantity != null && quantity > 0) {
            productMapper.increaseStockAtomic(productId, quantity);
        }
    }

    public List<Product> getProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return productMapper.selectBatchIds(ids);
    }
}