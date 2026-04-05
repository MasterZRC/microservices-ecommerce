package com.ecommerce.admin.service;

import com.ecommerce.admin.dto.common.PageResponse;
import com.ecommerce.admin.dto.seckill.SeckillRequest;
import com.ecommerce.admin.dto.seckill.SeckillResponse;
import java.util.Map;

public interface SeckillAdminService {

    PageResponse<SeckillResponse> getActivityPage(int page, int size, String keyword, Integer status);

    SeckillResponse getActivityById(Long id);

    SeckillResponse createActivity(SeckillRequest request);

    SeckillResponse updateActivity(Long id, SeckillRequest request);

    void deleteActivity(Long id);

    Map<String, Object> updateStock(Long id, int stock);
}
