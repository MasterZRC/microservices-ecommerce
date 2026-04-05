package com.ecommerce.admin.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "仪表盘统计响应")
public class DashboardStatsResponse {

    @Schema(description = "用户总数")
    private Long userCount;

    @Schema(description = "商品总数")
    private Long productCount;

    @Schema(description = "订单总数")
    private Long orderCount;

    @Schema(description = "今日订单数")
    private Long todayOrderCount;

    @Schema(description = "今日销售额")
    private BigDecimal todaySales;

    @Schema(description = "总销售额")
    private BigDecimal totalSales;

    @Schema(description = "待处理订单数")
    private Long pendingOrderCount;

    @Schema(description = "库存不足商品数")
    private Long lowStockProductCount;
}
