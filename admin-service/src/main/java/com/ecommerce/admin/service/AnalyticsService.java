package com.ecommerce.admin.service;

import java.util.List;
import java.util.Map;

/**
 * 经营分析 Service：所有方法只读。
 */
public interface AnalyticsService {

    /** 状态分布（含 status 名称） */
    List<Map<String, Object>> orderStatusDistribution(int days);

    /** 销售时序（按天聚合） */
    List<Map<String, Object>> salesTrendDaily(int days);

    /** 商品 Top N，metric ∈ sales|exposure|click */
    List<Map<String, Object>> topProducts(String metric, int days, int limit);

    /** 取消率 */
    Map<String, Object> cancellationRate(int days);

    /** 类目维度业绩 */
    List<Map<String, Object>> categoryPerformance(int days);

    /** 只读 SQL 透传执行（调用方需自行校验） */
    List<Map<String, Object>> executeReadonlySql(String sql);
}
