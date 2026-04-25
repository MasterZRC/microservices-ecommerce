package com.ecommerce.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 经营分析专用 Mapper：聚合查询，仅读不写。
 *
 * 表结构提示：
 *  - order_info(id, user_id, total_amount, status, create_time)
 *      status: 0=待支付 1=已支付 2=已发货 3=已完成 4=已取消
 *  - order_item(id, order_id, product_id, product_name, price, quantity)
 *  - product(id, name, category_id, sales)
 *  - category(id, name)
 *  - product_exposure(id, user_id, product_id, recommend_type, position, create_time)
 *  - user_behavior(id, user_id, product_id, behavior_type, create_time)
 */
@Mapper
public interface AnalyticsMapper {

    /**
     * 订单状态分布：返回 [{status, statusName, count, totalAmount}]
     */
    @Select("""
            SELECT
                status,
                COUNT(*) AS count,
                COALESCE(SUM(total_amount), 0) AS totalAmount
            FROM order_info
            WHERE create_time >= NOW() - INTERVAL #{days} DAY
            GROUP BY status
            """)
    List<Map<String, Object>> orderStatusDistribution(@Param("days") int days);

    /**
     * 销售时序：按天聚合，仅算已支付（status>=1 且 status!=4）。
     */
    @Select("""
            SELECT
                DATE(create_time) AS day,
                COUNT(*) AS orderCount,
                COALESCE(SUM(total_amount), 0) AS sales
            FROM order_info
            WHERE create_time >= CURDATE() - INTERVAL #{days} DAY
              AND status IN (1, 2, 3)
            GROUP BY DATE(create_time)
            ORDER BY day ASC
            """)
    List<Map<String, Object>> salesTrendDaily(@Param("days") int days);

    /**
     * 商品销量 Top N：按已支付订单的 order_item 聚合。
     */
    @Select("""
            SELECT
                oi.product_id AS productId,
                oi.product_name AS productName,
                SUM(oi.quantity) AS soldQuantity,
                SUM(oi.price * oi.quantity) AS gmv
            FROM order_item oi
            JOIN order_info o ON oi.order_id = o.id
            WHERE o.create_time >= CURDATE() - INTERVAL #{days} DAY
              AND o.status IN (1, 2, 3)
            GROUP BY oi.product_id, oi.product_name
            ORDER BY soldQuantity DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topProductsBySales(@Param("days") int days, @Param("limit") int limit);

    /**
     * 商品曝光 Top N（来自推荐曝光表）。
     */
    @Select("""
            SELECT
                pe.product_id AS productId,
                p.name AS productName,
                COUNT(*) AS exposureCount
            FROM product_exposure pe
            LEFT JOIN product p ON p.id = pe.product_id
            WHERE pe.create_time >= NOW() - INTERVAL #{days} DAY
            GROUP BY pe.product_id, p.name
            ORDER BY exposureCount DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topProductsByExposure(@Param("days") int days, @Param("limit") int limit);

    /**
     * 商品点击 Top N（user_behavior 中 behavior_type='click'）。
     */
    @Select("""
            SELECT
                ub.product_id AS productId,
                p.name AS productName,
                COUNT(*) AS clickCount
            FROM user_behavior ub
            LEFT JOIN product p ON p.id = ub.product_id
            WHERE ub.create_time >= NOW() - INTERVAL #{days} DAY
              AND ub.behavior_type = 'click'
            GROUP BY ub.product_id, p.name
            ORDER BY clickCount DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topProductsByClick(@Param("days") int days, @Param("limit") int limit);

    /**
     * 取消率：取消订单数 / 总订单数。返回单行 {totalCount, canceledCount, cancellationRate}。
     */
    @Select("""
            SELECT
                COUNT(*) AS totalCount,
                COALESCE(SUM(CASE WHEN status = 4 THEN 1 ELSE 0 END), 0) AS canceledCount,
                ROUND(
                    COALESCE(SUM(CASE WHEN status = 4 THEN 1 ELSE 0 END), 0) * 100.0 /
                    NULLIF(COUNT(*), 0),
                    2
                ) AS cancellationRatePercent
            FROM order_info
            WHERE create_time >= NOW() - INTERVAL #{days} DAY
            """)
    Map<String, Object> cancellationRate(@Param("days") int days);

    /**
     * 各类目销售额（最近 N 天）。
     */
    @Select("""
            SELECT
                c.id AS categoryId,
                c.name AS categoryName,
                COALESCE(SUM(oi.price * oi.quantity), 0) AS gmv,
                COUNT(DISTINCT oi.product_id) AS productCount,
                COALESCE(SUM(oi.quantity), 0) AS soldQuantity
            FROM order_item oi
            JOIN order_info o ON oi.order_id = o.id
            LEFT JOIN product p ON p.id = oi.product_id
            LEFT JOIN category c ON c.id = p.category_id
            WHERE o.create_time >= CURDATE() - INTERVAL #{days} DAY
              AND o.status IN (1, 2, 3)
            GROUP BY c.id, c.name
            ORDER BY gmv DESC
            """)
    List<Map<String, Object>> categoryPerformance(@Param("days") int days);

    /**
     * 受限只读 SQL 工具：执行任意 SELECT 并返回结果。
     * 调用方必须先经过 sql_guard 校验，确保是只读语句、表在白名单内、含 LIMIT。
     * 这里只是一个透明执行器，不做安全检查。
     */
    @Select("${sql}")
    List<Map<String, Object>> executeReadonlySql(@Param("sql") String sql);
}
