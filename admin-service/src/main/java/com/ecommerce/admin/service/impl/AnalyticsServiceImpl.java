package com.ecommerce.admin.service.impl;

import com.ecommerce.admin.mapper.AnalyticsMapper;
import com.ecommerce.admin.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final Map<Integer, String> STATUS_NAME = Map.of(
            0, "待支付",
            1, "已支付",
            2, "已发货",
            3, "已完成",
            4, "已取消"
    );

    private final AnalyticsMapper analyticsMapper;

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    @Override
    public List<Map<String, Object>> orderStatusDistribution(int days) {
        days = clamp(days, 1, 365);
        List<Map<String, Object>> rows = analyticsMapper.orderStatusDistribution(days);
        for (Map<String, Object> r : rows) {
            Object s = r.get("status");
            if (s instanceof Number) {
                r.put("statusName", STATUS_NAME.getOrDefault(((Number) s).intValue(), "未知"));
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> salesTrendDaily(int days) {
        days = clamp(days, 1, 90);
        return analyticsMapper.salesTrendDaily(days);
    }

    @Override
    public List<Map<String, Object>> topProducts(String metric, int days, int limit) {
        days = clamp(days, 1, 365);
        limit = clamp(limit, 1, 50);
        String m = metric == null ? "sales" : metric.toLowerCase();
        return switch (m) {
            case "sales" -> analyticsMapper.topProductsBySales(days, limit);
            case "exposure" -> analyticsMapper.topProductsByExposure(days, limit);
            case "click" -> analyticsMapper.topProductsByClick(days, limit);
            default -> throw new IllegalArgumentException("metric 仅支持 sales|exposure|click，收到: " + metric);
        };
    }

    @Override
    public Map<String, Object> cancellationRate(int days) {
        days = clamp(days, 1, 365);
        Map<String, Object> r = analyticsMapper.cancellationRate(days);
        if (r == null) r = new LinkedHashMap<>();
        r.putIfAbsent("totalCount", 0);
        r.putIfAbsent("canceledCount", 0);
        r.putIfAbsent("cancellationRatePercent", 0);
        r.put("days", days);
        return r;
    }

    @Override
    public List<Map<String, Object>> categoryPerformance(int days) {
        days = clamp(days, 1, 365);
        return analyticsMapper.categoryPerformance(days);
    }

    @Override
    public List<Map<String, Object>> executeReadonlySql(String sql) {
        log.info("[Analytics] readonly sql: {}", sql);
        return analyticsMapper.executeReadonlySql(sql);
    }
}
