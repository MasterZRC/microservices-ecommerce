package com.ecommerce.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.seckill.SeckillRequest;
import com.ecommerce.admin.dto.seckill.SeckillResponse;
import com.ecommerce.admin.entity.Product;
import com.ecommerce.admin.entity.SeckillActivity;
import com.ecommerce.admin.mapper.ProductMapper;
import com.ecommerce.admin.mapper.SeckillActivityMapper;
import com.ecommerce.admin.service.SeckillAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeckillAdminServiceImpl implements SeckillAdminService {

    private final SeckillActivityMapper seckillActivityMapper;
    private final ProductMapper productMapper;

    private static final Map<Integer, String> STATUS_MAP = new HashMap<>();
    static {
        STATUS_MAP.put(0, "已禁用");
        STATUS_MAP.put(1, "进行中");
        STATUS_MAP.put(2, "已结束");
    }

    @Override
    public PageResponse<SeckillResponse> getActivityPage(int page, int size, String keyword, Integer status) {
        Page<SeckillActivity> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SeckillActivity> wrapper = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(SeckillActivity::getName, keyword);
        }
        if (status != null) {
            wrapper.eq(SeckillActivity::getStatus, status);
        }

        wrapper.orderByDesc(SeckillActivity::getCreateTime);
        IPage<SeckillActivity> result = seckillActivityMapper.selectPage(pageParam, wrapper);

        List<SeckillResponse> records = result.getRecords().stream()
                .map(this::toSeckillResponse)
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
    @Cacheable(value = "seckill:detail:#id", unless = "#result == null")
    public SeckillResponse getActivityById(Long id) {
        SeckillActivity activity = seckillActivityMapper.selectById(id);
        if (activity == null) {
            throw new RuntimeException("秒杀活动不存在");
        }
        return toSeckillResponse(activity);
    }

    @Override
    @CacheEvict(value = {"seckill:page", "seckill:detail"}, allEntries = true)
    public SeckillResponse createActivity(SeckillRequest request) {
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        SeckillActivity activity = new SeckillActivity();
        activity.setName(request.getName());
        activity.setProductId(request.getProductId());
        activity.setProductName(product.getName());
        activity.setSeckillPrice(request.getSeckillPrice());
        activity.setTotalStock(request.getStock() != null ? request.getStock() : 100);
        activity.setStock(request.getStock());
        activity.setSoldCount(0);
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        activity.setStatusName(STATUS_MAP.getOrDefault(activity.getStatus(), "未知"));
        activity.setCreateTime(LocalDateTime.now());
        activity.setUpdateTime(LocalDateTime.now());

        seckillActivityMapper.insert(activity);
        return toSeckillResponse(activity);
    }

    @Override
    @CacheEvict(value = {"seckill:page", "seckill:detail"}, allEntries = true)
    public SeckillResponse updateActivity(Long id, SeckillRequest request) {
        SeckillActivity activity = seckillActivityMapper.selectById(id);
        if (activity == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        activity.setName(request.getName());

        if (request.getProductId() != null) {
            Product product = productMapper.selectById(request.getProductId());
            if (product != null) {
                activity.setProductId(request.getProductId());
                activity.setProductName(product.getName());
            }
        }

        activity.setSeckillPrice(request.getSeckillPrice());
        activity.setStock(request.getStock());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());

        if (request.getStatus() != null) {
            activity.setStatus(request.getStatus());
            activity.setStatusName(STATUS_MAP.getOrDefault(request.getStatus(), "未知"));
        }

        seckillActivityMapper.updateById(activity);
        return toSeckillResponse(activity);
    }

    @Override
    @CacheEvict(value = {"seckill:page", "seckill:detail"}, allEntries = true)
    public void deleteActivity(Long id) {
        SeckillActivity activity = seckillActivityMapper.selectById(id);
        if (activity == null) {
            throw new RuntimeException("秒杀活动不存在");
        }
        activity.setStatus(0);
        seckillActivityMapper.updateById(activity);
    }

    @Override
    @CacheEvict(value = {"seckill:page", "seckill:detail"}, allEntries = true)
    public Map<String, Object> updateStock(Long id, int stock) {
        SeckillActivity activity = seckillActivityMapper.selectById(id);
        if (activity == null) {
            throw new RuntimeException("秒杀活动不存在");
        }
        activity.setStock(stock);
        seckillActivityMapper.updateById(activity);

        Map<String, Object> result = new HashMap<>();
        result.put("id", activity.getId());
        result.put("name", activity.getName());
        result.put("stock", activity.getStock());
        return result;
    }

    private SeckillResponse toSeckillResponse(SeckillActivity activity) {
        SeckillResponse response = new SeckillResponse();
        response.setId(activity.getId());
        response.setName(activity.getName());
        response.setProductId(activity.getProductId());
        response.setProductName(activity.getProductName());
        response.setSeckillPrice(activity.getSeckillPrice());
        response.setStock(activity.getStock());
        response.setSoldCount(activity.getSoldCount());
        response.setStartTime(activity.getStartTime());
        response.setEndTime(activity.getEndTime());
        response.setStatus(activity.getStatus());
        response.setStatusName(activity.getStatusName() != null ? activity.getStatusName() :
                STATUS_MAP.getOrDefault(activity.getStatus(), "未知"));
        response.setCreateTime(activity.getCreateTime());
        return response;
    }
}
